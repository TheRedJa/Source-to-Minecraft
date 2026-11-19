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
    /// `$phong` of a `VertexLitGeneric` material that Source draws with its
    /// phong shader; see [`Phong`].
    pub phong: Option<Phong>,
    /// A material that shows the `point_camera` image (`$basetexture
    /// _rt_Camera`); see [`Camera`].
    pub camera: Option<Camera>,
}

/// A material whose `$basetexture` is `_rt_Camera`, the one render target
/// every `point_camera` draws into (baseclientrendertargets.cpp:37-46,
/// viewrender.cpp:3256-3282), drawn by one of the three shaders the monitor
/// materials use. Proxies are reduced to what reaches the picture: `Sine` on
/// `$color`, and `TextureScroll` or `LinearRamp` + `TextureTransform`
/// (translate only) on `$texture2transform`.
#[derive(Debug, Clone, PartialEq)]
pub struct Camera {
    pub shader: CameraShader,
    /// `$texture2`, relative to `materials/`; never for `UnlitGeneric`.
    pub texture2: Option<String>,
    /// `$additive`.
    pub additive: bool,
    /// `$color`, white by default; a channel with a sine is overwritten by it.
    pub color: [f32; 3],
    /// `$color2` (or `srgb?$color2`, which holds on a DX9 system that blends
    /// in sRGB), white by default.
    pub color2: [f32; 3],
    /// The `Sine` proxy that writes each `$color` channel last, if any.
    pub sines: [Option<Sine>; 3],
    /// `$texture2transform` as its proxy sets it; `None` is the identity.
    pub transform: Option<Texture2Transform>,
    /// `MonitorScreen`'s `$contrast` (0), `$saturation` (1) and `$tint` (white)
    /// (MonitorScreen_dx9.cpp SHADER_INIT_PARAMS).
    pub contrast: f32,
    pub saturation: f32,
    pub tint: [f32; 3],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CameraShader {
    UnlitGeneric,
    UnlitTwoTexture,
    MonitorScreen,
}

/// `CSineProxy` (game/client/mathproxy.cpp:382-416): `(max - min) * (sin(2 pi
/// (t - offset) / period) * 0.5 + 0.5) + min`, a period of 0 read as 1.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Sine {
    pub period: f32,
    pub min: f32,
    pub max: f32,
    pub offset: f32,
}

/// `$texture2transform` over time `t`: texture coordinates `uv` become
/// `scale * uv + offset + velocity * t`. `TextureScroll`
/// (texturescrollmaterialproxy.cpp:70-110) sets `scale` and a velocity of
/// `rate * (cos angle, sin angle)`, its offset wrapped into 0..1 (no change for
/// a repeating texture); `TextureTransform` (matrixproxy.cpp:75-112) with only
/// a translation takes it from its translate variable, each component a
/// `LinearRamp` (`rate * t + initialValue`, mathproxy.cpp:611-619) or fixed.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Texture2Transform {
    pub scale: f32,
    pub offset: [f32; 2],
    pub velocity: [f32; 2],
}

/// A `VertexLitGeneric` material's phong specular and rim light, as
/// `phong_ps20b.fxc` combines them. Only present when Source would pick the
/// phong shader: `$phong` set and a `$lightwarptexture`, `$basemapalphaphongmask`
/// or `$bumpmap` given. The bump map, when there is one, is `bump_map` of the
/// material.
#[derive(Debug, Clone, PartialEq)]
pub struct Phong {
    /// `$phongexponent` when it is set and not zero; `0` takes the exponent
    /// from `exponent_texture`'s red channel, `1 + 149 * red`.
    pub exponent: f32,
    /// `$phongexponenttexture`, relative to `materials/`: red the exponent,
    /// green the albedo-tint mask, alpha the rim mask.
    pub exponent_texture: Option<String>,
    /// `$phongboost`, default 1.
    pub boost: f32,
    /// `$phongfresnelranges`: Fresnel at grazing, head-on and in between;
    /// default `[0 0.5 1]`.
    pub fresnel_ranges: [f32; 3],
    /// `$phongtint`, default white. White with `albedo_tint`.
    pub tint: [f32; 3],
    /// The specular is tinted by `lerp(1, base colour, exponent texture
    /// green)`. Source does this only for a `$phongtint` of all zeros, with an
    /// exponent texture and `$phongalbedotint` not 0 (default 1).
    pub albedo_tint: bool,
    /// Where the specular strength comes from.
    pub mask: PhongMask,
    /// Half-Lambert diffuse for the phong term: not `$phongdisablehalflambert`.
    pub half_lambert: bool,
    /// `$phongwarptexture`: a 2D ramp over (n.h, Fresnel) replacing the
    /// specular falloff and Fresnel.
    pub warp: Option<String>,
    /// `$lightwarptexture`: a 1D ramp over the diffuse term.
    pub light_warp: Option<String>,
    /// `$rimlight`.
    pub rim: Option<Rim>,
}

/// The phong strength mask, in Source's precedence: luminance over base alpha
/// over the bump map's alpha.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PhongMask {
    /// The bump map's alpha (1 without a bump map).
    NormalAlpha,
    /// `$basemapalphaphongmask`: the base texture's alpha.
    BaseAlpha,
    /// `$basemapluminancephongmask`: the base texture's luminance.
    BaseLuminance,
}

/// `$rimlight`: a Fresnel-weighted glow at silhouettes.
#[derive(Debug, Clone, PartialEq)]
pub struct Rim {
    /// `$rimlightexponent`, at least 1, default 4.
    pub exponent: f32,
    /// `$rimlightboost`, default 1.
    pub boost: f32,
    /// `$rimmask` with an exponent texture, else 0: how much the exponent
    /// texture's alpha masks the rim, `lerp(1, alpha, mask)`.
    pub mask: f32,
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

/// The phong shader's parameters of a material, when Source would use it
/// (`WantsPhongShader`): `$phong` set and one of a light warp, a base-alpha
/// phong mask or a bump map given.
fn phong_of(vmt: &Vmt) -> Option<Phong> {
    if vmt.shader != "vertexlitgeneric" || !vmt.flag("$phong") {
        return None;
    }
    let path = |key: &str| {
        vmt.get(key)
            .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
            .filter(|v| !v.is_empty())
    };
    let light_warp = path("$lightwarptexture");
    let base_alpha = vmt.flag("$basemapalphaphongmask");
    if light_warp.is_none() && !base_alpha && path("$bumpmap").is_none() {
        return None;
    }
    let exponent_texture = path("$phongexponenttexture");
    let tint = vmt.get("$phongtint").and_then(vector3).unwrap_or([1.0; 3]);
    // Source tints with white where no tint is set, and by the albedo only
    // where an all-zero tint meets an exponent texture.
    let zero_tint = tint == [0.0; 3];
    let albedo_tint = zero_tint
        && exponent_texture.is_some()
        && vmt.get("$phongalbedotint").and_then(scalar).is_none_or(|v| v != 0.0);
    Some(Phong {
        exponent: vmt
            .get("$phongexponent")
            .and_then(scalar)
            .unwrap_or(0.0),
        boost: vmt.get("$phongboost").and_then(scalar).unwrap_or(1.0),
        fresnel_ranges: vmt
            .get("$phongfresnelranges")
            .and_then(vector3)
            .unwrap_or([0.0, 0.5, 1.0]),
        tint: if zero_tint { [1.0; 3] } else { tint },
        albedo_tint,
        mask: if vmt.flag("$basemapluminancephongmask") {
            PhongMask::BaseLuminance
        } else if base_alpha {
            PhongMask::BaseAlpha
        } else {
            PhongMask::NormalAlpha
        },
        half_lambert: !vmt.flag("$phongdisablehalflambert"),
        warp: path("$phongwarptexture"),
        light_warp,
        rim: vmt.flag("$rimlight").then(|| Rim {
            exponent: vmt
                .get("$rimlightexponent")
                .and_then(scalar)
                .unwrap_or(4.0)
                .max(1.0),
            boost: vmt.get("$rimlightboost").and_then(scalar).unwrap_or(1.0),
            mask: if exponent_texture.is_some() {
                vmt.get("$rimmask").and_then(scalar).unwrap_or(0.0)
            } else {
                0.0
            },
        }),
        exponent_texture,
    })
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
    /// The top-level `Proxies` block: each proxy's lowercased name and its
    /// keys (lowercased, values as written), in the order they run.
    pub proxies: Vec<(String, Vec<(String, String)>)>,
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
                } else if name == "proxies" {
                    vmt.proxies = proxy_blocks(&tokens, i + 1);
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

/// The proxies inside the `Proxies` block opening at `open`: each one's
/// lowercased name and scalar keys, in order.
fn proxy_blocks(tokens: &[String], open: usize) -> Vec<(String, Vec<(String, String)>)> {
    let mut out = Vec::new();
    let mut i = open + 1;
    while i < tokens.len() {
        let token = &tokens[i];
        if token == "}" {
            break;
        }
        if token == "{" {
            i = skip_block(tokens, i);
            continue;
        }
        match tokens.get(i + 1) {
            Some(next) if next == "{" => {
                let (keys, after) = block_keys(tokens, i + 1);
                out.push((token.to_ascii_lowercase(), keys));
                i = after;
            }
            Some(_) => i += 2,
            None => break,
        }
    }
    out
}

/// A material variable a proxy names, `$name` or `$name[k]`: the lowercased
/// name and the component, `None` for the whole variable.
fn proxy_var(value: &str) -> (String, Option<usize>) {
    let value = value.trim().trim_matches('"').trim().to_ascii_lowercase();
    match value.split_once('[') {
        Some((name, rest)) => (
            name.trim().to_string(),
            rest.trim_end_matches(']').trim().parse::<usize>().ok(),
        ),
        None => (value, None),
    }
}

/// `CFloatInput::Init`: a number, or a material variable's (fixed) value;
/// `default` when the key is absent or unreadable.
fn float_input(vmt: &Vmt, keys: &[(String, String)], key: &str, default: f32) -> f32 {
    let Some((_, value)) = keys.iter().rev().find(|(k, _)| k == key) else {
        return default;
    };
    let value = value.trim().trim_matches('"').trim();
    if value.starts_with('$') {
        return vmt.get(&value.to_ascii_lowercase()).and_then(scalar).unwrap_or(default);
    }
    scalar(value).unwrap_or(default)
}

/// A proxy key's raw value, the last one written.
fn proxy_key<'k>(keys: &'k [(String, String)], key: &str) -> Option<&'k str> {
    keys.iter().rev().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
}

/// The camera picture a `_rt_Camera` material draws, or `None` for any other
/// material or a shader other than the three the monitor materials use.
fn camera_of(vmt: &Vmt) -> Option<Camera> {
    let base = vmt.get("$basetexture")?.trim().trim_matches('"').trim();
    if !base.eq_ignore_ascii_case("_rt_camera") {
        return None;
    }
    let shader = match vmt.shader.as_str() {
        "unlitgeneric" => CameraShader::UnlitGeneric,
        "unlittwotexture" => CameraShader::UnlitTwoTexture,
        "monitorscreen" => CameraShader::MonitorScreen,
        _ => return None,
    };
    let texture2 = (shader != CameraShader::UnlitGeneric)
        .then(|| vmt.get("$texture2"))
        .flatten()
        .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
        .filter(|v| !v.is_empty());
    let mut sines: [Option<Sine>; 3] = [None; 3];
    // LinearRamp results by variable and component, for TextureTransform.
    let mut ramps: Vec<(String, Option<usize>, f32, f32)> = Vec::new();
    let mut transform = None;
    for (name, keys) in &vmt.proxies {
        match name.as_str() {
            "sine" => {
                let Some(result) = proxy_key(keys, "resultvar") else { continue };
                let (var, component) = proxy_var(result);
                if var != "$color" {
                    continue;
                }
                let period = float_input(vmt, keys, "sineperiod", 1.0);
                let sine = Sine {
                    // CSineProxy::OnBind: "if (flSinePeriod == 0) flSinePeriod = 1;"
                    period: if period == 0.0 { 1.0 } else { period },
                    min: float_input(vmt, keys, "sinemin", 0.0),
                    max: float_input(vmt, keys, "sinemax", 1.0),
                    offset: float_input(vmt, keys, "timeoffset", 0.0),
                };
                match component {
                    Some(k) if k < 3 => sines[k] = Some(sine),
                    Some(_) => {}
                    // CResultProxy::SetFloatResult: no component sets them all.
                    None => sines = [Some(sine); 3],
                }
            }
            "linearramp" => {
                let Some(result) = proxy_key(keys, "resultvar") else { continue };
                let (var, component) = proxy_var(result);
                ramps.push((
                    var,
                    component,
                    float_input(vmt, keys, "rate", 1.0),
                    float_input(vmt, keys, "initialvalue", 0.0),
                ));
            }
            "texturescroll" => {
                let Some(var) = proxy_key(keys, "texturescrollvar") else { continue };
                if proxy_var(var).0 != "$texture2transform" {
                    continue;
                }
                let rate = float_input(vmt, keys, "texturescrollrate", 1.0);
                let angle = float_input(vmt, keys, "texturescrollangle", 0.0);
                // `curtime * cos( angle * ( M_PI / 180.0f ) ) * rate`, in double.
                let radians = f64::from(angle) * (std::f64::consts::PI / 180.0);
                transform = Some(Texture2Transform {
                    scale: float_input(vmt, keys, "texturescale", 1.0),
                    offset: [0.0, 0.0],
                    velocity: [
                        (radians.cos() * f64::from(rate)) as f32,
                        (radians.sin() * f64::from(rate)) as f32,
                    ],
                });
            }
            "texturetransform" => {
                let Some(result) = proxy_key(keys, "resultvar") else { continue };
                if proxy_var(result).0 != "$texture2transform" {
                    continue;
                }
                // Only a translation is carried; a scale or rotation variable
                // leaves the transform as it was.
                if proxy_key(keys, "scalevar").is_some() || proxy_key(keys, "rotatevar").is_some() {
                    continue;
                }
                let mut offset = [0.0f32; 2];
                let mut velocity = [0.0f32; 2];
                if let Some(translate) = proxy_key(keys, "translatevar") {
                    let (var, _) = proxy_var(translate);
                    let fixed = vmt
                        .get(&var)
                        .map(|v| {
                            let v = v.trim().trim_matches('"').trim();
                            let n: Vec<f32> = v
                                .trim_start_matches('[')
                                .trim_end_matches(']')
                                .split_whitespace()
                                .filter_map(|n| n.parse::<f32>().ok())
                                .filter(|n| n.is_finite())
                                .collect();
                            match n.as_slice() {
                                [x, y, ..] => [*x, *y],
                                [x] => [*x, *x],
                                _ => [0.0, 0.0],
                            }
                        })
                        .unwrap_or([0.0, 0.0]);
                    for axis in 0..2 {
                        let ramp = ramps.iter().rev().find(|(name, component, _, _)| {
                            *name == var && (*component == Some(axis) || component.is_none())
                        });
                        match ramp {
                            Some(&(_, _, rate, initial)) => {
                                offset[axis] = initial;
                                velocity[axis] = rate;
                            }
                            None => offset[axis] = fixed[axis],
                        }
                    }
                }
                transform = Some(Texture2Transform { scale: 1.0, offset, velocity });
            }
            _ => {}
        }
    }
    let monitor = shader == CameraShader::MonitorScreen;
    Some(Camera {
        shader,
        texture2,
        additive: vmt.flag("$additive"),
        color: vmt.get("$color").and_then(vector3).unwrap_or([1.0; 3]),
        color2: vmt
            .get("$color2")
            .or_else(|| vmt.get("srgb?$color2"))
            .and_then(vector3)
            .unwrap_or([1.0; 3]),
        sines,
        transform: transform.filter(|_| shader != CameraShader::UnlitGeneric),
        contrast: if monitor { vmt.get("$contrast").and_then(scalar).unwrap_or(0.0) } else { 0.0 },
        saturation: if monitor { vmt.get("$saturation").and_then(scalar).unwrap_or(1.0) } else { 1.0 },
        tint: if monitor { vmt.get("$tint").and_then(vector3).unwrap_or([1.0; 3]) } else { [1.0; 3] },
    })
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
        let phong = phong_of(&vmt);
        // A phong material's bump map is its normal map and phong mask.
        let bump_map = bump_map.or_else(|| {
            phong.as_ref()?;
            vmt.get("$bumpmap")
                .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
                .filter(|v| !v.is_empty())
        });
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
            ssbump: bump_map.is_some() && lightmapped && vmt.flag("$ssbump"),
            phong,
            camera: camera_of(&vmt),
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

    #[test]
    fn phong_needs_a_bump_map_a_base_alpha_mask_or_a_light_warp() {
        let phong = |extra: &str| {
            phong_of(&parse(&format!(
                r#""VertexLitGeneric" {{ "$basetexture" "a/b" {extra} }}"#
            )))
        };
        assert!(phong(r#""$phong" 1"#).is_none());
        assert!(phong(r#""$bumpmap" "a/b_n""#).is_none());
        assert!(phong(r#""$phong" 1 "$bumpmap" "a/b_n""#).is_some());
        assert!(phong(r#""$phong" 1 "$basemapalphaphongmask" 1"#).is_some());
        assert!(phong(r#""$phong" 1 "$lightwarptexture" "a/ramp""#).is_some());
        let lightmapped = parse(r#""LightmappedGeneric" { "$phong" 1 "$bumpmap" "a/b_n" }"#);
        assert!(phong_of(&lightmapped).is_none());
    }

    #[test]
    fn phong_reads_defaults_and_overrides() {
        let phong = |extra: &str| {
            phong_of(&parse(&format!(
                r#""VertexLitGeneric" {{ "$phong" 1 "$bumpmap" "a/b_n" {extra} }}"#
            )))
            .unwrap()
        };
        let plain = phong("");
        assert_eq!(plain.exponent, 0.0);
        assert_eq!(plain.boost, 1.0);
        assert_eq!(plain.fresnel_ranges, [0.0, 0.5, 1.0]);
        assert_eq!(plain.tint, [1.0; 3]);
        assert!(!plain.albedo_tint && plain.half_lambert && plain.rim.is_none());
        assert_eq!(plain.mask, PhongMask::NormalAlpha);

        let set = phong(
            r#""$phongexponent" 20 "$phongboost" 2.5 "$phongtint" "[1 0.5 0.25]"
            "$phongdisablehalflambert" 1 "$basemapluminancephongmask" 1
            "$basemapalphaphongmask" 1 "$rimlight" 1 "$rimlightexponent" 0.5"#,
        );
        assert_eq!((set.exponent, set.boost), (20.0, 2.5));
        assert_eq!(set.tint, [1.0, 0.5, 0.25]);
        assert!(!set.half_lambert);
        assert_eq!(set.mask, PhongMask::BaseLuminance);
        let rim = set.rim.unwrap();
        assert_eq!((rim.exponent, rim.boost, rim.mask), (1.0, 1.0, 0.0));
    }

    #[test]
    fn phong_tints_by_albedo_only_for_a_zero_tint_with_an_exponent_texture() {
        let phong = |extra: &str| {
            phong_of(&parse(&format!(
                r#""VertexLitGeneric" {{ "$phong" 1 "$bumpmap" "a/b_n" {extra} }}"#
            )))
            .unwrap()
        };
        let texture = r#""$phongexponenttexture" "a/b_exp""#;
        assert!(!phong(texture).albedo_tint, "no $phongtint tints white");
        assert!(phong(&format!(r#"{texture} "$phongtint" "[0 0 0]""#)).albedo_tint);
        assert!(!phong(r#""$phongtint" "[0 0 0]""#).albedo_tint);
        assert_eq!(phong(r#""$phongtint" "[0 0 0]""#).tint, [1.0; 3]);
        let off = phong(&format!(
            r#"{texture} "$phongtint" "[0 0 0]" "$phongalbedotint" 0"#
        ));
        assert!(!off.albedo_tint);
        let rim = phong(&format!(r#"{texture} "$rimlight" 1 "$rimmask" 1"#)).rim.unwrap();
        assert_eq!(rim.mask, 1.0);
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

    #[test]
    fn a_camera_material_carries_its_shader_and_proxies() {
        // dev/dev_combinemonitor_b, as Half-Life 2 ships it, plus a static $color.
        let vmt = parse(
            r#""Unlittwotexture"
            {
                "$basetexture" "_rt_Camera"
                "$additive" "1"
                "$color" "[1 0.5 1]"
                "srgb?$color2" "[2.5 2.5 2.5]"
                "$texture2" "dev/dev_combinescanline"
                "Proxies"
                {
                    "Sine" { "resultVar" "$color[0]" "sineperiod" 1 "sinemin" .2 "sinemax" .3 }
                    "Sine" { "resultVar" "$color[1]" "sineperiod" 0 "sinemin" .3 "sinemax" .4 }
                    "TextureScroll" { "texturescrollvar" "$texture2transform" "texturescrollrate" .06 "texturescrollangle" -90 }
                }
            }"#,
        );
        assert!(vmt.get("sine").is_none(), "proxy keys stay out of the material's");
        let camera = camera_of(&vmt).expect("a camera material");
        assert_eq!(camera.shader, CameraShader::UnlitTwoTexture);
        assert_eq!(camera.texture2.as_deref(), Some("dev/dev_combinescanline"));
        assert!(camera.additive);
        assert_eq!(camera.color, [1.0, 0.5, 1.0]);
        assert_eq!(camera.color2, [2.5; 3]);
        assert_eq!(
            camera.sines[0],
            Some(Sine { period: 1.0, min: 0.2, max: 0.3, offset: 0.0 })
        );
        // A period of 0 is read as 1, as CSineProxy::OnBind does.
        assert_eq!(camera.sines[1].map(|s| s.period), Some(1.0));
        assert!(camera.sines[2].is_none());
        let transform = camera.transform.expect("scrolls");
        assert_eq!(transform.scale, 1.0);
        assert!(transform.velocity[0].abs() < 1e-8);
        assert!((transform.velocity[1] + 0.06).abs() < 1e-7);
    }

    #[test]
    fn a_ramped_texture_transform_scrolls_by_the_ramp() {
        // dev/dev_tvmonitor1a's interlace chain: LinearRamp into $tex2offset[1],
        // TextureTransform translating $texture2transform by it.
        let vmt = parse(
            r#""UnlitTwoTexture"
            {
                "$basetexture" "_rt_Camera"
                "$texture2" "dev/dev_scanline"
                "$tex2offset" "[0.25 0]"
                "Proxies"
                {
                    "Sine" { "resultVar" "$color" "sineperiod" 2 "sinemin" .9 "sinemax" 1 }
                    "LinearRamp" { "resultVar" "$tex2offset[1]" "rate" -.91 }
                    "TextureTransform" { "translateVar" "$tex2offset" "resultVar" "$texture2transform" }
                }
            }"#,
        );
        let camera = camera_of(&vmt).expect("a camera material");
        assert!(!camera.additive);
        // A whole-vector result writes every channel.
        assert!(camera.sines.iter().all(|s| s.is_some_and(|s| s.period == 2.0)));
        let transform = camera.transform.expect("scrolls");
        assert_eq!(transform.offset, [0.25, 0.0]);
        assert_eq!(transform.velocity, [0.0, -0.91]);
    }

    #[test]
    fn only_render_target_materials_are_camera_materials() {
        assert!(camera_of(&parse(r#""UnlitGeneric" { "$basetexture" "dev/dev_monitor" }"#)).is_none());
        let plain = camera_of(&parse(r#""UnlitGeneric" { "$basetexture" "_RT_CAMERA" "$texture2" "x" }"#))
            .expect("matched ignoring case");
        assert_eq!(plain.shader, CameraShader::UnlitGeneric);
        assert!(plain.texture2.is_none(), "UnlitGeneric has no second texture");
        let monitor = camera_of(&parse(
            r#""MonitorScreen" { "$basetexture" "_rt_Camera" "$saturation" "0" "$tint" "[.8 .9 1]" }"#,
        ))
        .unwrap();
        assert_eq!(monitor.shader, CameraShader::MonitorScreen);
        assert_eq!((monitor.contrast, monitor.saturation, monitor.tint), (0.0, 0.0, [0.8, 0.9, 1.0]));
        assert!(camera_of(&parse(r#""LightmappedGeneric" { "$basetexture" "_rt_Camera" }"#)).is_none());
    }

    #[test]
    fn resolves_real_half_life_2_monitor_materials() {
        let Some((vfs, _)) = real_materials() else {
            return;
        };
        let materials = Materials::new(&vfs, None);
        let combine = materials.assets("dev/dev_combinemonitor_3", None).unwrap();
        let camera = combine.camera.expect("dev_combinemonitor_3 shows the camera");
        assert_eq!(camera.shader, CameraShader::UnlitTwoTexture);
        assert_eq!(camera.texture2.as_deref(), Some("dev/dev_combinescanline"));
        assert!(camera.additive);
        assert_eq!(camera.color2, [2.5; 3]);
        assert!(camera.sines.iter().all(|s| s.is_some_and(|s| s.min == 0.4 && s.max == 0.41)));
        let tv = materials.assets("dev/dev_tvmonitor2a", None).unwrap().camera.unwrap();
        assert_eq!(tv.shader, CameraShader::MonitorScreen);
        assert_eq!(tv.texture2.as_deref(), Some("Effects/security_noise2"));
        assert_eq!(tv.tint, [0.8, 0.9, 1.0]);
        let plain = materials.assets("dev/dev_tvmonitornonoise", None).unwrap().camera.unwrap();
        assert_eq!(plain.shader, CameraShader::UnlitGeneric);
        assert!(materials.assets("concrete/concretewall001a", None).unwrap().camera.is_none());
    }
}
