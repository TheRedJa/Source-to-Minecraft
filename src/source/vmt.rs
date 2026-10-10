//! Reading `.vmt` material definitions.
//!
//! A VMT is a small VDF document: a shader name, then keys. All this needs
//! from it is which texture to draw and whether the surface is see-through:
//!
//! ```text
//! "LightmappedGeneric"
//! {
//!     "$basetexture" "Concrete/concretewall001a"
//!     "$surfaceprop" "concrete"
//! }
//! ```
//!
//! It is scanned rather than parsed into typed shaders. Source has dozens of
//! shaders and mods add their own, so a typed parser turns an unrecognised
//! shader into a missing texture; scanning for four keys cannot. It also has
//! to cope with `patch`, which is what the compiler writes into a map's
//! pakfile for every cubemap-lit surface, and which no VMT library models:
//!
//! ```text
//! "patch"
//! {
//!     "include" "materials/concrete/concretewall001a.vmt"
//!     "insert" { "$envmap" "env_cubemap" }
//! }
//! ```

use crate::source::vfs::Vfs;
use std::collections::HashMap;

/// How deep an `include` chain may go before we assume it is a cycle.
const MAX_INCLUDE_DEPTH: usize = 8;

/// What a material tells us about its appearance.
#[derive(Debug, Clone, PartialEq)]
pub struct MaterialAssets {
    /// `$basetexture`, relative to `materials/` and without an extension.
    pub base_texture: String,
    /// The surface is see-through where its alpha channel says so.
    pub alpha_test: bool,
    /// The surface is blended, like glass.
    pub translucent: bool,
    /// `$surfaceprop`, e.g. `concrete` or `metalgrate`.
    pub surface_prop: Option<String>,
    /// `$nocull`: both sides are drawn, as on a single-sheet fence mesh.
    pub no_cull: bool,
    /// `$bumpmap` of a lightmapped world material, relative to `materials/`:
    /// the normal map (or with `ssbump` the self-shadowed bump map) that
    /// weights the face's three bump lightmaps. `None` with
    /// `$nodiffusebumplighting`, which draws the flat lightmap.
    pub bump_map: Option<String>,
    /// `$ssbump`: `bump_map` holds each bump basis direction's light share.
    pub ssbump: bool,
    /// `$envmap` of a lightmapped world material: the cubemap it reflects.
    pub envmap: Option<Envmap>,
    /// `$basetexture2` of a `WorldVertexTransition` displacement material,
    /// which its vertices' alpha blends in.
    pub base_texture2: Option<String>,
    /// `$blendmodulatetexture`: green the blend's centre, red its width.
    pub blend_modulate: Option<String>,
    /// `$detail` of a lightmapped world material.
    pub detail: Option<Detail>,
    /// `$selfillumtint` of a `$selfillum` lightmapped or vertex-lit material,
    /// which glows by its base texture's alpha. A model's `$selfillummask` or
    /// `$selfillumfresnel` glow is not carried yet.
    pub selfillum: Option<[f32; 3]>,
}

/// A detail texture as LightmappedGeneric combines it.
#[derive(Debug, Clone, PartialEq)]
pub struct Detail {
    pub texture: String,
    /// `$detailscale`: repeats per base texture repeat, along s and t.
    pub scale: [f32; 2],
    /// `$detailblendfactor`, default 1.
    pub blend_factor: f32,
    /// `$detailblendmode`, `TCOMBINE_*`, default 0.
    pub blend_mode: u8,
    /// `$detailtint`, default white.
    pub tint: [f32; 3],
}

/// A material's cubemap reflection, as LightmappedGeneric draws it.
#[derive(Debug, Clone, PartialEq)]
pub struct Envmap {
    /// `$envmap` as written, relative to `materials/`: `env_cubemap` for the
    /// nearest `env_cubemap` (which vbsp writes into a patch of the material),
    /// or a cubemap texture.
    pub texture: String,
    /// `$envmaptint`, linear, default white.
    pub tint: [f32; 3],
    /// `$envmapcontrast`: 0 leaves the reflection, 1 squares it.
    pub contrast: f32,
    /// `$envmapsaturation`, per channel: 0 grey, 1 as reflected.
    pub saturation: [f32; 3],
    /// `$fresnelreflection`: the reflection facing it; 1, the default, is no
    /// Fresnel falloff.
    pub fresnel: f32,
    /// Where the reflection's strength comes from.
    pub mask: EnvmapMask,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EnvmapMask {
    None,
    /// `$envmapmask`, a texture over the base texture's coordinates.
    Texture(String),
    /// `$basealphaenvmapmask`: one minus the base texture's alpha.
    BaseAlpha,
    /// `$normalmapalphaenvmapmask`: the bump map's alpha.
    NormalAlpha,
}

/// A `$` vector value: `[x y z]` as written, `{r g b}` as 0-255 colour, or one
/// number for all three. `None` when nothing parses.
pub fn vector3(value: &str) -> Option<[f32; 3]> {
    let value = value.trim().trim_matches('"').trim();
    let (inner, scale) = if let Some(inner) = value.strip_prefix('{') {
        (inner.trim_end_matches('}'), 1.0 / 255.0)
    } else {
        (value.trim_start_matches('[').trim_end_matches(']'), 1.0)
    };
    let numbers: Vec<f32> = inner
        .split_whitespace()
        .filter_map(|n| n.parse::<f32>().ok())
        .filter(|n| n.is_finite())
        .collect();
    match numbers.as_slice() {
        [x] => Some([x * scale; 3]),
        [x, y, z, ..] => Some([x * scale, y * scale, z * scale]),
        _ => None,
    }
}

/// A `$` scalar: its first number, as `atof` reads it.
pub fn scalar(value: &str) -> Option<f32> {
    let value = value.trim().trim_matches('"').trim();
    let end = value
        .char_indices()
        .find(|&(i, c)| !(c.is_ascii_digit() || c == '.' || ((c == '-' || c == '+') && i == 0)))
        .map_or(value.len(), |(i, _)| i);
    value[..end].parse::<f32>().ok().filter(|v| v.is_finite())
}

/// A parsed VMT: its shader and every scalar key found in it.
#[derive(Debug, Default, Clone)]
pub struct Vmt {
    pub shader: String,
    /// Keys lowercased; values as written. Keys inside `replace`/`insert`
    /// blocks override the ones they patch, so later wins.
    pub keys: HashMap<String, String>,
}

impl Vmt {
    pub fn get(&self, key: &str) -> Option<&str> {
        self.keys.get(key).map(String::as_str)
    }

    fn flag(&self, key: &str) -> bool {
        // Source treats any non-zero value as set, and mappers write `1`,
        // `"1"` and occasionally `.5` for blend factors.
        self.get(key)
            .map(|v| v.trim_matches('"'))
            .is_some_and(|v| !v.is_empty() && v != "0")
    }

    pub fn is_patch(&self) -> bool {
        self.shader == "patch"
    }
}

/// Scan a VMT into its shader name and the scalar keys a DX9 HDR system
/// draws it with.
///
/// Sub-blocks are applied as the material system does on such a system: the
/// conditional blocks that hold there (`hdr`, `>=dx90`, ...) where they stand,
/// then the one shader fallback block for the hardware, `<shader>_HDR_DX9` or
/// else `<shader>_DX9`, over the top-level keys; `replace` and `insert` blocks
/// patch them. Every other block -- lower DirectX levels, `<dx90`, `ldr`,
/// `Proxies` -- is skipped, contents and all: Portal's materials keep their
/// DX9 bump map in `_DX9` and a different one in `_DX8`.
pub fn parse(text: &str) -> Vmt {
    let mut vmt = Vmt::default();
    let tokens = tokenize(text);
    let mut i = 0;
    // The first token is the shader, unless the file opens straight into a
    // block, which some hand-written materials do.
    if let Some(first) = tokens.first()
        && first != "{"
    {
        vmt.shader = first.to_ascii_lowercase();
        i = 1;
    }
    if tokens.get(i).is_some_and(|t| t == "{") {
        i += 1;
    }
    let hdr_block = format!("{}_hdr_dx9", vmt.shader);
    let dx9_blocks = [format!("{}_dx9", vmt.shader), format!("{}_dx90", vmt.shader)];
    let mut hdr = None;
    let mut dx9 = None;
    let mut patches = Vec::new();
    while i < tokens.len() {
        let token = &tokens[i];
        if token == "}" {
            break;
        }
        if token == "{" {
            i = skip_block(&tokens, i);
            continue;
        }
        match tokens.get(i + 1) {
            Some(next) if next == "{" => {
                let name = token.to_ascii_lowercase();
                let (keys, after) = block_keys(&tokens, i + 1);
                if name == "replace" || name == "insert" {
                    patches.push(keys);
                } else if name == hdr_block {
                    hdr = Some(keys);
                } else if dx9_blocks.contains(&name) {
                    dx9 = Some(keys);
                } else if condition_holds(&name) {
                    vmt.keys.extend(keys);
                }
                i = after;
            }
            Some(value) => {
                vmt.keys.insert(token.to_ascii_lowercase(), value.clone());
                i += 2;
            }
            None => break,
        }
    }
    if let Some(keys) = hdr.or(dx9) {
        vmt.keys.extend(keys);
    }
    for keys in patches {
        vmt.keys.extend(keys);
    }
    vmt
}

/// The index after the block opening at `open`.
fn skip_block(tokens: &[String], open: usize) -> usize {
    let mut depth = 0usize;
    let mut i = open;
    while i < tokens.len() {
        match tokens[i].as_str() {
            "{" => depth += 1,
            "}" => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    return i + 1;
                }
            }
            _ => {}
        }
        i += 1;
    }
    i
}

/// The scalar keys directly inside the block opening at `open`, nested
/// blocks skipped, and the index after it.
fn block_keys(tokens: &[String], open: usize) -> (Vec<(String, String)>, usize) {
    let mut keys = Vec::new();
    let mut i = open + 1;
    while i < tokens.len() {
        let token = &tokens[i];
        if token == "}" {
            return (keys, i + 1);
        }
        if token == "{" {
            i = skip_block(tokens, i);
            continue;
        }
        match tokens.get(i + 1) {
            Some(next) if next == "{" => i = skip_block(tokens, i + 1),
            Some(value) => {
                keys.push((token.to_ascii_lowercase(), value.clone()));
                i += 2;
            }
            None => i += 1,
        }
    }
    (keys, i)
}

/// Whether a conditional block holds on a DX9 (shader model 2.0b and up)
/// system with HDR on, at the highest GPU level (3) the later games know:
/// `hdr`, `srgb`, and comparisons such as `>=dx90`, `<dx90_20b` or `GPU>=1`.
fn condition_holds(name: &str) -> bool {
    let name = name.trim_end_matches('?').replace(' ', "");
    if matches!(name.as_str(), "hdr" | "srgb") {
        return true;
    }
    let (op, rest) = ["<=", ">=", "==", "!=", "<", ">"]
        .iter()
        .find_map(|op| name.strip_prefix(op).map(|rest| (*op, rest)))
        .or_else(|| {
            ["<=", ">=", "==", "!=", "<", ">"]
                .iter()
                .find_map(|op| name.find(op).map(|at| (*op, &name[at..])))
                .and_then(|(op, tail)| tail.strip_prefix(op).map(|rest| (op, rest)))
        })
        .unwrap_or(("", name.as_str()));
    // `GPU>=1`: the GPU level; `>=dx90`, `dx90_20b`: the DirectX level, 2.0b above plain 9.0.
    let (have, want) = if name.starts_with("gpu") {
        (3, rest.parse::<i32>().ok())
    } else if let Some(level) = rest.strip_prefix("dx") {
        let (digits, profile) = level.split_once('_').unwrap_or((level, ""));
        let want = digits.parse::<i32>().ok().map(|d| d * 10 + i32::from(profile == "20b"));
        (951, want)
    } else {
        return false;
    };
    let Some(want) = want else { return false };
    match op {
        ">=" => have >= want,
        ">" => have > want,
        "<=" => have <= want,
        "<" => have < want,
        "!=" => have != want,
        _ => have == want,
    }
}

/// Split VDF text into tokens, dropping comments and platform conditionals.
pub(crate) fn tokenize(text: &str) -> Vec<String> {
    tokenize_with(text, false)
}

/// As [`tokenize`], optionally resolving `\"`, `\\`, `\n` and `\t` inside quoted
/// strings, as KeyValues files loaded with escape sequences (localization and
/// caption files) are read.
pub(crate) fn tokenize_with(text: &str, escapes: bool) -> Vec<String> {
    let mut tokens = Vec::new();
    let bytes = text.as_bytes();
    let mut i = 0;

    while i < bytes.len() {
        let c = bytes[i];
        if c.is_ascii_whitespace() {
            i += 1;
        } else if c == b'/' && bytes.get(i + 1) == Some(&b'/') {
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
        } else if c == b'[' {
            // `$basetexture "x" [!$X360]` — a conditional we never satisfy or
            // reject, so it is simply not a token.
            while i < bytes.len() && bytes[i] != b']' {
                i += 1;
            }
            i += 1;
        } else if c == b'"' && escapes {
            i += 1;
            let mut token = Vec::new();
            while i < bytes.len() && bytes[i] != b'"' {
                if bytes[i] == b'\\' && i + 1 < bytes.len() {
                    let escaped = match bytes[i + 1] {
                        b'"' => Some(b'"'),
                        b'\\' => Some(b'\\'),
                        b'n' => Some(b'\n'),
                        b't' => Some(b'\t'),
                        _ => None,
                    };
                    if let Some(escaped) = escaped {
                        token.push(escaped);
                        i += 2;
                        continue;
                    }
                }
                token.push(bytes[i]);
                i += 1;
            }
            tokens.push(String::from_utf8_lossy(&token).into_owned());
            i += 1;
        } else if c == b'"' {
            i += 1;
            let start = i;
            while i < bytes.len() && bytes[i] != b'"' {
                i += 1;
            }
            tokens.push(String::from_utf8_lossy(&bytes[start..i]).into_owned());
            i += 1;
        } else if c == b'{' || c == b'}' {
            tokens.push((c as char).to_string());
            i += 1;
        } else {
            let start = i;
            while i < bytes.len()
                && !bytes[i].is_ascii_whitespace()
                && !matches!(bytes[i], b'{' | b'}' | b'"' | b'[')
            {
                i += 1;
            }
            tokens.push(String::from_utf8_lossy(&bytes[start..i]).into_owned());
        }
    }
    tokens
}

/// Resolves materials against a search path, following `patch` includes.
pub struct Materials<'a> {
    vfs: &'a Vfs,
    /// The map's own pakfile, tried first: it holds the compiler's patches.
    pak: Option<&'a vbsp::Packfile>,
}

impl<'a> Materials<'a> {
    pub fn new(vfs: &'a Vfs, pak: Option<&'a vbsp::Packfile>) -> Materials<'a> {
        Materials { vfs, pak }
    }

    pub fn read(&self, path: &str) -> Option<Vec<u8>> {
        if let Some(pak) = self.pak {
            // Pakfile entries are stored lowercase with forward slashes.
            let key = path.to_ascii_lowercase().replace('\\', "/");
            if let Ok(Some(data)) = pak.get(&key) {
                return Some(data);
            }
        }
        self.vfs.open(path)
    }

    /// Resolve a material name such as `concrete/concretewall001a`.
    ///
    /// `raw_name` is the name as the BSP stored it, which for a patched
    /// material is the pakfile stub; it is only consulted if the authored
    /// path is missing, since the stub adds a cubemap and nothing else.
    pub fn assets(&self, name: &str, raw_name: Option<&str>) -> Option<MaterialAssets> {
        // The compiler's patch first, which is what the game draws: it names
        // the face's cubemap and replaces a blend material off displacements.
        let vmt = raw_name
            .filter(|r| !r.eq_ignore_ascii_case(name))
            .and_then(|r| self.load(r, 0))
            .or_else(|| self.load(name, 0))?;

        let base_texture = vmt
            .get("$basetexture")
            // Blend materials paint two textures across a displacement; the
            // first is the one the surface mostly reads as.
            .or_else(|| vmt.get("$basetexture2"))
            .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
            .filter(|v| !v.is_empty())?;

        let lightmapped = matches!(
            vmt.shader.as_str(),
            "lightmappedgeneric" | "worldvertextransition"
        );
        let bump_map = vmt
            .get("$bumpmap")
            .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
            .filter(|v| !v.is_empty() && lightmapped && !vmt.flag("$nodiffusebumplighting"));
        let envmap = vmt
            .get("$envmap")
            .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
            .filter(|v| !v.is_empty() && lightmapped)
            .map(|texture| {
                let path = |key: &str| {
                    vmt.get(key)
                        .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
                        .filter(|v| !v.is_empty())
                };
                // LightmappedGeneric skips $envmapmask and $basealphaenvmapmask on a bump-mapped surface.
                let mask = if bump_map.is_some() {
                    if vmt.flag("$normalmapalphaenvmapmask") {
                        EnvmapMask::NormalAlpha
                    } else {
                        EnvmapMask::None
                    }
                } else if let Some(mask) = path("$envmapmask") {
                    EnvmapMask::Texture(mask)
                } else if vmt.flag("$basealphaenvmapmask") {
                    EnvmapMask::BaseAlpha
                } else {
                    EnvmapMask::None
                };
                Envmap {
                    texture,
                    tint: vmt.get("$envmaptint").and_then(vector3).unwrap_or([1.0; 3]),
                    contrast: vmt.get("$envmapcontrast").and_then(scalar).unwrap_or(0.0),
                    saturation: vmt.get("$envmapsaturation").and_then(vector3).unwrap_or([1.0; 3]),
                    fresnel: vmt.get("$fresnelreflection").and_then(scalar).unwrap_or(1.0),
                    mask,
                }
            });
        let path = |key: &str| {
            vmt.get(key)
                .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
                .filter(|v| !v.is_empty())
        };
        let blend_material = vmt.shader == "worldvertextransition" && vmt.get("$basetexture").is_some();
        let detail = path("$detail").filter(|_| lightmapped).map(|texture| {
            let scale = vmt
                .get("$detailscale")
                .map(|v| {
                    let v = v.trim().trim_matches('"').trim();
                    let numbers: Vec<f32> = v
                        .trim_start_matches('[')
                        .trim_end_matches(']')
                        .split_whitespace()
                        .filter_map(|n| n.parse::<f32>().ok())
                        .filter(|n| n.is_finite())
                        .collect();
                    match numbers.as_slice() {
                        [x, y, ..] => [*x, *y],
                        [x] => [*x, *x],
                        _ => [4.0, 4.0],
                    }
                })
                .unwrap_or([4.0, 4.0]);
            Detail {
                texture,
                scale,
                blend_factor: vmt.get("$detailblendfactor").and_then(scalar).unwrap_or(1.0),
                blend_mode: vmt
                    .get("$detailblendmode")
                    .and_then(scalar)
                    .map_or(0, |m| m.clamp(0.0, 11.0) as u8),
                tint: vmt.get("$detailtint").and_then(vector3).unwrap_or([1.0; 3]),
            }
        });
        Some(MaterialAssets {
            base_texture2: path("$basetexture2").filter(|_| blend_material),
            blend_modulate: path("$blendmodulatetexture").filter(|_| blend_material),
            detail,
            selfillum: (vmt.flag("$selfillum")
                && (lightmapped
                    || vmt.shader == "vertexlitgeneric"
                        && vmt.get("$selfillummask").is_none()
                        && !vmt.flag("$selfillumfresnel")))
                .then(|| vmt.get("$selfillumtint").and_then(vector3).unwrap_or([1.0; 3])),
            envmap,
            ssbump: bump_map.is_some() && vmt.flag("$ssbump"),
            bump_map,
            base_texture,
            alpha_test: vmt.flag("$alphatest"),
            translucent: vmt.flag("$translucent"),
            no_cull: vmt.flag("$nocull"),
            surface_prop: vmt
                .get("$surfaceprop")
                .map(|v| v.trim_matches('"').to_string()),
        })
    }

    /// A material's keys as written, patches merged; `None` when it is missing.
    pub fn vmt(&self, name: &str) -> Option<Vmt> {
        self.load(name, 0)
    }

    /// Load a VMT and merge in whatever it patches.
    fn load(&self, name: &str, depth: usize) -> Option<Vmt> {
        if depth > MAX_INCLUDE_DEPTH {
            return None;
        }
        let path = format!("materials/{}.vmt", name.trim_end_matches(".vmt"));
        let data = self.read(&path)?;
        let vmt = parse(&String::from_utf8_lossy(&data));

        let Some(include) = vmt.get("include") else {
            return Some(vmt);
        };

        // A patch's own keys are the overrides, so the included material is
        // loaded first and then written over.
        let included = include
            .trim_matches('"')
            .trim_start_matches("materials/")
            .trim_end_matches(".vmt")
            .to_string();
        let Some(mut base) = self.load(&included, depth + 1) else {
            return Some(vmt);
        };
        for (key, value) in vmt.keys {
            if key != "include" {
                base.keys.insert(key, value);
            }
        }
        Some(base)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn takes_the_dx9_fallback_block_and_skips_lower_levels() {
        let vmt = parse(
            r#"
            "LightmappedGeneric"
            {
                "$basetexture" "Concrete/observationwall_001b"
                "LightmappedGeneric_HDR_DX9"
                {
                    "$envmap" "env_cubemap"
                    "$normalmapalphaenvmapmask" 1
                    "$bumpmap" "concrete/observationwall_001b_height-ssbump"
                    "$ssbump" "1"
                }
                "LightmappedGeneric_DX9" { "$envmaptint" "[ .1 .1 .1 ]" }
                "LightmappedGeneric_DX8"
                {
                    "$bumpmap" "Concrete/observationwall_001b_normal"
                    "$nodiffusebumplighting" 1
                }
                "LightmappedGeneric_NoBump_DX8" { "$basetexture" "Concrete/other" }
                "Proxies" { "Sine" { "resultVar" "$alpha" } }
            }
            "#,
        );
        assert_eq!(vmt.get("$basetexture"), Some("Concrete/observationwall_001b"));
        assert_eq!(vmt.get("$bumpmap"), Some("concrete/observationwall_001b_height-ssbump"));
        assert_eq!(vmt.get("$nodiffusebumplighting"), None);
        // The HDR block wins over the plain DX9 one; only one applies.
        assert_eq!(vmt.get("$envmaptint"), None);
        assert_eq!(vmt.get("resultvar"), None);
    }

    #[test]
    fn applies_conditional_blocks_that_hold_with_hdr() {
        let vmt = parse(
            r#"VertexlitGeneric
            {
                $envmap "metal/black_wall_envmap_002a"
                hdr { $envmap "metal/black_wall_envmap_002a_hdr" }
                "<dx90" { $envmap "x" }
                ">=dx90" { $detailscale 4 }
                "GPU>=1" { $bumpmap "a" }
                "GPU<2" { $bumpmap "b" }
                "<dx90_20b" { $ssbump 1 }
            }"#,
        );
        assert_eq!(vmt.get("$bumpmap"), Some("a"));
        assert_eq!(vmt.get("$ssbump"), None);
        assert_eq!(vmt.get("$envmap"), Some("metal/black_wall_envmap_002a_hdr"));
        assert_eq!(vmt.get("$detailscale"), Some("4"));
    }

    #[test]
    fn reads_vectors_as_source_writes_them() {
        assert_eq!(vector3("[.5 .25 1]"), Some([0.5, 0.25, 1.0]));
        let color = vector3("{255 0 51}").unwrap();
        assert!((color[0] - 1.0).abs() < 1e-6 && color[1] == 0.0 && (color[2] - 0.2).abs() < 1e-6);
        assert_eq!(vector3(".3"), Some([0.3; 3]));
        assert_eq!(vector3("[]"), None);
        assert_eq!(scalar(".75 // comment"), Some(0.75));
        assert_eq!(scalar("1x"), Some(1.0));
    }

    #[test]
    fn parses_a_plain_material() {
        let vmt = parse(
            r#"
            "LightmappedGeneric"
            {
                "$basetexture" "Concrete/concretewall001a"
                "$surfaceprop" "concrete"
                "$detailblendfactor" .6
            }
            "#,
        );
        assert_eq!(vmt.shader, "lightmappedgeneric");
        assert_eq!(vmt.get("$basetexture"), Some("Concrete/concretewall001a"));
        assert_eq!(vmt.get("$surfaceprop"), Some("concrete"));
        assert_eq!(vmt.get("$detailblendfactor"), Some(".6"));
    }

    /// Real materials mix quoted and bare tokens and use `//` comments.
    #[test]
    fn handles_unquoted_values_and_comments() {
        let vmt = parse(
            r#"
            UnlitGeneric
            {
                // this is a comment with "quotes" in it
                $basetexture metal/metalwall001a
                $alphatest 1
            }
            "#,
        );
        assert_eq!(vmt.shader, "unlitgeneric");
        assert_eq!(vmt.get("$basetexture"), Some("metal/metalwall001a"));
        assert!(vmt.flag("$alphatest"));
    }

    /// `[$X360]` style conditionals must not be mistaken for values.
    #[test]
    fn platform_conditionals_are_dropped() {
        let vmt =
            parse(r#""LightmappedGeneric" { "$basetexture" "a/b" [!$X360] "$alphatest" "1" }"#);
        assert_eq!(vmt.get("$basetexture"), Some("a/b"));
        assert!(vmt.flag("$alphatest"));
    }

    /// Proxies and other nested blocks must not swallow the keys after them.
    #[test]
    fn nested_blocks_do_not_hide_later_keys() {
        let vmt = parse(
            r#"
            "LightmappedGeneric"
            {
                "$basetexture" "a/b"
                "Proxies"
                {
                    "TextureScroll"
                    {
                        "textureScrollVar" "$basetexturetransform"
                    }
                }
                "$translucent" "1"
            }
            "#,
        );
        assert_eq!(vmt.get("$basetexture"), Some("a/b"));
        assert!(vmt.flag("$translucent"));
    }

    #[test]
    fn nocull_is_read_as_a_flag() {
        let vmt =
            parse(r#""VertexlitGeneric" { "$basetexture" "a/b" "$alphatest" 1 "$nocull" 1 }"#);
        assert!(vmt.flag("$nocull"));
        assert!(!parse(r#""x" { "$basetexture" "a/b" }"#).flag("$nocull"));
    }

    #[test]
    fn flags_treat_zero_and_absent_as_off() {
        let vmt = parse(r#""x" { "$alphatest" "0" }"#);
        assert!(!vmt.flag("$alphatest"));
        assert!(!vmt.flag("$translucent"));
    }

    #[test]
    fn a_patch_is_recognised() {
        let vmt = parse(
            r#"
            "patch"
            {
                "include" "materials/concrete/concretewall001a.vmt"
                "insert" { "$envmap" "env_cubemap" }
            }
            "#,
        );
        assert!(vmt.is_patch());
        assert_eq!(
            vmt.get("include"),
            Some("materials/concrete/concretewall001a.vmt")
        );
        // Keys inside `insert` are flattened, which is what makes a patch's
        // overrides win over the material it includes.
        assert_eq!(vmt.get("$envmap"), Some("env_cubemap"));
    }

    #[test]
    fn empty_input_is_harmless() {
        let vmt = parse("");
        assert!(vmt.shader.is_empty());
        assert!(vmt.keys.is_empty());
    }

    // --- against real game content ---

    fn real_materials() -> Option<(Vfs, ())> {
        let map = std::path::Path::new(
            "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_02.bsp",
        );
        map.exists().then(|| (Vfs::for_map(map, &[]), ()))
    }

    #[test]
    fn resolves_real_half_life_2_materials() {
        let Some((vfs, _)) = real_materials() else {
            return;
        };
        let materials = Materials::new(&vfs, None);

        let concrete = materials.assets("concrete/concretewall001a", None).unwrap();
        assert_eq!(concrete.base_texture, "Concrete/concretewall001a");
        assert_eq!(concrete.surface_prop.as_deref(), Some("concrete"));
        assert!(!concrete.alpha_test);

        // A grate is alpha-tested, which is how we know to make it cutout.
        let grate = materials.assets("metal/metalgrate011a", None).unwrap();
        assert!(grate.alpha_test, "metalgrate011a should be alpha tested");

        // Blend materials name two textures; the first is enough.
        let blend = materials
            .assets("nature/blendgrassgravel001a", None)
            .unwrap();
        assert_eq!(blend.base_texture, "nature/dirtfloor006a");
    }

    #[test]
    fn a_missing_material_resolves_to_nothing() {
        let Some((vfs, _)) = real_materials() else {
            return;
        };
        let materials = Materials::new(&vfs, None);
        assert!(materials.assets("nothing/at/all", None).is_none());
    }
}
