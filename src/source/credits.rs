//! `scripts/credits.txt`, the roll `env_credits` plays, and its logo.
//!
//! The client (`game/client/hl2/hud_credits.cpp`, SDK 2013) loads
//! `scripts/credits.txt` with `KeyValues::LoadFromFile`, which makes the
//! file's single top-level key (`"credits.txt"` in Half-Life 2) the root, and
//! looks up `IntroCreditsNames` or `OutroCreditsNames` (`PrepareCredits`) and
//! `CreditsParams` (`ReadParams`) below it. In the names sections each pair is
//! a name and the scheme font it is drawn in (`ReadNames`); the same name may
//! repeat (Half-Life 2 uses `" "` as spacing), so order and duplicates are
//! kept.
//!
//! The logo is the `logo` parameter drawn centered in the scheme font
//! `WeaponIcons` (`DrawLogo`; the `ClientTitleFont` branch is for episodic
//! games), which `resource/ClientScheme.res` defines as the glyph font
//! `HalfLife2` (file `resource/HALFLIFE2.ttf`, listed in `CustomFontFiles`) at
//! a `tall`. [`render_logo`] draws it to a PNG.

use crate::source::keyvalues::{Block, Value};
use crate::source::vfs::Vfs;
use serde::Serialize;
use std::collections::BTreeMap;

/// The logo string when `CreditsParams` has none (`ReadParams`).
const DEFAULT_LOGO: &str = "HALF-LIFE'";
/// The scheme font the logo is drawn in, `hud_credits.cpp` `DrawLogo` for a
/// non-episodic, non-Xbox game.
const LOGO_FONT: &str = "WeaponIcons";
/// The largest logo image: a scheme asking for more is read as garbage.
const MAX_LOGO_PIXELS: u32 = 4096;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct CreditName {
    pub name: String,
    /// A scheme font name such as `CreditsText`.
    pub font: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Credits {
    /// `CreditsParams` with lowercase keys and values as written.
    pub params: BTreeMap<String, String>,
    pub intro: Vec<CreditName>,
    pub outro: Vec<CreditName>,
    /// The map-relative bundle path of the logo PNG; absent when the logo
    /// could not be drawn.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub logo_image: Option<String>,
}

/// Parse the file's tree. `None` when it holds none of the three sections.
pub fn parse(file: &Block) -> Option<Credits> {
    let has = |block: &Block| {
        ["CreditsParams", "IntroCreditsNames", "OutroCreditsNames"]
            .iter()
            .any(|key| {
                matches!(
                    crate::source::keyvalues::get(block, key),
                    Some(Value::Block(_))
                )
            })
    };
    let root = file
        .iter()
        .find_map(|(_, value)| match value {
            Value::Block(block) if has(block) => Some(block),
            _ => None,
        })
        .or_else(|| has(file).then_some(file))?;
    let section = |key: &str| match crate::source::keyvalues::get(root, key) {
        Some(Value::Block(block)) => block.as_slice(),
        _ => &[],
    };
    let mut params = BTreeMap::new();
    for (key, value) in section("CreditsParams") {
        if let Value::Text(value) = value {
            // The engine's lookups find the first of a repeated key.
            params
                .entry(key.to_ascii_lowercase())
                .or_insert_with(|| value.clone());
        }
    }
    let names = |key: &str| -> Vec<CreditName> {
        section(key)
            .iter()
            .filter_map(|(name, font)| match font {
                Value::Text(font) => Some(CreditName {
                    name: name.clone(),
                    font: font.clone(),
                }),
                Value::Block(_) => None,
            })
            .collect()
    };
    Some(Credits {
        params,
        intro: names("IntroCreditsNames"),
        outro: names("OutroCreditsNames"),
        logo_image: None,
    })
}

/// The game's `scripts/credits.txt`, conditionals evaluated; `None` without it.
pub fn load(vfs: &Vfs) -> Option<Credits> {
    parse(&crate::source::kvfile::load(vfs, "scripts/credits.txt")?)
}

/// The logo as a white-on-transparent PNG: the `logo` parameter (and `logo2`
/// below it), each line centered, in the scheme's `WeaponIcons` font.
/// `None` when the scheme or the font file is missing or the font lacks a
/// glyph for every character.
pub fn render_logo(vfs: &Vfs, credits: &Credits) -> Option<Vec<u8>> {
    let (file, tall) = scheme_font(vfs, LOGO_FONT)?;
    let font = vfs.open(&file)?;
    let mut lines = vec![
        credits
            .params
            .get("logo")
            .map_or(DEFAULT_LOGO, String::as_str),
    ];
    if let Some(second) = credits.params.get("logo2").filter(|s| !s.is_empty()) {
        lines.push(second);
    }
    let image = rasterize(&font, &lines, tall)?;
    crate::source::vtf::to_png(&image).ok()
}

/// The TTF file and pixel height a scheme font uses: the first definition's
/// `name`, matched to a `CustomFontFiles` entry by file name, and its `tall`.
fn scheme_font(vfs: &Vfs, font: &str) -> Option<(String, u32)> {
    let scheme = crate::source::kvfile::load(vfs, "resource/clientscheme.res")?;
    let root = match crate::source::keyvalues::get(&scheme, "Scheme") {
        Some(Value::Block(block)) => block,
        _ => &scheme,
    };
    let Some(Value::Block(fonts)) = crate::source::keyvalues::get(root, "Fonts") else {
        return None;
    };
    let Some(Value::Block(definition)) = crate::source::keyvalues::get(fonts, font) else {
        return None;
    };
    let Value::Block(first) = &definition.first()?.1 else {
        return None;
    };
    let name = crate::source::keyvalues::text(first, "name")?;
    let tall: u32 = crate::source::keyvalues::text(first, "tall")?
        .trim()
        .parse()
        .ok()?;
    if tall == 0 || tall > MAX_LOGO_PIXELS {
        return None;
    }
    let Some(Value::Block(files)) = crate::source::keyvalues::get(root, "CustomFontFiles") else {
        return None;
    };
    let file = files.iter().find_map(|(_, value)| {
        let Value::Text(path) = value else {
            return None;
        };
        let stem = path
            .rsplit(['/', '\\'])
            .next()?
            .strip_suffix(".ttf")
            .or_else(|| path.rsplit(['/', '\\']).next()?.strip_suffix(".TTF"))?;
        stem.eq_ignore_ascii_case(name).then(|| path.clone())
    })?;
    Some((file, tall))
}

/// White glyphs with coverage as alpha, `tall` pixels per line. A character
/// advances by its rounded advance width, as the client's
/// `GetStringPixelWidth` sums `GetCharacterWidth`.
fn rasterize(font: &[u8], lines: &[&str], tall: u32) -> Option<image::RgbaImage> {
    use ab_glyph::{Font, FontRef, PxScale, ScaleFont, point};
    let font = FontRef::try_from_slice(font).ok()?;
    let scaled = font.as_scaled(PxScale::from(tall as f32));
    let mut widths = Vec::new();
    for line in lines {
        let mut width = 0.0f32;
        for c in line.chars() {
            let id = scaled.glyph_id(c);
            if id.0 == 0 {
                return None;
            }
            width += scaled.h_advance(id).round();
        }
        widths.push(width);
    }
    let width = widths.iter().cloned().fold(0.0f32, f32::max).ceil() as u32;
    let height = tall * lines.len() as u32;
    if width == 0 || width > MAX_LOGO_PIXELS * 4 || height > MAX_LOGO_PIXELS * 4 {
        return None;
    }
    let mut image = image::RgbaImage::new(width, height);
    for (row, line) in lines.iter().enumerate() {
        let mut x = (width as f32 - widths[row]) / 2.0;
        let baseline = row as f32 * tall as f32 + scaled.ascent();
        for c in line.chars() {
            let id = scaled.glyph_id(c);
            let glyph = id.with_scale_and_position(PxScale::from(tall as f32), point(x, baseline));
            if let Some(outline) = font.outline_glyph(glyph) {
                let bounds = outline.px_bounds();
                outline.draw(|gx, gy, coverage| {
                    let px = bounds.min.x as i64 + i64::from(gx);
                    let py = bounds.min.y as i64 + i64::from(gy);
                    if px >= 0 && py >= 0 && (px as u32) < width && (py as u32) < height {
                        let pixel = image.get_pixel_mut(px as u32, py as u32);
                        let alpha = (coverage.clamp(0.0, 1.0) * 255.0).round() as u8;
                        *pixel = image::Rgba([255, 255, 255, pixel[3].max(alpha)]);
                    }
                });
            }
            x += scaled.h_advance(id).round();
        }
    }
    Some(image)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_sections_keeping_order_and_duplicates() {
        let text = "\"credits.txt\"\n{\n\"IntroCreditsNames\"\n{\n\"V\" \"WeaponIcons\"\n\" \" \"CreditsText\"\n\" \" \"CreditsText\"\n\"Ann\" \"CreditsText\"\n}\n\
            \"CreditsParams\"\n{\n\"Logo\" \"HALF-LIFE'\"\n\"posx\" \"96\"\n\"color\" \"255 255 255 128\"\n}\n\
            \"OutroCreditsNames\"\n{\n\"Bob\" \"CreditsOutroText\"\n}\n}\n";
        let credits = parse(&crate::source::keyvalues::parse(text)).unwrap();
        assert_eq!(credits.params["logo"], "HALF-LIFE'");
        assert_eq!(credits.params["color"], "255 255 255 128");
        let intro: Vec<&str> = credits.intro.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(intro, ["V", " ", " ", "Ann"]);
        assert_eq!(credits.intro[0].font, "WeaponIcons");
        assert_eq!(credits.outro.len(), 1);
        assert!(parse(&crate::source::keyvalues::parse("\"x\" { \"a\" \"b\" }")).is_none());
    }

    #[test]
    fn reads_half_life_2_credits_and_draws_the_logo() {
        let map = std::path::Path::new(
            "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_01.bsp",
        );
        if !map.exists() {
            return;
        }
        let vfs = Vfs::for_map(map, &[]);
        let credits = load(&vfs).expect("credits.txt");
        assert_eq!(credits.params["logo"], "HALF-LIFE'");
        assert_eq!(credits.params["posy"], "360");
        assert_eq!(credits.intro[0].name, "V");
        assert_eq!(credits.intro[0].font, "WeaponIcons");
        assert_eq!(credits.intro.len(), 87);
        assert_eq!(credits.outro[0].font, "CreditsOutroLogos");
        let (file, tall) = scheme_font(&vfs, "WeaponIcons").expect("scheme font");
        assert_eq!(
            (file.to_ascii_lowercase().as_str(), tall),
            ("resource/halflife2.ttf", 64)
        );
        let png = render_logo(&vfs, &credits).expect("logo");
        let image = image::load_from_memory(&png).unwrap().to_rgba8();
        assert_eq!(image.height(), 64);
        assert!(image.width() > 100, "{} wide", image.width());
        assert!(image.pixels().filter(|p| p[3] > 128).count() > 500);
        assert!(
            image
                .pixels()
                .all(|p| p[3] == 0 || p.0[..3] == [255, 255, 255])
        );
    }
}
