//! The map's sky (format section 20): the faces Source draws the sky through,
//! and the six sides of its 2D skybox.
//!
//! Source never draws a sky face. The engine draws the skybox -- a box of six
//! textures around the eye, and the 3D skybox room in front of it -- before the
//! world, and the sky faces are simply where no world covers it. Minecraft has
//! its own sky and, outside a converted map, its own terrain, so the mod draws
//! the sky faces instead: each pixel of one shows what Source's skybox shows in
//! that direction. The faces are the world's, as the BSP's face lump holds them
//! (`SURF_SKY`, which `toolsskybox2d` sets along with `SURF_SKY2D`), less those
//! of the 3D skybox room, which is not converted.
//!
//! Each side is the material `skybox/<skyname><side>`, as the engine names
//! them. Minecraft draws without HDR, so a side is what Source's LDR path
//! draws: `$basetexture` (the `Sky` shader's HDR textures are left alone),
//! tinted by `$color`, through `$basetexturetransform`. The transform is baked
//! into the image: Portal's and INFRA's side textures are half height, drawn
//! with `scale 1 2` so they cover the top half and the clamped bottom row fills
//! the rest.

use crate::bsp::Map;
use crate::bsp::skybox::Skybox;
use crate::geom::{Aabb, Vec3};
use crate::output::metadata::{Diagnostic, Severity};
use crate::source::vfs::Vfs;
use crate::source::vmt::{Materials, Vmt};
use crate::voxel::transform::Transform;
use anyhow::{Result, ensure};
use image::RgbaImage;
use std::collections::BTreeMap;

pub const MAGIC: [u8; 8] = *b"S2SKY\0\0\0";
pub const VERSION: u32 = 1;
/// The order the sides are stored in, by their material suffix.
pub const SIDES: [&str; 6] = ["rt", "lf", "bk", "ft", "up", "dn"];
/// No baked side is larger than this along either axis.
const MAX_SIDE: u32 = 2048;
/// A face has at most this many corners; the compiler's limit is 64.
pub const MAX_FACE_POINTS: usize = 256;

const SURF_SKY: u32 = 0x4;
const TEXTUREFLAGS_CLAMPS: u32 = 0x4;
const TEXTUREFLAGS_CLAMPT: u32 = 0x8;

/// One side's baked image, PNG-encoded and content-addressed.
pub struct SkySide {
    pub content_id: String,
    pub png: Vec<u8>,
}

pub struct SkyExport {
    pub skyname: String,
    /// In [`SIDES`] order; `None` for a side whose material is missing.
    pub sides: [Option<SkySide>; 6],
    /// Map-local block coordinates, each wound counter-clockwise seen from the
    /// side Source draws the sky on.
    pub faces: Vec<Vec<[f32; 3]>>,
    pub diagnostics: Vec<Diagnostic>,
}

/// The map's sky; `None` when it has no sky face, so that there is nothing to
/// see the sky through.
pub fn build(
    map: &Map,
    vfs: &Vfs,
    transform: &Transform,
    skybox: Option<&Skybox>,
) -> Result<Option<SkyExport>> {
    let faces = sky_faces(map, transform, skybox);
    if faces.is_empty() {
        return Ok(None);
    }
    let skyname = map
        .bsp
        .entities
        .iter()
        .next()
        .and_then(|world| {
            world
                .properties()
                .find(|(key, _)| key.eq_ignore_ascii_case("skyname"))
                .map(|(_, value)| value.trim().to_string())
        })
        .unwrap_or_default();
    let materials = Materials::new(vfs, Some(&map.bsp.pack));
    let mut diagnostics = Vec::new();
    let mut sides: [Option<SkySide>; 6] = Default::default();
    for (slot, suffix) in SIDES.iter().enumerate() {
        let name = format!("skybox/{skyname}{suffix}");
        match bake_side(&materials, vfs, &name) {
            Ok(image) => {
                let png = crate::source::vtf::to_png(&image)?;
                sides[slot] = Some(SkySide {
                    content_id: crate::output::bundle::content_id(&png),
                    png,
                });
            }
            Err(reason) => {
                let mut context = BTreeMap::new();
                context.insert("material".into(), name);
                context.insert("reason".into(), reason);
                diagnostics.push(Diagnostic {
                    severity: Severity::Warning,
                    code: "SKY_SIDE_MISSING".into(),
                    message: "a side of the 2D skybox could not be read; it is drawn black".into(),
                    context,
                });
            }
        }
    }
    let mut context = BTreeMap::new();
    context.insert("skyname".into(), skyname.clone());
    context.insert("faces".into(), faces.len().to_string());
    diagnostics.push(Diagnostic {
        severity: Severity::Info,
        code: "SKY_SUMMARY".into(),
        message: "faces the sky is drawn through, and the 2D skybox drawn in them".into(),
        context,
    });
    Ok(Some(SkyExport {
        skyname,
        sides,
        faces,
        diagnostics,
    }))
}

/// Every sky face of the world outside the 3D skybox room, in block space.
fn sky_faces(map: &Map, transform: &Transform, skybox: Option<&Skybox>) -> Vec<Vec<[f32; 3]>> {
    let Some(world) = map.bsp.models.first() else {
        return Vec::new();
    };
    let first = usize::try_from(world.first_face).unwrap_or(0);
    let count = usize::try_from(world.face_count).unwrap_or(0);
    let mut faces = Vec::new();
    for face_index in first..first.saturating_add(count) {
        let Some(face) = map.bsp.faces.get(face_index) else {
            break;
        };
        if face.displacement_info >= 0 {
            continue;
        }
        let Some(info) = usize::try_from(face.texture_info)
            .ok()
            .and_then(|index| map.bsp.textures_info.get(index))
        else {
            continue;
        };
        if info.flags.bits() & SURF_SKY == 0 {
            continue;
        }
        let Some(plane) = map.bsp.planes.get(face.plane_num as usize) else {
            continue;
        };
        let points: Vec<Vec3> = vbsp::Handle::new(&map.bsp, face)
            .vertices()
            .map(|vertex| Vec3::from(vertex.position))
            .collect();
        if points.len() < 3 || points.len() > MAX_FACE_POINTS {
            continue;
        }
        if let Some(room) = skybox {
            let bounds = points
                .iter()
                .skip(1)
                .fold(Aabb::new(points[0], points[0]), |b, &p| {
                    b.union(&Aabb::new(p, p))
                });
            if room.contains(&bounds) {
                continue;
            }
        }
        let mut block: Vec<Vec3> = points
            .iter()
            .map(|&p| transform.to_block_space(p))
            .collect();
        // The face's own plane faces the side the sky is seen from.
        let facing = transform.transform_direction(Vec3::from(plane.normal));
        if winding(&block).dot(facing) < 0.0 {
            block.reverse();
        }
        faces.push(
            block
                .iter()
                .map(|p| [p.x as f32, p.y as f32, p.z as f32])
                .collect(),
        );
    }
    faces
}

/// Newell's normal of a polygon: its area-weighted facing, counter-clockwise
/// positive.
fn winding(points: &[Vec3]) -> Vec3 {
    let mut normal = Vec3::ZERO;
    for (i, a) in points.iter().enumerate() {
        let b = points[(i + 1) % points.len()];
        normal = normal + a.cross(b);
    }
    normal
}

/// A side as Source's LDR path draws it, its texture transform baked in.
fn bake_side(materials: &Materials, vfs: &Vfs, name: &str) -> Result<RgbaImage, String> {
    let vmt = materials.vmt(name).ok_or("material not found")?;
    let tint = color(&vmt).unwrap_or([1.0; 3]);
    let texture = vmt
        .get("$basetexture")
        .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
        .filter(|v| !v.is_empty());
    let Some(texture) = texture else {
        // UnlitGeneric without a texture draws its colour: Portal 2's sky_fog.
        if vmt.get("$color").is_none() {
            return Err("no $basetexture or $color".into());
        }
        return Ok(RgbaImage::from_pixel(
            4,
            4,
            image::Rgba(tinted([255; 4], tint)),
        ));
    };
    let path = format!("materials/{}.vtf", texture.trim_end_matches(".vtf"));
    let data = vfs.open(&path).ok_or_else(|| format!("{path} not found"))?;
    let vtf = vtf::from_bytes(&data).map_err(|e| format!("{path}: {e}"))?;
    let flags = vtf.header.flags;
    let source = vtf
        .highres_image
        .decode(0)
        .map_err(|e| format!("{path}: {e}"))?
        .to_rgba8();
    if source.width() == 0 || source.height() == 0 {
        return Err(format!("{path} has no pixels"));
    }
    let transform = TextureTransform::parse(vmt.get("$basetexturetransform"));
    Ok(bake(
        &source,
        &transform,
        [
            flags & TEXTUREFLAGS_CLAMPS != 0,
            flags & TEXTUREFLAGS_CLAMPT != 0,
        ],
        tint,
    ))
}

/// `$color`: `{r g b}` in 0 to 255, or `[r g b]` in 0 to 1.
fn color(vmt: &Vmt) -> Option<[f64; 3]> {
    let value = vmt.get("$color")?.trim().trim_matches('"').trim();
    let (inner, scale) = if let Some(inner) = value.strip_prefix('{') {
        (inner.trim_end_matches('}'), 255.0)
    } else {
        (value.trim_start_matches('[').trim_end_matches(']'), 1.0)
    };
    let parts: Vec<f64> = inner
        .split_whitespace()
        .filter_map(|p| p.parse::<f64>().ok())
        .collect();
    (parts.len() == 3).then(|| [parts[0] / scale, parts[1] / scale, parts[2] / scale])
}

fn tinted(pixel: [u8; 4], tint: [f64; 3]) -> [u8; 4] {
    let channel = |value: u8, by: f64| (f64::from(value) * by).round().clamp(0.0, 255.0) as u8;
    [
        channel(pixel[0], tint[0]),
        channel(pixel[1], tint[1]),
        channel(pixel[2], tint[2]),
        255,
    ]
}

/// `$basetexturetransform`: `center cx cy scale sx sy rotate degrees translate
/// tx ty`, each part optional, applied to a vertex's texture coordinate as
/// `center + rotate(scale * (uv - center)) + translate`.
#[derive(Debug, Clone, Copy, PartialEq)]
struct TextureTransform {
    center: [f64; 2],
    scale: [f64; 2],
    rotate: f64,
    translate: [f64; 2],
}

impl TextureTransform {
    const IDENTITY: TextureTransform = TextureTransform {
        center: [0.5, 0.5],
        scale: [1.0, 1.0],
        rotate: 0.0,
        translate: [0.0, 0.0],
    };

    fn parse(value: Option<&str>) -> TextureTransform {
        let mut transform = Self::IDENTITY;
        let Some(value) = value else {
            return transform;
        };
        let words: Vec<&str> = value.trim_matches('"').split_whitespace().collect();
        let number = |i: usize| words.get(i).and_then(|w| w.parse::<f64>().ok());
        let mut i = 0;
        while i < words.len() {
            match words[i].to_ascii_lowercase().as_str() {
                "center" => {
                    transform.center = [number(i + 1).unwrap_or(0.5), number(i + 2).unwrap_or(0.5)];
                    i += 3;
                }
                "scale" => {
                    transform.scale = [number(i + 1).unwrap_or(1.0), number(i + 2).unwrap_or(1.0)];
                    i += 3;
                }
                "rotate" => {
                    transform.rotate = number(i + 1).unwrap_or(0.0);
                    i += 2;
                }
                "translate" => {
                    transform.translate =
                        [number(i + 1).unwrap_or(0.0), number(i + 2).unwrap_or(0.0)];
                    i += 3;
                }
                _ => i += 1,
            }
        }
        transform
    }

    fn apply(&self, uv: [f64; 2]) -> [f64; 2] {
        let scaled = [
            self.scale[0] * (uv[0] - self.center[0]),
            self.scale[1] * (uv[1] - self.center[1]),
        ];
        let (sin, cos) = self.rotate.to_radians().sin_cos();
        [
            self.center[0] + cos * scaled[0] - sin * scaled[1] + self.translate[0],
            self.center[1] + sin * scaled[0] + cos * scaled[1] + self.translate[1],
        ]
    }
}

/// Resamples `source` as the side's quad shows it: each texel of the result is
/// the texel its transformed coordinate lands on, clamped or wrapped as the
/// texture's flags say. A side stretched by an integer scale keeps every texel.
fn bake(
    source: &RgbaImage,
    transform: &TextureTransform,
    clamp: [bool; 2],
    tint: [f64; 3],
) -> RgbaImage {
    let (width, height) = (source.width(), source.height());
    let stretch = |size: u32, scale: f64| {
        ((f64::from(size) * scale.abs().max(1.0)).round() as u32).clamp(1, MAX_SIDE)
    };
    let out_width = stretch(width, transform.scale[0]);
    let out_height = stretch(height, transform.scale[1]);
    let texel = |coordinate: f64, size: u32, clamped: bool| {
        let index = (coordinate * f64::from(size)).floor() as i64;
        if clamped {
            index.clamp(0, i64::from(size) - 1) as u32
        } else {
            index.rem_euclid(i64::from(size)) as u32
        }
    };
    RgbaImage::from_fn(out_width, out_height, |x, y| {
        let uv = [
            (f64::from(x) + 0.5) / f64::from(out_width),
            (f64::from(y) + 0.5) / f64::from(out_height),
        ];
        let [u, v] = transform.apply(uv);
        let pixel = source.get_pixel(texel(u, width, clamp[0]), texel(v, height, clamp[1]));
        image::Rgba(tinted(pixel.0, tint))
    })
}

/// Encodes the sky table. Faces are written in the order given.
pub fn encode(sky: &SkyExport) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    for side in &sky.sides {
        match side {
            Some(side) => {
                out.push(1);
                let id = side.content_id.as_bytes();
                ensure!(
                    id.len() == 64 && id.iter().all(u8::is_ascii_hexdigit),
                    "sky side content ID is not a SHA-256"
                );
                for pair in id.chunks(2) {
                    let digits = std::str::from_utf8(pair)?;
                    out.push(u8::from_str_radix(digits, 16)?);
                }
            }
            None => out.push(0),
        }
    }
    ensure!(
        sky.faces.len() <= crate::output::limits::MAX_SKY_FACES,
        "sky face limit exceeded"
    );
    out.extend_from_slice(&(sky.faces.len() as u32).to_le_bytes());
    for face in &sky.faces {
        ensure!(
            (3..=MAX_FACE_POINTS).contains(&face.len()),
            "sky face corner count out of range"
        );
        out.extend_from_slice(&(face.len() as u16).to_le_bytes());
        for point in face {
            for &coordinate in point {
                ensure!(coordinate.is_finite(), "non-finite sky face corner");
                // Negative zero is not canonical (section 2).
                let coordinate = if coordinate == 0.0 {
                    0.0f32
                } else {
                    coordinate
                };
                out.extend_from_slice(&coordinate.to_le_bytes());
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_half_height_side_covers_the_top_and_clamps_below() {
        // Portal's sky_day01_05bk: 2x2 texels, drawn with `scale 1 2` about the corner.
        let mut source = RgbaImage::new(2, 2);
        source.put_pixel(0, 0, image::Rgba([10, 0, 0, 255]));
        source.put_pixel(1, 0, image::Rgba([20, 0, 0, 255]));
        source.put_pixel(0, 1, image::Rgba([30, 0, 0, 255]));
        source.put_pixel(1, 1, image::Rgba([40, 0, 0, 255]));
        let transform =
            TextureTransform::parse(Some("center 0 0 scale 1 2 rotate 0 translate 0 0"));
        let baked = bake(&source, &transform, [true, true], [1.0; 3]);
        assert_eq!((baked.width(), baked.height()), (2, 4));
        let column: Vec<u8> = (0..4).map(|y| baked.get_pixel(0, y).0[0]).collect();
        assert_eq!(column, [10, 30, 30, 30]);
    }

    #[test]
    fn an_unclamped_texture_repeats() {
        let mut source = RgbaImage::new(1, 2);
        source.put_pixel(0, 0, image::Rgba([1, 0, 0, 255]));
        source.put_pixel(0, 1, image::Rgba([2, 0, 0, 255]));
        let transform = TextureTransform::parse(Some("center 0 0 scale 1 2"));
        let baked = bake(&source, &transform, [false, false], [1.0; 3]);
        let column: Vec<u8> = (0..4).map(|y| baked.get_pixel(0, y).0[0]).collect();
        assert_eq!(column, [1, 2, 1, 2]);
    }

    #[test]
    fn colors_read_both_ways_source_writes_them() {
        let braces = crate::source::vmt::parse(r#"UnlitGeneric { $color "{70 85 100}" }"#);
        let c = color(&braces).unwrap();
        assert!((c[0] - 70.0 / 255.0).abs() < 1e-9 && (c[2] - 100.0 / 255.0).abs() < 1e-9);
        let brackets = crate::source::vmt::parse(r#"UnlitGeneric { "$color" "[0.5 1 0.25]" }"#);
        assert_eq!(color(&brackets), Some([0.5, 1.0, 0.25]));
    }

    #[test]
    fn the_default_transform_changes_nothing() {
        let identity = TextureTransform::parse(None);
        assert_eq!(identity.apply([0.25, 0.75]), [0.25, 0.75]);
    }

    #[test]
    fn winding_is_counter_clockwise_positive() {
        let square = [
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(1.0, 1.0, 0.0),
            Vec3::new(0.0, 1.0, 0.0),
        ];
        assert!(winding(&square).z > 0.0);
    }
}
