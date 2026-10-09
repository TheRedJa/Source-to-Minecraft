//! What Source's view code needs beyond the map's surfaces to look as the
//! game does (format section 24): whether the map has HDR light, which turns
//! on Source's auto exposure and bloom, how the game's own post-processing
//! materials set up bloom and the vignette, and the colour lookups its
//! `color_correction` entities name.
//!
//! Two branches of Source draw bloom differently. The 2013 engine (Half-Life 2,
//! Portal) shapes each of the four downsample taps and blurs with a fixed
//! thirteen-tap cross; the Alien Swarm branch (Portal 2, INFRA) shapes their
//! average by the material's `$bloomtype` and blurs with the Gaussian its
//! `$kernel` picks. Which one a game is shows in its `dev/downsample_non_hdr`
//! material: only the later shader has `$bloomtype`. That branch's
//! `engine_post` also darkens the screen's edges by `dev/vignette` where the
//! material enables it without a console variable gating it (Portal 2; INFRA
//! gates it behind `mat_vignette_enable`, off by default).
//!
//! A lookup is a `.raw` file under `scripts/colorcorrection/`: 32 × 32 × 32
//! RGB bytes, red fastest, then green, then blue, the colour each input maps
//! to. Source loads it by the name the entity's `filename` keyvalue gives, so
//! the table keeps that name, lowercased with forward slashes, as the key the
//! mod looks it up by.

use crate::bsp::Map;
use crate::output::metadata::{Diagnostic, Severity};
use crate::source::keyvalues::{self, Block, Value};
use crate::source::vfs::{Vfs, read_file};
use std::collections::BTreeMap;

/// Edge of a colour lookup's cube.
pub const LOOKUP_SIZE: usize = 32;
/// Bytes of one lookup: the cube's cells, three each.
pub const LOOKUP_BYTES: usize = LOOKUP_SIZE * LOOKUP_SIZE * LOOKUP_SIZE * 3;

const MAGIC: &[u8; 8] = b"S2LOOK\0\0";
const VERSION: u32 = 1;
/// Flag bit: the map carries HDR light.
const FLAG_HDR: u8 = 1;
/// Flag bit: the game's bloom is the Alien Swarm branch's.
const FLAG_LATER_BLOOM: u8 = 2;
/// Flag bit: a vignette texture follows.
const FLAG_VIGNETTE: u8 = 4;
/// Largest Gaussian `BlurFilter` knows.
const MAX_KERNEL: u8 = 4;

/// Entities that name a lookup through `filename`.
const LOOKUP_CLASSES: &[&str] = &["color_correction", "color_correction_volume"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LookTable {
    pub hdr: bool,
    pub post: Post,
    /// Lookups by normalized name.
    pub lookups: BTreeMap<String, Vec<u8>>,
}

/// How the game's post-processing materials set its bloom and vignette up.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Post {
    /// `$bloomtype` of `dev/downsample_non_hdr`; None for the 2013 shader, which has none.
    pub bloom_type: Option<u8>,
    /// `$kernel` of `dev/blurfilterx_nohdr` and `dev/blurfiltery_nohdr`: 0 is the cross.
    pub kernel_x: u8,
    pub kernel_y: u8,
    /// The red channel of `engine_post`'s vignette texture where it is on: width, height, texels.
    pub vignette: Option<(u32, u32, Vec<u8>)>,
}

/// The name a lookup is keyed by: as the entity wrote it, lowercased, with
/// forward slashes and no surrounding blanks.
pub fn lookup_name(filename: &str) -> String {
    filename.trim().to_ascii_lowercase().replace('\\', "/")
}

/// Reads the map's HDR flag and every lookup its entities name.
pub fn collect(map: &Map, vfs: &Vfs) -> (LookTable, Vec<Diagnostic>) {
    let mut names = Vec::new();
    for entity in map.bsp.entities.iter() {
        let mut class = None;
        let mut file = None;
        for (key, value) in entity.properties() {
            if key.eq_ignore_ascii_case("classname") {
                class = Some(value.to_ascii_lowercase());
            } else if key.eq_ignore_ascii_case("filename") {
                // Repeated keys: the last one is the entity's, as Source applies them in order.
                file = Some(value.to_string());
            }
        }
        let (Some(class), Some(file)) = (class, file) else {
            continue;
        };
        if LOOKUP_CLASSES.contains(&class.as_str()) {
            let name = lookup_name(&file);
            if !name.is_empty() {
                names.push((class, name));
            }
        }
    }
    let read = |path: &str| read_file(vfs, Some(&map.bsp.pack), path);
    let (mut table, mut diagnostics) = collect_named(names, map.light.hdr, read);
    let (post, post_diagnostics) = post(read);
    table.post = post;
    diagnostics.extend(post_diagnostics);
    (table, diagnostics)
}

/// The first block of a material file: the shader's parameters.
fn material(read: impl Fn(&str) -> Option<Vec<u8>>, name: &str) -> Option<Block> {
    let bytes = read(&format!("materials/{name}.vmt"))?;
    keyvalues::parse(&String::from_utf8_lossy(&bytes))
        .into_iter()
        .find_map(|(_, value)| match value {
            Value::Block(block) => Some(block),
            Value::Text(_) => None,
        })
}

/// A parameter read as the material system reads an int: any number, truncated; 0 when absent.
fn int(block: &Block, key: &str) -> i64 {
    keyvalues::text(block, key)
        .and_then(|v| v.trim().trim_matches('"').parse::<f64>().ok())
        .map_or(0, |v| v as i64)
}

/// Whether a `ConVar` proxy of the material writes `var`.
fn gated(block: &Block, var: &str) -> bool {
    let Some(Value::Block(proxies)) = keyvalues::get(block, "proxies") else {
        return false;
    };
    proxies.iter().any(|(name, proxy)| {
        name.eq_ignore_ascii_case("convar")
            && matches!(proxy, Value::Block(p)
                if keyvalues::text(p, "resultvar").is_some_and(|r| r.eq_ignore_ascii_case(var)))
    })
}

/// The bloom and vignette setup of the game's post-processing materials.
fn post(read: impl Fn(&str) -> Option<Vec<u8>>) -> (Post, Vec<Diagnostic>) {
    let mut post = Post::default();
    let mut diagnostics = Vec::new();
    if let Some(downsample) = material(&read, "dev/downsample_non_hdr")
        && keyvalues::get(&downsample, "$bloomtype").is_some()
    {
        post.bloom_type = Some(int(&downsample, "$bloomtype").clamp(0, 1) as u8);
    }
    let kernel = |name: &str| {
        material(&read, name).map_or(0, |m| int(&m, "$kernel").clamp(0, i64::from(MAX_KERNEL)) as u8)
    };
    post.kernel_x = kernel("dev/blurfilterx_nohdr");
    post.kernel_y = kernel("dev/blurfiltery_nohdr");
    if post.bloom_type.is_some()
        && let Some(engine_post) = material(&read, "dev/engine_post")
    {
        // Engine_Post's defaults: vignette allowed, not enabled.
        let allowed = keyvalues::get(&engine_post, "$allowvignette").is_none()
            || int(&engine_post, "$allowvignette") != 0;
        // A console variable gating it is mat_vignette_enable, which is off by default.
        let enabled = int(&engine_post, "$vignetteenable") != 0 && !gated(&engine_post, "$vignetteenable");
        if allowed && enabled {
            let texture = keyvalues::text(&engine_post, "$internal_vignettetexture")
                .unwrap_or("dev/vignette")
                .trim_matches('"')
                .to_ascii_lowercase();
            match read(&format!("materials/{texture}.vtf")).and_then(|bytes| {
                // The header's width and height, so the texture keeps its own size.
                let size = |at: usize| bytes.get(at..at + 2).map(|b| u32::from(u16::from_le_bytes([b[0], b[1]])));
                let (width, height) = (size(16)?, size(18)?);
                crate::source::vtf::decode_resized(&bytes, [width, height], false).ok()
            }) {
                Some(image) => {
                    let red = image.pixels().map(|p| p.0[0]).collect();
                    post.vignette = Some((image.width(), image.height(), red));
                }
                None => diagnostics.push(diagnostic(
                    "LOOK_VIGNETTE_MISSING",
                    "the game's vignette texture could not be read; the screen's edges stay bright",
                    &[("texture", &texture)],
                )),
            }
        }
    }
    (post, diagnostics)
}

/// [`collect`] after the entity scan, with the file lookup passed in.
fn collect_named(
    names: Vec<(String, String)>,
    hdr: bool,
    read: impl Fn(&str) -> Option<Vec<u8>>,
) -> (LookTable, Vec<Diagnostic>) {
    let mut lookups = BTreeMap::new();
    let mut diagnostics = Vec::new();
    for (class, name) in names {
        if lookups.contains_key(&name) {
            continue;
        }
        match read(&name) {
            Some(bytes) if bytes.len() == LOOKUP_BYTES => {
                lookups.insert(name, bytes);
            }
            Some(bytes) => diagnostics.push(diagnostic(
                "LOOK_LOOKUP_INVALID",
                "colour correction lookup is not 32x32x32 RGB; the entity corrects nothing",
                &[
                    ("classname", &class),
                    ("file", &name),
                    ("bytes", &bytes.len().to_string()),
                ],
            )),
            None => diagnostics.push(diagnostic(
                "LOOK_LOOKUP_MISSING",
                "colour correction lookup not found; the entity corrects nothing",
                &[("classname", &class), ("file", &name)],
            )),
        }
    }
    (LookTable { hdr, post: Post::default(), lookups }, diagnostics)
}

fn diagnostic(code: &str, message: &str, context: &[(&str, &str)]) -> Diagnostic {
    Diagnostic {
        severity: Severity::Warning,
        code: code.to_string(),
        message: message.to_string(),
        context: context
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect(),
    }
}

impl LookTable {
    /// Little-endian: magic, `u32` version, `u8` flags, `u8` bloom type,
    /// `u8` x and y blur kernels, with the vignette flag a `u32` width,
    /// `u32` height and that many red texels, then a `u32` lookup count and
    /// per lookup in name order a `u32` name length, the UTF-8 name and its
    /// [`LOOKUP_BYTES`].
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(20 + self.lookups.len() * (LOOKUP_BYTES + 64));
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&VERSION.to_le_bytes());
        let mut flags = 0;
        if self.hdr {
            flags |= FLAG_HDR;
        }
        if self.post.bloom_type.is_some() {
            flags |= FLAG_LATER_BLOOM;
        }
        if self.post.vignette.is_some() {
            flags |= FLAG_VIGNETTE;
        }
        out.push(flags);
        out.push(self.post.bloom_type.unwrap_or(0));
        out.push(self.post.kernel_x);
        out.push(self.post.kernel_y);
        if let Some((width, height, red)) = &self.post.vignette {
            out.extend_from_slice(&width.to_le_bytes());
            out.extend_from_slice(&height.to_le_bytes());
            out.extend_from_slice(red);
        }
        out.extend_from_slice(&(self.lookups.len() as u32).to_le_bytes());
        for (name, bytes) in &self.lookups {
            out.extend_from_slice(&(name.len() as u32).to_le_bytes());
            out.extend_from_slice(name.as_bytes());
            out.extend_from_slice(bytes);
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_are_matched_as_source_opens_files() {
        assert_eq!(
            lookup_name(" Scripts\\ColorCorrection\\CC_Furnace_Hall.raw "),
            "scripts/colorcorrection/cc_furnace_hall.raw"
        );
    }

    #[test]
    fn a_lookup_named_twice_is_kept_once_and_bad_ones_are_reported() {
        let good = vec![7u8; LOOKUP_BYTES];
        let names = vec![
            ("color_correction".to_string(), "a.raw".to_string()),
            ("color_correction".to_string(), "a.raw".to_string()),
            ("color_correction".to_string(), "short.raw".to_string()),
            ("color_correction_volume".to_string(), "gone.raw".to_string()),
        ];
        let (table, diagnostics) = collect_named(names, true, |path| match path {
            "a.raw" => Some(good.clone()),
            "short.raw" => Some(vec![0; 12]),
            _ => None,
        });
        assert!(table.hdr);
        assert_eq!(table.lookups.keys().collect::<Vec<_>>(), ["a.raw"]);
        let codes: Vec<_> = diagnostics.iter().map(|d| d.code.as_str()).collect();
        assert_eq!(codes, ["LOOK_LOOKUP_INVALID", "LOOK_LOOKUP_MISSING"]);
    }

    #[test]
    fn the_encoding_carries_flag_names_and_cubes() {
        let mut lookups = BTreeMap::new();
        lookups.insert("b".to_string(), vec![2u8; LOOKUP_BYTES]);
        lookups.insert("a".to_string(), vec![1u8; LOOKUP_BYTES]);
        let post = Post { bloom_type: Some(0), kernel_x: 4, kernel_y: 4, vignette: Some((2, 1, vec![9, 8])) };
        let bytes = LookTable { hdr: true, post, lookups }.encode();
        assert_eq!(&bytes[..8], MAGIC);
        assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 1);
        assert_eq!(bytes[12], FLAG_HDR | FLAG_LATER_BLOOM | FLAG_VIGNETTE);
        assert_eq!(&bytes[13..16], [0, 4, 4]);
        assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 2);
        assert_eq!(u32::from_le_bytes(bytes[20..24].try_into().unwrap()), 1);
        assert_eq!(&bytes[24..26], [9, 8]);
        assert_eq!(u32::from_le_bytes(bytes[26..30].try_into().unwrap()), 2);
        assert_eq!(u32::from_le_bytes(bytes[30..34].try_into().unwrap()), 1);
        assert_eq!(bytes[34], b'a');
        assert_eq!(bytes[35], 1);
        assert_eq!(bytes.len(), 30 + 2 * (4 + 1 + LOOKUP_BYTES));
    }

    fn files(entries: &[(&str, &str)]) -> impl Fn(&str) -> Option<Vec<u8>> {
        let entries: Vec<(String, Vec<u8>)> = entries.iter().map(|(k, v)| (k.to_string(), v.as_bytes().to_vec())).collect();
        move |path| entries.iter().find(|(k, _)| k == path).map(|(_, v)| v.clone())
    }

    #[test]
    fn the_2013_downsample_has_no_bloom_type() {
        let (post, diagnostics) = post(files(&[(
            "materials/dev/downsample_non_hdr.vmt",
            "Downsample_nohdr { $basetexture _rt_FullFrameFB }",
        )]));
        assert_eq!(post, Post::default());
        assert!(diagnostics.is_empty());
    }

    #[test]
    fn a_vignette_a_console_variable_gates_stays_off() {
        let (post, _) = post(files(&[
            ("materials/dev/downsample_non_hdr.vmt", "Downsample_nohdr { $bloomtype 0 }"),
            ("materials/dev/blurfilterx_nohdr.vmt", "BlurFilterX { $kernel 4 }"),
            ("materials/dev/blurfiltery_nohdr.vmt", "BlurFilterY { $kernel 4 }"),
            (
                "materials/dev/engine_post.vmt",
                "engine_post { $AllowVignette 1 $vignetteEnable 1 Proxies { ConVar { resultVar $vignetteEnable convar mat_vignette_enable } } }",
            ),
        ]));
        assert_eq!(post, Post { bloom_type: Some(0), kernel_x: 4, kernel_y: 4, vignette: None });
    }

    #[test]
    fn an_ungated_vignette_without_its_texture_is_reported() {
        let (post, diagnostics) = post(files(&[
            ("materials/dev/downsample_non_hdr.vmt", "Downsample_nohdr { $bloomtype 0 }"),
            ("materials/dev/engine_post.vmt", "engine_post { $AllowVignette 1 $vignetteEnable 1 }"),
        ]));
        assert_eq!(post.vignette, None);
        assert_eq!(diagnostics[0].code, "LOOK_VIGNETTE_MISSING");
    }
}
