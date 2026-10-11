//! A map's `vgui_screen` panels (format section 26): everything the mod needs
//! to draw and drive them, as data. The entities themselves reach the mod
//! through the logic table; this adds what their `panelname` resolves to.
//!
//! `panelname` names a block in `scripts/vgui_screens.txt`, whose `resfile` is
//! a VGUI layout (`scripts/screens/*.res`), styled by the `resource/*scheme.res`
//! files and drawing materials and localized strings. Trees keep the files'
//! own key names and order, so another game's screens need nothing here.

use crate::bsp::entities::EntityRecord;
use crate::output::bundle;
use crate::output::metadata::{Diagnostic, Severity};
use crate::source::keyvalues::{self, Block, Value};
use crate::source::kvfile;
use crate::source::vfs::{Vfs, read_file};
use anyhow::Result;
use serde::Serialize;
use serde_json::Value as Json;
use std::collections::{BTreeMap, BTreeSet};

const SCREENS_FILE: &str = "scripts/vgui_screens.txt";

/// Sounds INFRA's closed client code plays for its menu. These names come
/// from that code, not from any data file: `ui/menu_*.wav` for focus, accept,
/// back and invalid, and `music/menu.wav` behind the main menu (a `cue ` loop
/// from sample 0).
const INFRA_MENU_SOUNDS: &[&str] = &[
    "ui/menu_accept.wav",
    "ui/menu_back.wav",
    "ui/menu_focus.wav",
    "ui/menu_invalid.wav",
    "music/menu.wav",
];

/// Localization token prefixes INFRA's closed code reads without naming them
/// in a layout.
const INFRA_TOKEN_PREFIXES: &[&str] = &["infra_menu_", "infra_chapter_", "infra_part_"];

#[derive(Debug, Clone, Serialize)]
pub struct ScreensTable {
    pub format: &'static str,
    pub version: u32,
    pub panels: BTreeMap<String, Json>,
    pub layouts: BTreeMap<String, Json>,
    pub schemes: BTreeMap<String, Json>,
    pub images: Vec<Image>,
    pub sounds: Vec<Sound>,
    pub strings: BTreeMap<String, String>,
}

impl ScreensTable {
    pub fn encode(&self) -> Result<Vec<u8>> {
        bundle::canonical_json(self)
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Image {
    /// Lowercase material name without `materials/` and `.vmt`.
    pub material: String,
    pub path: String,
    pub width: u32,
    pub height: u32,
    pub translucent: bool,
    pub additive: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct Sound {
    /// Lowercase path below `sound/`.
    pub wave: String,
    pub path: String,
    #[serde(rename = "loop")]
    pub looping: bool,
    /// Seconds into the sound the loop restarts at; absent without a loop.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub loop_start: Option<f64>,
}

pub struct ScreensExport {
    pub table: ScreensTable,
    /// Bundle entries (path, bytes) the table's `path` fields name.
    pub assets: Vec<(String, Vec<u8>)>,
    pub diagnostics: Vec<Diagnostic>,
}

/// A block as a TREE: `[key, value]` pairs in file order, a value being a
/// string or a nested TREE.
pub fn tree(block: &Block) -> Json {
    Json::Array(
        block
            .iter()
            .map(|(key, value)| {
                Json::Array(vec![
                    Json::String(key.clone()),
                    match value {
                        Value::Text(text) => Json::String(text.clone()),
                        Value::Block(inner) => tree(inner),
                    },
                ])
            })
            .collect(),
    )
}

/// The screens of a map; `None` when it has no `vgui_screen` whose
/// `panelname` resolves.
pub fn collect(
    entities: &[EntityRecord],
    vfs: &Vfs,
    pak: Option<&vbsp::Packfile>,
    map_id: &str,
) -> Option<ScreensExport> {
    let mut diagnostics = Vec::new();
    let names: BTreeSet<String> = entities
        .iter()
        .filter(|e| e.classname.eq_ignore_ascii_case("vgui_screen"))
        .filter_map(|e| {
            e.keyvalues
                .iter()
                .find(|(k, _)| k.eq_ignore_ascii_case("panelname"))
        })
        .map(|(_, v)| v.trim().to_ascii_lowercase())
        .collect();
    if names.is_empty() {
        return None;
    }
    let screens = kvfile::load(vfs, SCREENS_FILE)?;
    // The panels sit in a `VGUI_Screens` block.
    let screens = match keyvalues::get(&screens, "VGUI_Screens") {
        Some(Value::Block(inner)) => inner,
        _ => &screens,
    };
    let mut panels = BTreeMap::new();
    let mut resfiles = BTreeSet::new();
    let mut menu = false;
    for name in &names {
        let found = screens.iter().find_map(|(k, v)| match v {
            Value::Block(block) if k.eq_ignore_ascii_case(name) => Some(block),
            _ => None,
        });
        let Some(panel) = found else {
            diagnostics.push(diagnostic(
                "SCREENS_PANEL_MISSING",
                "a vgui_screen names a panel that scripts/vgui_screens.txt does not define",
                &[("panel", name)],
            ));
            continue;
        };
        if keyvalues::text(panel, "type")
            .is_some_and(|t| t.to_ascii_lowercase().starts_with("infra_menu"))
        {
            menu = true;
        }
        if let Some(resfile) = keyvalues::text(panel, "resfile") {
            resfiles.insert(path_key(resfile));
        }
        panels.insert(name.clone(), tree(panel));
    }
    if panels.is_empty() {
        return None;
    }

    // INFRA's closed code switches between layouts by name (`play` loads
    // infra_menu_main_game.res), so a menu map carries them all.
    if menu {
        for file in vfs.loose_files("scripts/screens/", ".res") {
            if !file.starts_with("scripts/screens/bases/") {
                resfiles.insert(file);
            }
        }
    }
    let mut layouts = BTreeMap::new();
    let mut layout_blocks = Vec::new();
    for file in &resfiles {
        match kvfile::load(vfs, file) {
            Some(block) => {
                layouts.insert(file.clone(), tree(&block));
                layout_blocks.push(block);
            }
            None => diagnostics.push(diagnostic(
                "SCREENS_LAYOUT_MISSING",
                "a panel's layout file was not found",
                &[("file", file)],
            )),
        }
    }
    let mut schemes = BTreeMap::new();
    for file in vfs.loose_files("resource/", "scheme.res") {
        if let Some(block) = kvfile::load(vfs, &file) {
            schemes.insert(file, tree(&block));
        }
    }

    let mut assets = Vec::new();
    let mut images = Vec::new();
    let mut materials = BTreeSet::new();
    let mut tokens = BTreeSet::new();
    for block in &layout_blocks {
        references(block, &mut materials, &mut tokens);
    }
    for material in materials {
        match image(vfs, pak, &material) {
            Ok((png, width, height, translucent, additive)) => {
                let path = format!("maps/{map_id}/screens/images/{}.png", images.len());
                assets.push((path.clone(), png));
                images.push(Image {
                    material,
                    path,
                    width,
                    height,
                    translucent,
                    additive,
                });
            }
            Err(reason) => diagnostics.push(diagnostic(
                "SCREENS_IMAGE_MISSING",
                "a screen's material could not be read",
                &[("material", &material), ("reason", &reason)],
            )),
        }
    }

    let mut sounds = Vec::new();
    if menu {
        for wave in INFRA_MENU_SOUNDS {
            match sound(vfs, pak, wave) {
                Ok((ogg, looping, loop_start)) => {
                    let path = format!("maps/{map_id}/screens/sounds/{}.ogg", sounds.len());
                    assets.push((path.clone(), ogg));
                    sounds.push(Sound {
                        wave: wave.to_string(),
                        path,
                        looping,
                        loop_start,
                    });
                }
                Err(reason) => diagnostics.push(diagnostic(
                    "SCREENS_SOUND_MISSING",
                    "a menu sound could not be read",
                    &[("wave", wave), ("reason", &reason)],
                )),
            }
        }
    }

    let known = crate::output::logic::load_strings(vfs, pak);
    let mut strings = BTreeMap::new();
    for (token, text) in &known {
        let wanted = tokens.contains(token)
            || (menu && INFRA_TOKEN_PREFIXES.iter().any(|p| token.starts_with(p)));
        if wanted {
            strings.insert(token.clone(), text.clone());
        }
    }

    Some(ScreensExport {
        table: ScreensTable {
            format: "src2mc-screens",
            version: 1,
            panels,
            layouts,
            schemes,
            images,
            sounds,
            strings,
        },
        assets,
        diagnostics,
    })
}

/// The materials and `#` localization tokens a layout names: `material` on
/// any control, `image` (below `materials/vgui/`) on an `ImagePanel`.
fn references(block: &Block, materials: &mut BTreeSet<String>, tokens: &mut BTreeSet<String>) {
    let image_panel =
        keyvalues::text(block, "ControlName").is_some_and(|c| c.eq_ignore_ascii_case("ImagePanel"));
    for (key, value) in block {
        match value {
            Value::Block(inner) => references(inner, materials, tokens),
            Value::Text(text) => {
                if key.eq_ignore_ascii_case("material") && !text.trim().is_empty() {
                    materials.insert(crate::output::particles::normalize_material(text));
                } else if key.eq_ignore_ascii_case("image")
                    && image_panel
                    && !text.trim().is_empty()
                {
                    materials.insert(crate::output::particles::normalize_material(&format!(
                        "vgui/{text}"
                    )));
                }
                if let Some(token) = text.trim().strip_prefix('#')
                    && !token.is_empty()
                {
                    tokens.insert(token.to_ascii_lowercase());
                }
            }
        }
    }
}

/// A material's top mip as PNG, its size and its blend flags.
fn image(
    vfs: &Vfs,
    pak: Option<&vbsp::Packfile>,
    material: &str,
) -> std::result::Result<(Vec<u8>, u32, u32, bool, bool), String> {
    let resolver = crate::source::vmt::Materials::new(vfs, pak);
    let vmt = resolver.vmt(material).ok_or("no material")?;
    let flag = |key: &str| {
        vmt.get(key)
            .map(|v| v.trim().trim_matches('"'))
            .is_some_and(|v| !v.is_empty() && v != "0")
    };
    let base = vmt.get("$basetexture").ok_or("no $basetexture")?;
    let base = base.trim().trim_matches('"').replace('\\', "/");
    let path = format!("materials/{}.vtf", base.trim_end_matches(".vtf"));
    let data = read_file(vfs, pak, &path).ok_or("no texture file")?;
    let vtf = vtf::from_bytes(&data).map_err(|e| e.to_string())?;
    let rgba = match vtf.highres_image.decode(0) {
        Ok(decoded) => decoded.to_rgba8(),
        // The decoder has no greyscale formats, which VGUI art such as the
        // INFRA logo (IA88) is stored in.
        Err(error) => {
            let image = &vtf.highres_image;
            let frame = image.get_frame(0).map_err(|e| e.to_string())?;
            grey_to_rgba(
                image.format,
                u32::from(image.width),
                u32::from(image.height),
                frame,
            )
            .ok_or_else(|| error.to_string())?
        }
    };
    let (width, height) = (rgba.width(), rgba.height());
    if width == 0 || height == 0 {
        return Err("texture has no pixels".into());
    }
    let png = crate::source::vtf::to_png(&rgba).map_err(|e| format!("{e:#}"))?;
    Ok((png, width, height, flag("$translucent"), flag("$additive")))
}

/// Greyscale VTF formats as RGBA: intensity in all three colour channels.
fn grey_to_rgba(
    format: vtf::ImageFormat,
    width: u32,
    height: u32,
    frame: &[u8],
) -> Option<image::RgbaImage> {
    let pixels: Vec<u8> = match format {
        vtf::ImageFormat::Ia88 => frame
            .as_chunks::<2>()
            .0
            .iter()
            .flat_map(|&[i, a]| [i, i, i, a])
            .collect(),
        vtf::ImageFormat::I8 => frame.iter().flat_map(|&i| [i, i, i, 255]).collect(),
        vtf::ImageFormat::A8 => frame.iter().flat_map(|&a| [255, 255, 255, a]).collect(),
        _ => return None,
    };
    image::RgbaImage::from_raw(width, height, pixels)
}

/// A sound as Ogg, and the loop it carries.
fn sound(
    vfs: &Vfs,
    pak: Option<&vbsp::Packfile>,
    wave: &str,
) -> std::result::Result<(Vec<u8>, bool, Option<f64>), String> {
    let bytes = crate::source::sound::read_sound(vfs, pak, wave).ok_or("not found")?;
    let mut wav = crate::source::wav::decode(&bytes).map_err(|e| format!("{e:#}"))?;
    if wav.channels.len() > 2 {
        wav = wav.downmixed();
    }
    let ogg = crate::source::wav::encode_ogg(&wav).map_err(|e| format!("{e:#}"))?;
    let loop_start = wav
        .loop_start
        .map(|frame| frame as f64 / f64::from(wav.sample_rate.max(1)));
    Ok((ogg, loop_start.is_some(), loop_start))
}

fn path_key(path: &str) -> String {
    path.trim().to_ascii_lowercase().replace('\\', "/")
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_tree_keeps_pair_order_and_duplicates() {
        let block: Block = vec![
            ("b".into(), Value::Text("1".into())),
            (
                "a".into(),
                Value::Block(vec![("x".into(), Value::Text("2".into()))]),
            ),
            ("b".into(), Value::Text("3".into())),
        ];
        assert_eq!(
            tree(&block).to_string(),
            r#"[["b","1"],["a",[["x","2"]]],["b","3"]]"#
        );
    }

    #[test]
    fn layouts_name_materials_images_and_tokens() {
        let block = kvfile::parse_with(
            r##"
            "f.res" {
                "logo" { "ControlName" "MaterialImage" "material" "VGUI/Logo" }
                "pic" { "ControlName" "ImagePanel" "image" "Foo\Bar" }
                "other" { "ControlName" "Button" "image" "ignored" "labelText" "#GameUI_Back" }
            }
            "##,
            &mut |_| None,
        );
        let (mut materials, mut tokens) = (BTreeSet::new(), BTreeSet::new());
        references(&block, &mut materials, &mut tokens);
        assert_eq!(
            materials.into_iter().collect::<Vec<_>>(),
            ["vgui/foo/bar", "vgui/logo"]
        );
        assert_eq!(tokens.into_iter().collect::<Vec<_>>(), ["gameui_back"]);
    }
}
