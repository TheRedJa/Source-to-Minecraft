//! The map's baked light, as the mod draws it (format.md section 22): vrad's
//! lightmaps packed into pages, its ambient light samples with the BSP tree
//! that finds a point's leaf, and each static prop's per-vertex light.
//!
//! Source lights what the map compiler saw ahead of time: a brush face or a
//! displacement by its lightmap, a static prop by the colours vrad wrote into
//! its `.vhv`, and anything that moves by the ambient cube sampled in the leaf
//! it stands in. The mod draws the same.

use crate::bsp::lighting::{BakedLight, VertexLight};
use crate::output::surface::LightRegion;
use crate::voxel::surface::FaceLight;
use anyhow::{Result, ensure};
use std::collections::{BTreeSet, HashMap};

pub const MAGIC: [u8; 8] = *b"S2LITE\0\0";
pub const VERSION: u32 = 1;
/// The largest page side, in luxels.
pub const MAX_PAGE: usize = 4096;
/// Luxels repeated around each lightmap, so filtering never reads a neighbour.
const BORDER: usize = 1;

/// One page of lightmaps: `ColorRGBExp32` luxels, row by row.
#[derive(Debug, Clone)]
pub struct Page {
    pub width: usize,
    pub height: usize,
    pub luxels: Vec<[u8; 4]>,
}

/// Every lightmap a map's drawn faces wear, packed shelf by shelf.
#[derive(Debug, Clone, Default)]
pub struct Atlas {
    pub pages: Vec<Page>,
    /// Per face, its page, the page position of its first luxel, and for a
    /// bump-mapped face the luxels from one of its lightmaps to the next.
    placed: HashMap<usize, (u32, usize, usize, usize)>,
}

impl Atlas {
    /// Packs the static lightmap of each of `faces` that has one. Tallest
    /// first, so shelves fill evenly; ties by size, then face, so the pages are
    /// the same for the same map. A bump-mapped face's three bump lightmaps
    /// sit to the right of its flat one, each with its own border, as Source's
    /// lightmap pages lay them out.
    pub fn build(light: &BakedLight, faces: &BTreeSet<usize>) -> Result<Atlas> {
        struct Packed<'a> {
            width: usize,
            height: usize,
            maps: Vec<&'a [u8]>,
        }
        let mut maps: Vec<(usize, Packed)> = faces
            .iter()
            .filter_map(|&face| {
                let flat = light.raw_lightmap(face)?;
                let mut all = vec![flat.luxels];
                if let Some(bump) = light.raw_bump_lightmaps(face) {
                    all.extend(bump);
                }
                Some((
                    face,
                    Packed {
                        width: flat.width,
                        height: flat.height,
                        maps: all,
                    },
                ))
            })
            .collect();
        maps.sort_by(|(fa, a), (fb, b)| {
            b.height
                .cmp(&a.height)
                .then(b.width.cmp(&a.width))
                .then(fa.cmp(fb))
        });
        let area: usize = maps
            .iter()
            .map(|(_, m)| m.maps.len() * (m.width + 2 * BORDER) * (m.height + 2 * BORDER))
            .sum();
        // Square-ish pages, never narrower than the widest lightmap.
        let widest = maps
            .iter()
            .map(|(_, m)| m.maps.len() * (m.width + 2 * BORDER))
            .max()
            .unwrap_or(1);
        let width = ((area as f64 * 1.1).sqrt().ceil() as usize)
            .next_power_of_two()
            .clamp(widest.next_power_of_two().max(64), MAX_PAGE);
        let mut atlas = Atlas::default();
        let (mut x, mut y, mut shelf) = (0usize, 0usize, 0usize);
        for (face, map) in maps {
            let stride = map.width + 2 * BORDER;
            let (w, h) = (map.maps.len() * stride, map.height + 2 * BORDER);
            ensure!(
                w <= MAX_PAGE && h <= MAX_PAGE,
                "lightmap of face {face} exceeds a page"
            );
            if x + w > width {
                y += shelf;
                x = 0;
                shelf = 0;
            }
            if atlas.pages.is_empty() || y + h > MAX_PAGE {
                atlas.pages.push(Page {
                    width,
                    height: 0,
                    luxels: Vec::new(),
                });
                x = 0;
                y = 0;
                shelf = 0;
            }
            let page_index = atlas.pages.len() - 1;
            let page = &mut atlas.pages[page_index];
            if y + h > page.height {
                page.height = y + h;
                page.luxels.resize(width * page.height, [0; 4]);
            }
            for (index, source) in map.maps.iter().enumerate() {
                let luxels = source.as_chunks::<4>().0;
                for row in 0..h {
                    for column in 0..stride {
                        let sx = column.saturating_sub(BORDER).min(map.width - 1);
                        let sy = row.saturating_sub(BORDER).min(map.height - 1);
                        page.luxels[(y + row) * width + x + index * stride + column] =
                            luxels[sy * map.width + sx];
                    }
                }
            }
            let bump = if map.maps.len() > 1 { stride } else { 0 };
            atlas
                .placed
                .insert(face, (page_index as u32, x + BORDER, y + BORDER, bump));
            x += w;
            shelf = shelf.max(h);
        }
        Ok(atlas)
    }

    /// Where `light` lands on the pages: its luxel map moved to its face's
    /// place and divided by the page size. `None` for a face not packed.
    pub fn region(&self, light: &FaceLight) -> Option<LightRegion> {
        let &(page, x, y, bump) = self.placed.get(&light.face)?;
        let size = &self.pages[page as usize];
        let (w, h) = (size.width as f64, size.height as f64);
        let mut st = light.luxel;
        for value in &mut st.u[..3] {
            *value /= w;
        }
        st.u[3] = (st.u[3] + x as f64) / w;
        for value in &mut st.v[..3] {
            *value /= h;
        }
        st.v[3] = (st.v[3] + y as f64) / h;
        Some(LightRegion {
            page,
            st,
            bump: bump as f64 / w,
        })
    }
}

/// The world's leaves and their ambient samples, with the tree that finds a
/// point's leaf, in map-local block space.
#[derive(Debug, Clone, Default)]
pub struct AmbientExport {
    pub root: i32,
    /// Plane `normal · point = distance`, then the children: a node index, or
    /// `-1 - leaf` for a leaf.
    pub nodes: Vec<([f32; 3], f32, [i32; 2])>,
    /// Per leaf in BSP order, its first sample and count.
    pub leaves: Vec<(u32, u32)>,
    /// Position, then the light from +X, -X, +Y, -Y, +Z, -Z in Minecraft axes.
    pub samples: Vec<([f32; 3], [[f32; 3]; 6])>,
}

/// The world model's tree and every leaf's ambient samples; `None` without a
/// usable tree or any sample.
pub fn ambient(
    map: &crate::bsp::Map,
    transform: &crate::voxel::transform::Transform,
) -> Option<AmbientExport> {
    let leaves = map.raw_leaves();
    if map.ambient.samples.is_empty() || map.ambient.leaves.len() != leaves.len() {
        return None;
    }
    let root = map.bsp.models.first()?.head_node;
    let node_count = map.bsp.nodes.len();
    if root < 0 || root as usize >= node_count {
        return None;
    }
    let mut nodes = Vec::with_capacity(node_count);
    for node in &map.bsp.nodes {
        let plane = map.bsp.planes.get(node.plane_index as usize)?;
        let mapped = transform.transform_plane(crate::geom::Plane::new(
            crate::geom::Vec3::new(
                plane.normal.x as f64,
                plane.normal.y as f64,
                plane.normal.z as f64,
            ),
            plane.dist as f64,
        ));
        let normal = [
            mapped.normal.x as f32,
            mapped.normal.y as f32,
            mapped.normal.z as f32,
        ];
        let distance = mapped.dist as f32;
        if !normal.iter().all(|v| v.is_finite()) || !distance.is_finite() {
            return None;
        }
        for &child in &node.children {
            let valid = if child >= 0 {
                (child as usize) < node_count
            } else {
                ((-1 - i64::from(child)) as usize) < leaves.len()
            };
            if !valid {
                return None;
            }
        }
        nodes.push((normal, distance, node.children));
    }
    // Source's +X, -X, +Y, -Y, +Z, -Z, each to the Minecraft slot its
    // direction lands in.
    let slots: [usize; 6] = std::array::from_fn(|side| {
        let mut direction = [0.0; 3];
        direction[side / 2] = if side % 2 == 0 { 1.0 } else { -1.0 };
        let d = transform.transform_direction(crate::geom::Vec3::new(
            direction[0],
            direction[1],
            direction[2],
        ));
        let values = [d.x, d.y, d.z];
        let axis = (0..3)
            .max_by(|&a, &b| values[a].abs().total_cmp(&values[b].abs()))
            .unwrap_or(0);
        axis * 2 + usize::from(values[axis] < 0.0)
    });
    let samples = map
        .ambient
        .samples
        .iter()
        .map(|sample| {
            let p = transform.to_block_space(crate::geom::Vec3::new(
                sample.position[0],
                sample.position[1],
                sample.position[2],
            ));
            let mut cube = [[0.0f32; 3]; 6];
            for (side, &slot) in slots.iter().enumerate() {
                cube[slot] = sample.cube[side];
            }
            ([p.x as f32, p.y as f32, p.z as f32], cube)
        })
        .collect();
    Some(AmbientExport {
        root,
        nodes,
        leaves: map.ambient.leaves.clone(),
        samples,
    })
}

/// A static prop's vertex light, one colour per vertex of its mesh, as the
/// `.vhv` stores it (gamma space, half range); `None` when the prop has no
/// `.vhv` or the mesh has a vertex the `.vhv` does not light.
pub fn prop_vertex_light(
    map: &crate::bsp::Map,
    static_index: Option<usize>,
    hardware: &[u32],
) -> Option<Vec<[u8; 3]>> {
    let index = static_index?;
    if hardware.is_empty() {
        return None;
    }
    let prefix = if map.light.hdr { "sp_hdr" } else { "sp" };
    let data = map
        .bsp
        .pack
        .get(&format!("{prefix}_{index}.vhv"))
        .ok()
        .flatten()?;
    let colors = VertexLight::parse_raw(&data)?;
    hardware
        .iter()
        .map(|&h| colors.get(h as usize).copied())
        .collect()
}

/// The light file. `props` holds each prop of the placement table, in its
/// order, with its vertex light or `None`.
pub fn encode(
    atlas: &Atlas,
    ambient: Option<&AmbientExport>,
    props: &[Option<&[[u8; 3]]>],
) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&u32::try_from(atlas.pages.len())?.to_le_bytes());
    for page in &atlas.pages {
        out.extend_from_slice(&u32::try_from(page.width)?.to_le_bytes());
        out.extend_from_slice(&u32::try_from(page.height)?.to_le_bytes());
        out.extend_from_slice(page.luxels.as_flattened());
    }
    let empty = AmbientExport::default();
    let ambient = ambient.unwrap_or(&empty);
    out.extend_from_slice(&u32::try_from(ambient.nodes.len())?.to_le_bytes());
    out.extend_from_slice(&ambient.root.to_le_bytes());
    for (normal, distance, children) in &ambient.nodes {
        for value in normal.iter().chain(std::iter::once(distance)) {
            out.extend_from_slice(&canonical(*value).to_le_bytes());
        }
        for child in children {
            out.extend_from_slice(&child.to_le_bytes());
        }
    }
    out.extend_from_slice(&u32::try_from(ambient.leaves.len())?.to_le_bytes());
    for (first, count) in &ambient.leaves {
        out.extend_from_slice(&first.to_le_bytes());
        out.extend_from_slice(&count.to_le_bytes());
    }
    out.extend_from_slice(&u32::try_from(ambient.samples.len())?.to_le_bytes());
    for (position, cube) in &ambient.samples {
        for value in position.iter().chain(cube.as_flattened()) {
            ensure!(value.is_finite(), "non-finite ambient sample");
            out.extend_from_slice(&canonical(*value).to_le_bytes());
        }
    }
    out.extend_from_slice(&u32::try_from(props.len())?.to_le_bytes());
    for colors in props {
        let colors = colors.unwrap_or(&[]);
        out.extend_from_slice(&u32::try_from(colors.len())?.to_le_bytes());
        for color in colors {
            out.extend_from_slice(&[color[0], color[1], color[2], 0]);
        }
    }
    Ok(out)
}

fn canonical(value: f32) -> f32 {
    if value == 0.0 { 0.0 } else { value }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bsp::texcoord::BlockTexCoord;

    #[test]
    fn a_region_moves_luxels_to_the_face_place_on_its_page() {
        let mut atlas = Atlas::default();
        atlas.pages.push(Page {
            width: 64,
            height: 32,
            luxels: vec![[0; 4]; 64 * 32],
        });
        atlas.placed.insert(7, (0, 10, 4, 6));
        let light = FaceLight {
            face: 7,
            luxel: BlockTexCoord {
                u: [2.0, 0.0, 0.0, 0.5],
                v: [0.0, 0.0, 2.0, 0.5],
            },
        };
        let region = atlas.region(&light).unwrap();
        // The first luxel's centre: page pixel 10 + 0.5 across, 4 + 0.5 down.
        assert!((region.st.u[3] - 10.5 / 64.0).abs() < 1e-12);
        assert!((region.st.v[3] - 4.5 / 32.0).abs() < 1e-12);
        assert!((region.st.u[0] - 2.0 / 64.0).abs() < 1e-12);
        // Bump lightmaps one flat lightmap plus its border further right each.
        assert!((region.bump - 6.0 / 64.0).abs() < 1e-12);
        assert!(atlas.region(&FaceLight { face: 8, ..light }).is_none());
    }
}
