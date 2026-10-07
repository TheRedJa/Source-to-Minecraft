//! Deterministic, lossless partitioning for mod-owned texture pages.

use anyhow::{Result, ensure};
use image::{Rgba, RgbaImage, imageops::FilterType};

pub const PAGE_SIZE: u32 = 4096;
pub const MAX_MIP_LEVEL: u8 = 4;
pub const GUTTER: u32 = 1 << MAX_MIP_LEVEL;
pub const USABLE_AXIS: u32 = PAGE_SIZE - 2 * GUTTER;
pub const TEXELS_PER_BLOCK: f64 = 16.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ResolutionDecision {
    pub original: [u32; 2],
    pub output: [u32; 2],
    pub resampled: bool,
}

/// How much of each source texture's resolution an export keeps.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TextureQuality {
    /// Up to [`TEXELS_PER_BLOCK`] texels per projected block, never more than
    /// the source has.
    #[default]
    Default,
    /// The source texture's own resolution, untouched. Only a texture wider or
    /// taller than [`PAGE_SIZE`], which the client accepts as the largest
    /// output axis, is scaled down to fit, keeping its aspect ratio.
    Full,
}

/// Retain up to 16 output texels per projected block, without inventing detail
/// by enlarging an image beyond its Source dimensions; or, at
/// [`TextureQuality::Full`], the source resolution itself.
pub fn analyze_resolution(
    original: [u32; 2],
    blocks_spanned: [f64; 2],
    quality: TextureQuality,
) -> Result<ResolutionDecision> {
    ensure!(
        original[0] > 0 && original[1] > 0,
        "texture dimensions must be positive"
    );
    ensure!(
        blocks_spanned.iter().all(|v| v.is_finite() && *v > 0.0),
        "texture projection span must be finite and positive"
    );
    let output = match quality {
        TextureQuality::Default => std::array::from_fn(|axis| {
            let required = (blocks_spanned[axis] * TEXELS_PER_BLOCK)
                .round()
                .clamp(1.0, f64::from(u32::MAX)) as u32;
            required.min(original[axis])
        }),
        TextureQuality::Full => {
            let largest = original[0].max(original[1]);
            if largest <= PAGE_SIZE {
                original
            } else {
                let scale = f64::from(PAGE_SIZE) / f64::from(largest);
                std::array::from_fn(|axis| {
                    ((f64::from(original[axis]) * scale).round() as u32).clamp(1, PAGE_SIZE)
                })
            }
        }
    };
    Ok(ResolutionDecision {
        original,
        output,
        resampled: output != original,
    })
}

/// `output` scaled down, keeping its aspect ratio, so it fits one atlas region
/// ([`USABLE_AXIS`]) and is never split across pages.
pub fn fit_one_region(output: [u32; 2]) -> [u32; 2] {
    let largest = output[0].max(output[1]);
    if largest <= USABLE_AXIS {
        return output;
    }
    let scale = f64::from(USABLE_AXIS) / f64::from(largest);
    std::array::from_fn(|axis| {
        ((f64::from(output[axis]) * scale).round() as u32).clamp(1, USABLE_AXIS)
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogicalTexture {
    pub content_id: String,
    pub width: u32,
    pub height: u32,
    /// Packed after every texture without it: the 3D skybox room's own
    /// textures, which must not spread the map's over more pages.
    pub after_map: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Region {
    pub content_id: String,
    /// Pixel rectangle in the unsplit logical texture.
    pub source: Rect,
    pub page: u32,
    /// Page rectangle excluding its extruded gutter.
    pub allocation: Rect,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    pub page_count: u32,
    pub regions: Vec<Region>,
}

#[derive(Debug)]
pub struct ImageAsset {
    pub content_id: String,
    pub image: RgbaImage,
}

#[derive(Debug)]
pub struct PageImages {
    /// One complete page image per mip level, largest first.
    pub mips: Vec<RgbaImage>,
}

#[derive(Debug, Clone)]
struct Piece {
    content_id: String,
    source: Rect,
}

/// Deterministic shelf packing. Discovery order cannot change the result.
/// Oversized images become source rectangles without changing pixel count.
/// The map's textures come first, tallest first so each shelf is filled by
/// pieces of about its height: in content ID order, furnace's 0.2 pages of
/// texels took 2 pages.
pub fn pack(textures: &[LogicalTexture]) -> Result<Layout> {
    let mut ordered = textures.to_vec();
    ordered.sort_by(|a, b| a.content_id.cmp(&b.content_id));
    ensure!(
        ordered
            .windows(2)
            .all(|p| p[0].content_id != p[1].content_id),
        "duplicate logical texture content ID"
    );
    ordered.sort_by(|a, b| {
        a.after_map
            .cmp(&b.after_map)
            .then(b.height.min(USABLE_AXIS).cmp(&a.height.min(USABLE_AXIS)))
            .then(b.width.cmp(&a.width))
            .then(a.content_id.cmp(&b.content_id))
    });
    let mut pieces = Vec::new();
    for texture in ordered {
        ensure!(
            texture.width > 0 && texture.height > 0,
            "texture dimensions must be positive"
        );
        for y in (0..texture.height).step_by(USABLE_AXIS as usize) {
            for x in (0..texture.width).step_by(USABLE_AXIS as usize) {
                pieces.push(Piece {
                    content_id: texture.content_id.clone(),
                    source: Rect {
                        x,
                        y,
                        width: (texture.width - x).min(USABLE_AXIS),
                        height: (texture.height - y).min(USABLE_AXIS),
                    },
                });
            }
        }
    }

    let mut regions = Vec::with_capacity(pieces.len());
    let (mut page, mut cursor_x, mut cursor_y, mut row_height) = (0u32, 0u32, 0u32, 0u32);
    for piece in pieces {
        let packed_width = align(piece.source.width + 2 * GUTTER, 1 << MAX_MIP_LEVEL);
        let packed_height = align(piece.source.height + 2 * GUTTER, 1 << MAX_MIP_LEVEL);
        ensure!(
            packed_width <= PAGE_SIZE && packed_height <= PAGE_SIZE,
            "atlas piece exceeds page"
        );
        if cursor_x + packed_width > PAGE_SIZE {
            cursor_x = 0;
            cursor_y += row_height;
            row_height = 0;
        }
        if cursor_y + packed_height > PAGE_SIZE {
            page = page
                .checked_add(1)
                .ok_or_else(|| anyhow::anyhow!("atlas page count overflow"))?;
            cursor_x = 0;
            cursor_y = 0;
            row_height = 0;
        }
        regions.push(Region {
            content_id: piece.content_id,
            source: piece.source,
            page,
            allocation: Rect {
                x: cursor_x + GUTTER,
                y: cursor_y + GUTTER,
                width: piece.source.width,
                height: piece.source.height,
            },
        });
        cursor_x += packed_width;
        row_height = row_height.max(packed_height);
    }
    Ok(Layout {
        page_count: if regions.is_empty() { 0 } else { page + 1 },
        regions,
    })
}

/// Materialize packed pages. Gutter samples come from the logical image, not
/// merely the partition edge, so a partition boundary stays continuous.
pub fn build_pages(layout: &Layout, assets: &[ImageAsset]) -> Result<Vec<PageImages>> {
    use std::collections::BTreeMap;
    let by_id: BTreeMap<_, _> = assets
        .iter()
        .map(|asset| (asset.content_id.as_str(), &asset.image))
        .collect();
    ensure!(
        by_id.len() == assets.len(),
        "duplicate texture image content ID"
    );
    for region in &layout.regions {
        let source = by_id
            .get(region.content_id.as_str())
            .ok_or_else(|| anyhow::anyhow!("missing logical texture {}", region.content_id))?;
        ensure!(
            region.source.x + region.source.width <= source.width()
                && region.source.y + region.source.height <= source.height(),
            "atlas source region exceeds image"
        );
        ensure!(
            region.page < layout.page_count,
            "atlas page index out of range"
        );
    }
    // Pages are independent, and so is each mip of a page, since every level
    // is resampled from the full-size page: both are built in parallel.
    use rayon::prelude::*;
    Ok((0..layout.page_count)
        .into_par_iter()
        .map(|page_index| {
            let mut page = RgbaImage::from_pixel(PAGE_SIZE, PAGE_SIZE, Rgba([0, 0, 0, 0]));
            for region in layout.regions.iter().filter(|r| r.page == page_index) {
                let source = by_id[region.content_id.as_str()];
                for dy in -(GUTTER as i64)..i64::from(region.source.height + GUTTER) {
                    for dx in -(GUTTER as i64)..i64::from(region.source.width + GUTTER) {
                        let source_x = (i64::from(region.source.x) + dx)
                            .rem_euclid(i64::from(source.width()))
                            as u32;
                        let source_y = (i64::from(region.source.y) + dy)
                            .rem_euclid(i64::from(source.height()))
                            as u32;
                        let page_x = (i64::from(region.allocation.x) + dx) as u32;
                        let page_y = (i64::from(region.allocation.y) + dy) as u32;
                        page.put_pixel(page_x, page_y, *source.get_pixel(source_x, source_y));
                    }
                }
            }
            let mut smaller: Vec<RgbaImage> = (1..=MAX_MIP_LEVEL)
                .into_par_iter()
                .map(|level| {
                    let size = PAGE_SIZE >> level;
                    image::imageops::resize(&page, size, size, FilterType::Triangle)
                })
                .collect();
            for region in layout.regions.iter().filter(|r| r.page == page_index) {
                if let Some(coverage) = cutout_coverage(by_id[region.content_id.as_str()], region) {
                    for (index, mip) in smaller.iter_mut().enumerate() {
                        keep_cutout_coverage(mip, region, index as u32 + 1, coverage);
                    }
                }
            }
            let mut mips = vec![page];
            mips.extend(smaller);
            PageImages { mips }
        })
        .collect())
}

/// The share of opaque texels in a region of a cut-out texture, or `None`
/// when the region is not cut-out: cut-out textures leave the converter with
/// alpha already binarized, so any other alpha value means blended or opaque.
fn cutout_coverage(source: &RgbaImage, region: &Region) -> Option<f64> {
    let mut opaque = 0u64;
    let mut clear = 0u64;
    for y in region.source.y..region.source.y + region.source.height {
        for x in region.source.x..region.source.x + region.source.width {
            match source.get_pixel(x, y).0[3] {
                255 => opaque += 1,
                0 => clear += 1,
                _ => return None,
            }
        }
    }
    (clear > 0).then(|| opaque as f64 / (opaque + clear) as f64)
}

/// Re-binarizes a cut-out region's alpha in one mip, keeping its level-0 share
/// of opaque texels.
///
/// Averaging alpha leaves the holes of a fence or grate half opaque in every
/// smaller mip, and the cut-out shader only drops texels below one tenth, so a
/// mesh fence turned solid with distance. Keeping the same share opaque, as at
/// full size, keeps it see-through. The gutter takes the region's cutoff.
fn keep_cutout_coverage(mip: &mut RgbaImage, region: &Region, level: u32, coverage: f64) {
    let rect = |r: &Rect, margin: u32| {
        let x0 = r.x.saturating_sub(margin) >> level;
        let y0 = r.y.saturating_sub(margin) >> level;
        let x1 = ((r.x + r.width + margin) >> level).min(mip.width());
        let y1 = ((r.y + r.height + margin) >> level).min(mip.height());
        (x0, y0, x1, y1)
    };
    // Ties are common -- a regular mesh averages to the same alpha everywhere --
    // so an ordered-dither rank breaks them, spreading the kept texels evenly.
    let key = |mip: &RgbaImage, x: u32, y: u32| {
        u32::from(mip.get_pixel(x, y).0[3]) * 16 + 15 - BAYER_4[(y % 4) as usize][(x % 4) as usize]
    };
    let (x0, y0, x1, y1) = rect(&region.allocation, 0);
    let mut keys: Vec<u32> = Vec::new();
    for y in y0..y1 {
        for x in x0..x1 {
            keys.push(key(mip, x, y));
        }
    }
    if keys.is_empty() {
        return;
    }
    keys.sort_unstable();
    let total = keys.len();
    let want = (coverage * total as f64).round() as usize;
    let cutoff = if want == 0 {
        u32::MAX
    } else {
        keys[total - want.min(total)]
    };
    let (x0, y0, x1, y1) = rect(&region.allocation, GUTTER);
    for y in y0..y1 {
        for x in x0..x1 {
            let opaque = key(mip, x, y) >= cutoff;
            mip.get_pixel_mut(x, y).0[3] = if opaque { 255 } else { 0 };
        }
    }
}

/// 4x4 ordered-dither ranks.
const BAYER_4: [[u32; 4]; 4] = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];

fn align(value: u32, alignment: u32) -> u32 {
    value.div_ceil(alignment) * alignment
}

#[cfg(test)]
mod tests {
    use super::*;

    fn texture(id: &str, width: u32, height: u32) -> LogicalTexture {
        LogicalTexture {
            content_id: id.into(),
            width,
            height,
            after_map: false,
        }
    }

    #[test]
    fn oversized_texture_is_partitioned_without_resampling() {
        let layout = pack(&[texture("a", 8192, 5000)]).unwrap();
        assert!(layout.regions.len() > 1);
        let area: u64 = layout
            .regions
            .iter()
            .map(|r| u64::from(r.source.width) * u64::from(r.source.height))
            .sum();
        assert_eq!(area, 8192 * 5000);
        for region in &layout.regions {
            assert!(region.allocation.x + region.allocation.width + GUTTER <= PAGE_SIZE);
            assert!(region.allocation.y + region.allocation.height + GUTTER <= PAGE_SIZE);
        }
    }

    #[test]
    fn textures_after_the_map_never_share_its_first_pages() {
        let mut textures: Vec<LogicalTexture> = (0..40)
            .map(|i| texture(&format!("map{i:02}"), 64 + i * 8, 64 + i * 8))
            .collect();
        for i in 0..4 {
            let mut room = texture(&format!("room{i}"), 2048, 2048);
            room.after_map = true;
            textures.push(room);
        }
        let layout = pack(&textures).unwrap();
        let last_map_page = layout
            .regions
            .iter()
            .filter(|r| r.content_id.starts_with("map"))
            .map(|r| r.page)
            .max()
            .unwrap();
        assert_eq!(last_map_page, 0);
        let first_room = layout
            .regions
            .iter()
            .position(|r| r.content_id.starts_with("room"))
            .unwrap();
        assert!(
            layout.regions[..first_room]
                .iter()
                .all(|r| r.content_id.starts_with("map"))
        );
    }

    #[test]
    fn tall_pieces_share_shelves_with_pieces_of_their_height() {
        // In content ID order these alternate tall and short, and every shelf
        // is as tall as its tallest piece; tallest first they fit one page.
        let textures: Vec<LogicalTexture> = (0..112)
            .map(|i| {
                if i % 2 == 0 {
                    texture(&format!("{i:03}"), 480, 480)
                } else {
                    texture(&format!("{i:03}"), 16, 16)
                }
            })
            .collect();
        let layout = pack(&textures).unwrap();
        assert_eq!(layout.page_count, 1);
    }

    #[test]
    fn packing_is_independent_of_discovery_order() {
        let a = texture("a", 64, 64);
        let b = texture("b", 128, 32);
        assert_eq!(
            pack(&[a.clone(), b.clone()]).unwrap(),
            pack(&[b, a]).unwrap()
        );
    }

    #[test]
    fn every_region_keeps_a_gutter_through_mip_four() {
        let layout = pack(&[texture("a", 31, 47), texture("b", 4000, 64)]).unwrap();
        for level in 0..=MAX_MIP_LEVEL {
            assert!(GUTTER >> level >= 1);
            for region in &layout.regions {
                assert_eq!(region.allocation.x % (1 << level), 0);
                assert_eq!(region.allocation.y % (1 << level), 0);
            }
        }
    }

    #[test]
    fn invalid_and_duplicate_inputs_fail() {
        assert!(pack(&[texture("a", 0, 1)]).is_err());
        assert!(pack(&[texture("a", 1, 1), texture("a", 2, 2)]).is_err());
    }

    #[test]
    fn resolution_targets_sixteen_texels_per_projected_block() {
        assert_eq!(
            analyze_resolution([512, 256], [4.0, 2.0], TextureQuality::Default).unwrap(),
            ResolutionDecision {
                original: [512, 256],
                output: [64, 32],
                resampled: true
            }
        );
        assert_eq!(
            analyze_resolution([8, 8], [4.0, 4.0], TextureQuality::Default)
                .unwrap()
                .output,
            [8, 8]
        );
        assert!(analyze_resolution([1, 1], [f64::NAN, 1.0], TextureQuality::Default).is_err());
    }

    #[test]
    fn four_block_1024_texture_keeps_sixteen_texels_per_block() {
        // Regression for the INFRA floor: once its texture-info record is
        // resolved to its owning material, a 1024 texture repeats across four
        // Minecraft blocks and therefore needs a 64 pixel export.
        assert_eq!(
            analyze_resolution([1024, 1024], [4.0, 4.0], TextureQuality::Default)
                .unwrap()
                .output,
            [64, 64]
        );
    }

    #[test]
    fn full_quality_keeps_the_source_resolution() {
        let decision = analyze_resolution([1024, 512], [4.0, 2.0], TextureQuality::Full).unwrap();
        assert_eq!(decision.output, [1024, 512]);
        assert!(!decision.resampled);
        // However small the projection, nothing is shrunk.
        assert_eq!(
            analyze_resolution([2048, 2048], [0.25, 0.25], TextureQuality::Full)
                .unwrap()
                .output,
            [2048, 2048]
        );
    }

    #[test]
    fn effect_textures_fit_one_region() {
        assert_eq!(fit_one_region([4096, 4096]), [USABLE_AXIS, USABLE_AXIS]);
        assert_eq!(fit_one_region([8192, 2048]), [USABLE_AXIS, 1016]);
        assert_eq!(fit_one_region([2048, 512]), [2048, 512]);
        let layout = pack(&[texture("fire", USABLE_AXIS, USABLE_AXIS)]).unwrap();
        assert_eq!(layout.regions.len(), 1);
    }

    #[test]
    fn full_quality_fits_oversized_textures_to_a_page() {
        assert_eq!(
            analyze_resolution([8192, 2048], [1.0, 1.0], TextureQuality::Full)
                .unwrap()
                .output,
            [4096, 1024]
        );
    }

    fn page_mips(image: RgbaImage) -> (Layout, Vec<PageImages>) {
        let layout = pack(&[texture("t", image.width(), image.height())]).unwrap();
        let pages = build_pages(
            &layout,
            &[ImageAsset {
                content_id: "t".into(),
                image,
            }],
        )
        .unwrap();
        (layout, pages)
    }

    fn region_alphas(layout: &Layout, mip: &RgbaImage, level: u32) -> Vec<u8> {
        let a = layout.regions[0].allocation;
        let mut out = Vec::new();
        for y in (a.y >> level)..((a.y + a.height) >> level) {
            for x in (a.x >> level)..((a.x + a.width) >> level) {
                out.push(mip.get_pixel(x, y).0[3]);
            }
        }
        out
    }

    #[test]
    fn cutout_mips_keep_their_holes() {
        // A fine mesh: one opaque texel in four. Averaged, every mip texel would be
        // a quarter opaque and pass the cut-out shader's one-tenth test as solid.
        let image = RgbaImage::from_fn(64, 64, |x, y| {
            Rgba([
                200,
                200,
                200,
                if x % 2 == 0 && y % 2 == 0 { 255 } else { 0 },
            ])
        });
        let (layout, pages) = page_mips(image);
        for level in 1..=u32::from(MAX_MIP_LEVEL) {
            let alphas = region_alphas(&layout, &pages[0].mips[level as usize], level);
            assert!(alphas.iter().all(|&a| a == 0 || a == 255), "level {level}");
            let opaque = alphas.iter().filter(|&&a| a == 255).count() as f64 / alphas.len() as f64;
            assert!((opaque - 0.25).abs() < 0.1, "level {level}: {opaque}");
        }
    }

    #[test]
    fn blended_and_opaque_mips_are_left_averaged() {
        let image = RgbaImage::from_fn(64, 64, |x, _| {
            Rgba([10, 10, 10, if x % 2 == 0 { 255 } else { 128 }])
        });
        let (layout, pages) = page_mips(image);
        let alphas = region_alphas(&layout, &pages[0].mips[1], 1);
        assert!(alphas.iter().all(|&a| a > 128 && a < 255));
    }
}
