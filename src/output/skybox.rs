//! The map's 3D skybox (format section 21): the room the `sky_camera` stands
//! in, drawn as Source draws it behind the world.
//!
//! The engine renders that room from `sky_camera.origin + eye / scale` with
//! the player's view angles (`CSkyboxView::DrawInternal`), so it shows as if
//! `scale` times larger and centred on the map. Here it is exported as
//! triangles in block units relative to the camera, lit as Source lights it:
//! brush faces and displacements by their lightmaps, static props by the
//! per-vertex light vrad baked into their `.vhv` files. The room is not part
//! of the map the player walks in, so Minecraft's light never reaches it.

use crate::bsp::Map;
use crate::bsp::lighting::{Lightmap, VertexLight};
use crate::bsp::skybox::Skybox;
use crate::geom::{Aabb, Vec3};
use crate::output::metadata::{Diagnostic, Severity};
use crate::voxel::transform::Transform;
use anyhow::{Result, ensure};
use std::collections::{BTreeMap, BTreeSet};

pub const MAGIC: [u8; 8] = *b"S2BOX\0\0\0";
pub const VERSION: u32 = 1;
/// The lightmap page is this wide; it grows downwards as faces need.
const PAGE_WIDTH: usize = 1024;
const MAX_PAGE_HEIGHT: usize = 4096;
/// Linear light is stored as `u16` of `value * LIGHT_SCALE`, so up to 16.
pub const LIGHT_SCALE: f32 = 4096.0;

const SURF_SKY2D: u32 = 0x2;
const SURF_SKY: u32 = 0x4;
const SURF_TRIGGER: u32 = 0x40;
const SURF_NODRAW: u32 = 0x80;
const SURF_HINT: u32 = 0x100;
const SURF_SKIP: u32 = 0x200;
const LEAF_FLAGS_SKY: i16 = 0x1;

/// One vertex: camera-relative block position, texture coordinates in
/// repeats of the texture, lightmap page coordinates (0 to 1), and linear
/// vertex light.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Vertex {
    pub position: [f32; 3],
    pub uv: [f32; 2],
    pub lightmap: [f32; 2],
    pub light: [f32; 3],
}

/// How a batch is lit.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Lighting {
    /// By the lightmap page.
    Lightmap = 0,
    /// By each vertex's light.
    Vertex = 1,
}

#[derive(Debug, Clone)]
pub struct Batch {
    /// Material path, resolved to the map's material table when written.
    pub material: String,
    pub lighting: Lighting,
    /// Prop tint, 0 to 255; white for brushes.
    pub tint: [u8; 3],
    /// Triangles, three vertices each, counter-clockwise from the side seen.
    pub vertices: Vec<Vertex>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Fog {
    /// 0 to 255, as the keyvalue writes it.
    pub color: [u8; 3],
    /// World units, as Source keeps them; the skybox view divides by `scale`.
    pub start: f32,
    pub end: f32,
    pub max_density: f32,
}

pub struct SkyboxExport {
    pub scale: f32,
    /// Map-local block coordinates of the `sky_camera`.
    pub camera: [f64; 3],
    /// Map-local block coordinates of Source's world origin.
    pub world_origin: [f64; 3],
    pub fog: Option<Fog>,
    /// Per PVS cluster, whether its leaves see the 3D sky
    /// (`LEAF_FLAGS_SKY`); empty when the map has no visibility.
    pub clusters: Vec<bool>,
    pub page_width: usize,
    pub page_height: usize,
    /// Linear light, row by row.
    pub page: Vec<[f32; 3]>,
    pub batches: Vec<Batch>,
    pub diagnostics: Vec<Diagnostic>,
}

impl SkyboxExport {
    /// Every material the room draws, for the atlas.
    pub fn materials(&self) -> BTreeSet<String> {
        self.batches.iter().map(|b| b.material.clone()).collect()
    }

    /// Blocks of the room one repeat of each material's texture spans, the
    /// median over its triangles: the detail a prop of the room's size would
    /// ask of the atlas. The room is seen enlarged by `scale` but from far
    /// away, and asking the enlarged size's detail filled most of an atlas
    /// page for furnace's room alone.
    pub fn texture_spans(&self) -> BTreeMap<String, f64> {
        let mut rates: BTreeMap<&str, Vec<f64>> = BTreeMap::new();
        for batch in &self.batches {
            let list = rates.entry(batch.material.as_str()).or_default();
            for triangle in batch.vertices.as_chunks::<3>().0 {
                let [a, b, c] = [0, 1, 2].map(|i| triangle[i].position.map(f64::from));
                let ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
                let ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
                let cross = [
                    ab[1] * ac[2] - ab[2] * ac[1],
                    ab[2] * ac[0] - ab[0] * ac[2],
                    ab[0] * ac[1] - ab[1] * ac[0],
                ];
                let area = (cross[0] * cross[0] + cross[1] * cross[1] + cross[2] * cross[2]).sqrt();
                let [u, v, w] = [0, 1, 2].map(|i| triangle[i].uv.map(f64::from));
                let sheet = ((v[0] - u[0]) * (w[1] - u[1]) - (v[1] - u[1]) * (w[0] - u[0])).abs();
                if area > 1e-9 && sheet > 1e-12 {
                    list.push((area / sheet).sqrt());
                }
            }
        }
        rates
            .into_iter()
            .filter_map(|(material, mut spans)| {
                spans.sort_by(f64::total_cmp);
                let median = *spans.get(spans.len() / 2)?;
                median.is_finite().then(|| (material.to_string(), median))
            })
            .collect()
    }
}

/// The 3D skybox of `map`; `None` without a `sky_camera` room left out of
/// the conversion.
pub fn build(
    map: &Map,
    vfs: &crate::source::vfs::Vfs,
    transform: &Transform,
    room: Option<&Skybox>,
    clusters: Option<usize>,
) -> Result<Option<SkyboxExport>> {
    let Some(room) = room else {
        return Ok(None);
    };
    let Some(camera) = map.bsp.entities.iter().find(|e| {
        e.properties().any(|(k, v)| {
            k.eq_ignore_ascii_case("classname") && v.eq_ignore_ascii_case("sky_camera")
        })
    }) else {
        return Ok(None);
    };
    let value = |key: &str| {
        camera
            .properties()
            .find(|(k, _)| k.eq_ignore_ascii_case(key))
            .map(|(_, v)| v.trim().to_string())
    };
    let number = |key: &str, default: f32| {
        value(key)
            .and_then(|v| {
                v.split_whitespace()
                    .next()
                    .and_then(|n| n.parse::<f32>().ok())
            })
            .unwrap_or(default)
    };
    // `CSkyCamera`: `scale` 16 unless set; a skybox scale of 0 draws unscaled.
    let scale = number("scale", 16.0);
    let scale = if scale > 0.0 { scale } else { 1.0 };
    let origin = room.camera;
    let fog = (number("fogenable", 0.0) != 0.0).then(|| Fog {
        color: value("fogcolor")
            .map(|v| {
                let parts: Vec<u8> = v
                    .split_whitespace()
                    .filter_map(|n| n.parse::<f32>().ok())
                    .map(|n| n.clamp(0.0, 255.0) as u8)
                    .collect();
                [
                    parts.first().copied().unwrap_or(255),
                    parts.get(1).copied().unwrap_or(255),
                    parts.get(2).copied().unwrap_or(255),
                ]
            })
            .unwrap_or([255; 3]),
        start: number("fogstart", 0.0),
        end: number("fogend", 0.0),
        max_density: number("fogmaxdensity", 1.0),
    });
    let mut diagnostics = Vec::new();
    if value("use_angles").is_some_and(|v| v != "0")
        && value("angles").is_some_and(|a| {
            a.split_whitespace()
                .any(|n| n.parse::<f32>().is_ok_and(|n| n != 0.0))
        })
    {
        diagnostics.push(Diagnostic {
            severity: Severity::Warning,
            code: "SKYBOX_ANGLES_IGNORED".into(),
            message: "the sky_camera turns the 3D skybox, which is not done".into(),
            context: BTreeMap::new(),
        });
    }

    let local = |p: Vec3| -> [f32; 3] {
        let b = transform.to_block_space(p) - transform.to_block_space(origin);
        [b.x as f32, b.y as f32, b.z as f32]
    };
    let mut builder = Builder::new();
    let mut brush_faces = 0usize;

    // Brush entities wholly in the room are part of it, where they stand.
    let mut models: Vec<(usize, Vec3)> = vec![(0, Vec3::ZERO)];
    for record in crate::bsp::entities::extract(map, transform) {
        let Some(model) = record
            .brush_model
            .filter(|&m| m > 0 && m < map.bsp.models.len())
        else {
            continue;
        };
        let offset = record
            .origin_source
            .map_or(Vec3::ZERO, |[x, y, z]| Vec3::new(x, y, z));
        let bsp_model = &map.bsp.models[model];
        let bounds = Aabb::new(
            Vec3::from(bsp_model.mins) + offset,
            Vec3::from(bsp_model.maxs) + offset,
        );
        if room.contains(&bounds) {
            models.push((model, offset));
        }
    }
    for (model, offset) in models {
        let Some(bsp_model) = map.bsp.models.get(model) else {
            continue;
        };
        let first = usize::try_from(bsp_model.first_face).unwrap_or(0);
        let count = usize::try_from(bsp_model.face_count).unwrap_or(0);
        for face_index in first..first.saturating_add(count) {
            let Some(face) = map.bsp.faces.get(face_index) else {
                break;
            };
            let Some(info) = usize::try_from(face.texture_info)
                .ok()
                .and_then(|i| map.bsp.textures_info.get(i))
            else {
                continue;
            };
            let flags = info.flags.bits();
            if flags & (SURF_NODRAW | SURF_SKIP | SURF_HINT | SURF_TRIGGER | SURF_SKY | SURF_SKY2D)
                != 0
            {
                continue;
            }
            let Some(material) = map.material_index(face.texture_info as usize) else {
                continue;
            };
            let points: Vec<Vec3> = vbsp::Handle::new(&map.bsp, face)
                .vertices()
                .map(|v| Vec3::from(v.position) + offset)
                .collect();
            if points.len() < 3 {
                continue;
            }
            let bounds = points
                .iter()
                .skip(1)
                .fold(Aabb::new(points[0], points[0]), |b, &p| {
                    b.union(&Aabb::new(p, p))
                });
            if !room.contains(&bounds) {
                continue;
            }
            let Some(plane) = map.bsp.planes.get(face.plane_num as usize) else {
                continue;
            };
            let size = texture_size(map, info.texture_data_index);
            let lightmap = map.light.lightmap(face_index).map(|l| builder.place(l));
            let light = map.light.face(face_index);
            let name = map.materials()[material].name.clone();
            let vertices: Vec<Vertex> = points
                .iter()
                .map(|&p| {
                    // Texture and light coordinates are of the brush where it was compiled.
                    let q = p - offset;
                    let s = &info.texture_transforms_u;
                    let t = &info.texture_transforms_v;
                    let uv = [
                        ((q.x * s[0] as f64 + q.y * s[1] as f64 + q.z * s[2] as f64 + s[3] as f64)
                            / size[0]) as f32,
                        ((q.x * t[0] as f64 + q.y * t[1] as f64 + q.z * t[2] as f64 + t[3] as f64)
                            / size[1]) as f32,
                    ];
                    let lm = match (lightmap, light) {
                        (Some(placed), Some(light)) => {
                            let ls = &info.light_map_scale;
                            let lt = &info.light_map_transform;
                            let luxel = [
                                q.x * ls[0] as f64
                                    + q.y * ls[1] as f64
                                    + q.z * ls[2] as f64
                                    + ls[3] as f64
                                    + 0.5
                                    - light.mins[0] as f64,
                                q.x * lt[0] as f64
                                    + q.y * lt[1] as f64
                                    + q.z * lt[2] as f64
                                    + lt[3] as f64
                                    + 0.5
                                    - light.mins[1] as f64,
                            ];
                            builder.page_uv(placed, luxel)
                        }
                        _ => builder.white(),
                    };
                    Vertex {
                        position: local(p),
                        uv,
                        lightmap: lm,
                        light: [1.0; 3],
                    }
                })
                .collect();
            let facing = transform.transform_direction(Vec3::from(plane.normal));
            builder.polygon(&name, Lighting::Lightmap, [255; 3], vertices, facing);
            brush_faces += 1;
        }
    }

    // Displacements, built as Source builds them (`buildDisplacement` in
    // noclip.website follows the same steps): the base face's corners from the
    // one nearest the start position, rows along corner 0 to 1, each row from
    // that edge to the 3 to 2 edge, texture coordinates before displacing, and
    // the lightmap spread evenly over the grid.
    let mut displacements = 0usize;
    for (index, disp) in map.bsp.displacements.iter().enumerate() {
        let Some(face) = map.bsp.faces.get(disp.map_face as usize) else {
            continue;
        };
        let face_index = disp.map_face as usize;
        let Some(info) = usize::try_from(face.texture_info)
            .ok()
            .and_then(|i| map.bsp.textures_info.get(i))
        else {
            continue;
        };
        if info.flags.bits() & (SURF_NODRAW | SURF_SKY | SURF_SKY2D) != 0 {
            continue;
        }
        let Some(material) = map.material_index(face.texture_info as usize) else {
            continue;
        };
        let corners: Vec<Vec3> = vbsp::Handle::new(&map.bsp, face)
            .vertices()
            .map(|v| Vec3::from(v.position))
            .collect();
        if corners.len() != 4 {
            continue;
        }
        let start = Vec3::from(disp.start_position);
        let first = (0..4)
            .min_by(|&a, &b| {
                (corners[a] - start)
                    .length()
                    .partial_cmp(&(corners[b] - start).length())
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .unwrap_or(0);
        let c: Vec<Vec3> = (0..4).map(|i| corners[(first + i) % 4]).collect();
        let side = (1usize << disp.power) + 1;
        let start_vertex = usize::try_from(disp.displacement_vertex_start).unwrap_or(0);
        let mut grid = Vec::with_capacity(side * side);
        let mut bounds = Aabb::empty();
        for y in 0..side {
            let ty = y as f64 / (side - 1) as f64;
            let v0 = c[0] + (c[1] - c[0]) * ty;
            let v1 = c[3] + (c[2] - c[3]) * ty;
            for x in 0..side {
                let tx = x as f64 / (side - 1) as f64;
                let base = v0 + (v1 - v0) * tx;
                let Some(dv) = map
                    .bsp
                    .displacement_vertices
                    .get(start_vertex + y * side + x)
                else {
                    continue;
                };
                let p = base + Vec3::from(dv.vector) * dv.distance as f64;
                bounds.extend(p);
                grid.push((p, base, tx, ty));
            }
        }
        if grid.len() != side * side || !room.contains(&bounds) {
            continue;
        }
        let size = texture_size(map, info.texture_data_index);
        let light = map.light.face(face_index);
        let lightmap = map.light.lightmap(face_index).map(|l| builder.place(l));
        let name = map.materials()[material].name.clone();
        let vertex = |(p, base, tx, ty): (Vec3, Vec3, f64, f64), builder: &Builder| {
            let s = &info.texture_transforms_u;
            let t = &info.texture_transforms_v;
            let uv = [
                ((base.x * s[0] as f64 + base.y * s[1] as f64 + base.z * s[2] as f64 + s[3] as f64)
                    / size[0]) as f32,
                ((base.x * t[0] as f64 + base.y * t[1] as f64 + base.z * t[2] as f64 + t[3] as f64)
                    / size[1]) as f32,
            ];
            let lm = match (lightmap, light) {
                (Some(placed), Some(light)) => builder.page_uv(
                    placed,
                    [
                        tx * light.size[0] as f64 + 0.5,
                        ty * light.size[1] as f64 + 0.5,
                    ],
                ),
                _ => builder.white(),
            };
            Vertex {
                position: local(p),
                uv,
                lightmap: lm,
                light: [1.0; 3],
            }
        };
        let mut triangles = Vec::new();
        for y in 0..side - 1 {
            for x in 0..side - 1 {
                let at = |x: usize, y: usize| grid[y * side + x];
                for [a, b, c] in [
                    [at(x, y), at(x, y + 1), at(x + 1, y + 1)],
                    [at(x, y), at(x + 1, y + 1), at(x + 1, y)],
                ] {
                    triangles.push([a, b, c]);
                }
            }
        }
        // The side the displacement is seen from: its base face's, turned
        // round when the whole surface winds the other way (an inverted one).
        let Some(plane) = map.bsp.planes.get(face.plane_num as usize) else {
            continue;
        };
        let base_normal = Vec3::from(plane.normal);
        let winding: f64 = triangles
            .iter()
            .map(|[a, b, c]| (b.0 - a.0).cross(c.0 - a.0).dot(base_normal))
            .sum();
        let seen = if winding < 0.0 {
            -base_normal
        } else {
            base_normal
        };
        let facing = transform.transform_direction(seen);
        let mut vertices = Vec::with_capacity(triangles.len() * 3);
        for [a, b, c] in triangles {
            vertices.extend([
                vertex(a, &builder),
                vertex(b, &builder),
                vertex(c, &builder),
            ]);
        }
        builder.triangles(&name, Lighting::Lightmap, [255; 3], vertices, facing);
        displacements += 1;
        let _ = index;
    }

    // Static props, lit by their `.vhv`.
    let mut models = crate::source::mdl::Models::new(vfs);
    let (mut props, mut unlit) = (0usize, 0usize);
    let prefix = if map.light.hdr { "sp_hdr" } else { "sp" };
    let statics = &map.static_props;
    for (lump_index, raw) in statics.props.iter().enumerate() {
        if raw.no_draw() {
            continue;
        }
        let origin_prop = Vec3::new(
            raw.origin[0] as f64,
            raw.origin[1] as f64,
            raw.origin[2] as f64,
        );
        if !room.contains_point(origin_prop) {
            continue;
        }
        let Some(path) = statics.models.get(raw.prop_type as usize) else {
            continue;
        };
        let path = path.replace('\\', "/").to_ascii_lowercase();
        let Some(model) = models.get_skin(&path, raw.skin) else {
            continue;
        };
        let prop = crate::bsp::props::Prop {
            model: path,
            origin: origin_prop,
            angles: raw.angles.map(f64::from),
            scale: if raw.scale > 0.0 {
                raw.scale as f64
            } else {
                1.0
            },
            classname: "prop_static".into(),
            solid: raw.solid,
            skin: raw.skin,
            color: raw.color,
            entity: None,
            static_index: Some(lump_index),
        };
        let vhv = map
            .bsp
            .pack
            .get(&format!("{prefix}_{lump_index}.vhv"))
            .ok()
            .flatten()
            .and_then(|data| VertexLight::parse(&data));
        if vhv.is_none() {
            unlit += 1;
        }
        for part in &model.parts {
            let mut vertices = Vec::with_capacity(part.triangles.len() * 3);
            for (t, tri) in part.triangles.iter().enumerate() {
                for (corner, &point) in tri.iter().enumerate() {
                    let light = vhv
                        .as_ref()
                        .and_then(|l| l.colors.get(part.hardware.get(t)?[corner] as usize))
                        .copied()
                        .unwrap_or([1.0; 3]);
                    vertices.push(Vertex {
                        position: local(prop.place(point)),
                        uv: part.uvs[t][corner].map(|v| v as f32),
                        lightmap: builder.white(),
                        light,
                    });
                }
            }
            builder.raw(
                &part.material,
                if vhv.is_some() {
                    Lighting::Vertex
                } else {
                    Lighting::Lightmap
                },
                raw.color,
                vertices,
            );
        }
        props += 1;
    }
    ensure!(
        builder.height <= MAX_PAGE_HEIGHT,
        "3D skybox lightmaps exceed one page"
    );

    let mut context = BTreeMap::new();
    context.insert("brush_faces".into(), brush_faces.to_string());
    context.insert("displacements".into(), displacements.to_string());
    context.insert("props".into(), props.to_string());
    context.insert("props_without_vertex_light".into(), unlit.to_string());
    context.insert("scale".into(), scale.to_string());
    context.insert(
        "lightmap_page".into(),
        format!("{}x{}", PAGE_WIDTH, builder.height),
    );
    diagnostics.push(Diagnostic {
        severity: Severity::Info,
        code: "SKYBOX_3D_SUMMARY".into(),
        message: "the 3D skybox room, drawn behind the sky faces with Source's baked light".into(),
        context,
    });

    let cluster_flags = clusters.map_or_else(Vec::new, |count| {
        let mut flags = vec![false; count];
        for leaf in &map.bsp.leaves {
            let leaf_flags = (leaf.area_and_flags >> 9) & 0x7F;
            if let Ok(cluster) = usize::try_from(leaf.cluster)
                && cluster < count
                && leaf_flags & LEAF_FLAGS_SKY != 0
            {
                flags[cluster] = true;
            }
        }
        flags
    });
    let to_array = |v: Vec3| [v.x, v.y, v.z];
    Ok(Some(SkyboxExport {
        scale,
        camera: to_array(transform.to_block_space(origin)),
        world_origin: to_array(transform.to_block_space(Vec3::ZERO)),
        fog,
        clusters: cluster_flags,
        page_width: PAGE_WIDTH,
        page_height: builder.height,
        page: builder.page,
        batches: builder.batches.into_values().collect(),
        diagnostics,
    }))
}

/// The texture size a face's texture coordinates are in texels of.
fn texture_size(map: &Map, texture_data: i32) -> [f64; 2] {
    usize::try_from(texture_data)
        .ok()
        .and_then(|i| map.bsp.textures_data.get(i))
        .map(|d| [f64::from(d.width.max(1)), f64::from(d.height.max(1))])
        .unwrap_or([1.0, 1.0])
}

/// Where a lightmap landed on the page.
#[derive(Debug, Clone, Copy)]
struct Placed {
    x: usize,
    y: usize,
}

/// Packs lightmaps into the page, shelf by shelf, and gathers batches.
struct Builder {
    page: Vec<[f32; 3]>,
    height: usize,
    shelf_x: usize,
    shelf_y: usize,
    shelf_height: usize,
    batches: BTreeMap<(String, Lighting, [u8; 3]), Batch>,
}

impl Builder {
    fn new() -> Builder {
        let mut builder = Builder {
            page: Vec::new(),
            height: 0,
            shelf_x: 0,
            shelf_y: 0,
            shelf_height: 0,
            batches: BTreeMap::new(),
        };
        // A white 2x2 at the corner for what has no lightmap: unlit materials,
        // and props lit by their vertices.
        builder.place(Lightmap {
            width: 2,
            height: 2,
            luxels: vec![[1.0; 3]; 4],
        });
        builder
    }

    fn place(&mut self, lightmap: Lightmap) -> Placed {
        // One luxel of space around each, so filtering never reads a neighbour.
        let (w, h) = (lightmap.width + 2, lightmap.height + 2);
        if self.shelf_x + w > PAGE_WIDTH {
            self.shelf_y += self.shelf_height;
            self.shelf_x = 0;
            self.shelf_height = 0;
        }
        let placed = Placed {
            x: self.shelf_x + 1,
            y: self.shelf_y + 1,
        };
        self.shelf_x += w;
        self.shelf_height = self.shelf_height.max(h);
        let needed = self.shelf_y + self.shelf_height;
        if needed > self.height {
            self.page.resize(needed * PAGE_WIDTH, [0.0; 3]);
            self.height = needed;
        }
        for y in 0..h {
            for x in 0..w {
                // The border repeats the edge.
                let sx = x.saturating_sub(1).min(lightmap.width - 1);
                let sy = y.saturating_sub(1).min(lightmap.height - 1);
                let value = lightmap.luxels[sy * lightmap.width + sx];
                self.page[(placed.y - 1 + y) * PAGE_WIDTH + placed.x - 1 + x] = value;
            }
        }
        placed
    }

    /// Page coordinates of a luxel coordinate of a placed lightmap. The page's
    /// final height is not known yet, so `v` is in luxels until written.
    fn page_uv(&self, placed: Placed, luxel: [f64; 2]) -> [f32; 2] {
        [
            ((placed.x as f64 + luxel[0]) / PAGE_WIDTH as f64) as f32,
            (placed.y as f64 + luxel[1]) as f32,
        ]
    }

    fn white(&self) -> [f32; 2] {
        self.page_uv(Placed { x: 1, y: 1 }, [1.0, 1.0])
    }

    /// A convex polygon, fanned, wound to face `facing`.
    fn polygon(
        &mut self,
        material: &str,
        lighting: Lighting,
        tint: [u8; 3],
        points: Vec<Vertex>,
        facing: Vec3,
    ) {
        let mut vertices = Vec::with_capacity((points.len() - 2) * 3);
        for i in 1..points.len() - 1 {
            vertices.extend([points[0], points[i], points[i + 1]]);
        }
        self.triangles(material, lighting, tint, vertices, facing);
    }

    /// Triangles, each turned to face `facing`.
    fn triangles(
        &mut self,
        material: &str,
        lighting: Lighting,
        tint: [u8; 3],
        mut vertices: Vec<Vertex>,
        facing: Vec3,
    ) {
        for tri in vertices.as_chunks_mut::<3>().0 {
            let p = |v: &Vertex| {
                Vec3::new(
                    v.position[0] as f64,
                    v.position[1] as f64,
                    v.position[2] as f64,
                )
            };
            let normal = (p(&tri[1]) - p(&tri[0])).cross(p(&tri[2]) - p(&tri[0]));
            if normal.dot(facing) < 0.0 {
                tri.swap(1, 2);
            }
        }
        self.raw(material, lighting, tint, vertices);
    }

    /// Triangles as they are wound.
    fn raw(&mut self, material: &str, lighting: Lighting, tint: [u8; 3], vertices: Vec<Vertex>) {
        self.batches
            .entry((material.to_string(), lighting, tint))
            .or_insert_with(|| Batch {
                material: material.to_string(),
                lighting,
                tint,
                vertices: Vec::new(),
            })
            .vertices
            .extend(vertices);
    }
}

/// Encodes the table; `material_ids` maps material paths to the map's
/// material table. Batches whose material has no entry are left out.
pub fn encode(sky: &SkyboxExport, material_ids: &BTreeMap<String, u32>) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&sky.scale.to_le_bytes());
    for value in sky.camera.iter().chain(&sky.world_origin) {
        ensure!(value.is_finite(), "non-finite skybox coordinate");
        out.extend_from_slice(&canonical64(*value).to_le_bytes());
    }
    match &sky.fog {
        Some(fog) => {
            out.push(1);
            out.extend_from_slice(&fog.color);
            for value in [fog.start, fog.end, fog.max_density] {
                out.extend_from_slice(&canonical32(value).to_le_bytes());
            }
        }
        None => out.push(0),
    }
    out.extend_from_slice(&(sky.clusters.len() as u32).to_le_bytes());
    let mut bits = vec![0u8; sky.clusters.len().div_ceil(8)];
    for (i, &visible) in sky.clusters.iter().enumerate() {
        if visible {
            bits[i / 8] |= 1 << (i % 8);
        }
    }
    out.extend_from_slice(&bits);
    out.extend_from_slice(&(sky.page_width as u32).to_le_bytes());
    out.extend_from_slice(&(sky.page_height as u32).to_le_bytes());
    for luxel in &sky.page {
        for channel in luxel {
            let value = (channel.max(0.0) * LIGHT_SCALE).round().min(65535.0) as u16;
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    let height = sky.page_height.max(1) as f32;
    let batches: Vec<(&Batch, u32)> = sky
        .batches
        .iter()
        .filter_map(|b| material_ids.get(&b.material).map(|&id| (b, id)))
        .collect();
    out.extend_from_slice(&(batches.len() as u32).to_le_bytes());
    for (batch, id) in batches {
        ensure!(
            batch.vertices.len() % 3 == 0,
            "a skybox batch is not whole triangles"
        );
        out.extend_from_slice(&id.to_le_bytes());
        out.push(batch.lighting as u8);
        out.extend_from_slice(&batch.tint);
        out.extend_from_slice(&(batch.vertices.len() as u32).to_le_bytes());
        for vertex in &batch.vertices {
            let lightmap = [vertex.lightmap[0], vertex.lightmap[1] / height];
            for value in vertex
                .position
                .iter()
                .chain(&vertex.uv)
                .chain(&lightmap)
                .chain(&vertex.light)
            {
                ensure!(value.is_finite(), "non-finite skybox vertex");
                out.extend_from_slice(&canonical32(*value).to_le_bytes());
            }
        }
    }
    Ok(out)
}

fn canonical32(value: f32) -> f32 {
    if value == 0.0 { 0.0 } else { value }
}

fn canonical64(value: f64) -> f64 {
    if value == 0.0 { 0.0 } else { value }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_texture_span_is_the_room_blocks_one_repeat_covers() {
        let vertex = |position: [f32; 3], uv: [f32; 2]| Vertex {
            position,
            uv,
            lightmap: [0.0; 2],
            light: [1.0; 3],
        };
        let sky = SkyboxExport {
            scale: 16.0,
            camera: [0.0; 3],
            world_origin: [0.0; 3],
            fog: None,
            clusters: Vec::new(),
            page_width: 0,
            page_height: 0,
            page: Vec::new(),
            batches: vec![Batch {
                material: "nature/snow".into(),
                lighting: Lighting::Lightmap,
                tint: [255; 3],
                // Two blocks each way for half a repeat each way: four blocks a repeat.
                vertices: vec![
                    vertex([0.0, 0.0, 0.0], [0.0, 0.0]),
                    vertex([2.0, 0.0, 0.0], [0.5, 0.0]),
                    vertex([0.0, 0.0, 2.0], [0.0, 0.5]),
                ],
            }],
            diagnostics: Vec::new(),
        };
        let spans = sky.texture_spans();
        assert!((spans["nature/snow"] - 4.0).abs() < 1e-9);
    }

    #[test]
    fn lightmaps_pack_with_a_repeated_border() {
        let mut builder = Builder::new();
        let placed = builder.place(Lightmap {
            width: 2,
            height: 1,
            luxels: vec![[0.25; 3], [0.5; 3]],
        });
        // The border copies the edge luxels.
        let row = (placed.y - 1) * PAGE_WIDTH;
        assert_eq!(builder.page[row + placed.x - 1], [0.25; 3]);
        assert_eq!(builder.page[row + placed.x + 2], [0.5; 3]);
        assert_eq!(
            builder.page[(placed.y) * PAGE_WIDTH + placed.x + 1],
            [0.5; 3]
        );
    }

    #[test]
    fn triangles_turn_to_face_their_side() {
        let mut builder = Builder::new();
        let v = |x: f32, y: f32| Vertex {
            position: [x, y, 0.0],
            uv: [0.0; 2],
            lightmap: [0.0; 2],
            light: [1.0; 3],
        };
        builder.triangles(
            "m",
            Lighting::Lightmap,
            [255; 3],
            vec![v(0.0, 0.0), v(0.0, 1.0), v(1.0, 0.0)],
            Vec3::new(0.0, 0.0, 1.0),
        );
        let tri = &builder.batches.values().next().unwrap().vertices;
        let normal = (tri[1].position[0] - tri[0].position[0])
            * (tri[2].position[1] - tri[0].position[1])
            - (tri[1].position[1] - tri[0].position[1]) * (tri[2].position[0] - tri[0].position[0]);
        assert!(normal > 0.0);
    }
}
