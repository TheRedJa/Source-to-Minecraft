//! Exact surfaces: the map's visible faces cut to cells, and thin brushes
//! kept as meshes.

use super::*;

/// Brushes filed by model and position, to find the one a drawn face came from.
pub(super) struct SolidLookup<'a> {
    pub(super) solids: &'a [Solid],
    pub(super) buckets: std::collections::HashMap<(usize, [i64; 3]), Vec<u32>>,
}

impl<'a> SolidLookup<'a> {
    pub(super) fn new(solids: &'a [Solid]) -> Self {
        let mut buckets: std::collections::HashMap<(usize, [i64; 3]), Vec<u32>> =
            std::collections::HashMap::new();
        for (index, solid) in solids.iter().enumerate() {
            let min = Self::bucket(solid.bounds.min - Vec3::splat(FACE_MATCH_SLACK));
            let max = Self::bucket(solid.bounds.max + Vec3::splat(FACE_MATCH_SLACK));
            for x in min[0]..=max[0] {
                for y in min[1]..=max[1] {
                    for z in min[2]..=max[2] {
                        buckets
                            .entry((solid.model, [x, y, z]))
                            .or_default()
                            .push(index as u32);
                    }
                }
            }
        }
        SolidLookup { solids, buckets }
    }

    pub(super) fn bucket(p: Vec3) -> [i64; 3] {
        [
            (p.x / FACE_MATCH_BUCKET).floor() as i64,
            (p.y / FACE_MATCH_BUCKET).floor() as i64,
            (p.z / FACE_MATCH_BUCKET).floor() as i64,
        ]
    }

    /// The brush whose side a face with this centre and outward normal lies
    /// on, preferring one wearing the face's own texture info. Model-local
    /// Source space throughout.
    pub(super) fn find(
        &self,
        model: usize,
        centre: Vec3,
        normal: Vec3,
        texture_info: usize,
    ) -> Option<&'a Solid> {
        let candidates = self.buckets.get(&(model, Self::bucket(centre)))?;
        let mut best: Option<(bool, usize, &'a Solid)> = None;
        for &index in candidates {
            let solid = &self.solids[index as usize];
            if solid
                .sides
                .iter()
                .any(|side| side.plane.distance_to(centre) > FACE_MATCH_SLACK)
            {
                continue;
            }
            let Some(side) = solid.sides.iter().find(|side| {
                side.plane.normal.dot(normal) > 0.999
                    && side.plane.distance_to(centre).abs() <= FACE_MATCH_SLACK
            }) else {
                continue;
            };
            let same_texture = side.texture_info == Some(texture_info);
            let better = match best {
                None => true,
                Some((best_texture, best_brush, _)) => {
                    (same_texture, std::cmp::Reverse(solid.brush_index))
                        > (best_texture, std::cmp::Reverse(best_brush))
                }
            };
            if better {
                best = Some((same_texture, solid.brush_index, solid));
            }
        }
        best.map(|(_, _, solid)| solid)
    }
}

/// Every visible face of the converted world, as exact block-space polygons.
///
/// Brush faces come from the face lump — what the map compiler actually
/// draws, already clipped against every other brush — rather than from brush
/// sides, which would draw every hidden face between touching brushes too.
/// The lump does not say which brush a face came from, and whether a brush is
/// converted at all is decided per brush, so each face is traced back to its
/// brush by plane and position and takes that brush's decision. Terrain comes
/// from the displacement triangles, which is what the lump's base quad turns
/// into in game.
#[allow(clippy::too_many_arguments)]
pub(super) fn exact_polygons(
    map: &Map,
    config: &Config,
    resolver: &Resolver,
    transform: &Transform,
    origins: &std::collections::HashMap<usize, Vec3>,
    models: &[usize],
    solids: &[Solid],
    skybox: Option<&crate::bsp::skybox::Skybox>,
    displacements: &[crate::bsp::displacement::Surface],
) -> (Vec<crate::voxel::fragments::Polygon>, usize) {
    use crate::voxel::fragments::Polygon;
    use crate::voxel::surface::{FaceSource, SourceProvenance};
    use vbsp::TextureFlags as F;
    let skip_sky = config.contents.skip_sky;
    let lookup = SolidLookup::new(solids);
    let mut polygons = Vec::new();
    let mut unmatched = 0usize;

    for &model in models {
        let Some(bsp_model) = map.bsp.models.get(model) else {
            continue;
        };
        let origin = origins.get(&model).copied().unwrap_or(Vec3::ZERO);
        let first = usize::try_from(bsp_model.first_face).unwrap_or(0);
        let count = usize::try_from(bsp_model.face_count).unwrap_or(0);
        for face_index in first..first.saturating_add(count) {
            let Some(face) = map.bsp.faces.get(face_index) else {
                break;
            };
            if face.displacement_info >= 0 {
                continue;
            }
            let Ok(texture_info) = usize::try_from(face.texture_info) else {
                continue;
            };
            let Some(info) = map.bsp.textures_info.get(texture_info) else {
                continue;
            };
            if is_invisible(info.flags) || (skip_sky && info.flags.intersects(F::SKY | F::SKY2D)) {
                continue;
            }
            let Some(material) = map.material_index(texture_info) else {
                continue;
            };
            let Some(plane) = map.bsp.planes.get(face.plane_num as usize) else {
                continue;
            };
            // The compiler writes the face's own plane, already facing out of
            // the brush; `side` only repeats which half of the plane pair that
            // is, and flipping by it points every second face into its brush.
            let normal = Vec3::from(plane.normal);
            let points: Vec<Vec3> = vbsp::Handle::new(&map.bsp, face)
                .vertices()
                .map(|vertex| Vec3::from(vertex.position))
                .collect();
            if points.len() < 3 {
                continue;
            }
            let centre = points.iter().fold(Vec3::ZERO, |sum, &p| sum + p) / points.len() as f64;
            let decision = match lookup.find(model, centre, normal, texture_info) {
                // Left out with the 3D skybox, as the voxelizer leaves it out.
                // Brush-entity brushes are stored relative to the entity's
                // origin, so the room is tested where they really are.
                Some(solid)
                    if skybox.is_some_and(|room| {
                        room.contains(&Aabb::new(
                            solid.bounds.min + origin,
                            solid.bounds.max + origin,
                        ))
                    }) =>
                {
                    continue;
                }
                Some(solid) => resolver.decide(solid.flags),
                // The lump has a face no brush accounts for. Rare; drawn on its
                // material alone rather than lost.
                None => {
                    unmatched += 1;
                    Decision::ByMaterial
                }
            };
            if side_block(&decision, resolver, Some(material), info.flags, skip_sky).is_none() {
                continue;
            }
            let source_normal = normal;
            let uv = crate::bsp::texcoord::TexCoord::of(info).in_block_space(transform, origin);
            let whole = Polygon::new(
                FaceSource {
                    provenance: SourceProvenance::Face {
                        face: face_index,
                        piece: 0,
                    },
                    material,
                    uv,
                    light: face_light(map, face_index, info, transform, origin),
                    blend: None,
                },
                transform.transform_direction(source_normal),
                points
                    .iter()
                    .map(|&p| transform.to_block_space(p + origin))
                    .collect(),
            );
            for (piece, points) in whole.pieces().into_iter().enumerate() {
                let mut source = whole.source;
                source.provenance = SourceProvenance::Face {
                    face: face_index,
                    piece,
                };
                polygons.push(Polygon::new(source, whole.normal, points));
            }
        }
    }

    for surface in displacements {
        if skybox.is_some_and(|room| room.contains(&surface.bounds)) {
            continue;
        }
        let (Some(material), Some(texcoord)) = (surface.material, surface.texcoord) else {
            continue;
        };
        if resolver.block_for_material(material).is_none() {
            continue;
        }
        let uv = texcoord.in_block_space(transform, Vec3::ZERO);
        let triangles: Vec<[Vec3; 3]> = surface
            .triangles
            .iter()
            .map(|tri| [tri.a, tri.b, tri.c].map(|p| transform.to_block_space(p)))
            .collect();
        // Each triangle faces the way it winds, which is how Source culls it.
        // Terrain can fold, so a single triangle facing away from the surface
        // is real; the surface's own facing, inverted displacements included,
        // is settled in `displacement_surface`, and the winding agrees with it.
        let facing = transform.transform_direction(surface.normal);
        let winding: f64 = triangles
            .iter()
            .map(|[a, b, c]| (*b - *a).cross(*c - *a).dot(facing))
            .sum();
        for (triangle, points) in triangles.into_iter().enumerate() {
            let [a, b, c] = points;
            let mut normal = (b - a).cross(c - a);
            // Only a mirroring transform can turn the winding against the
            // facing here; the facing is what stays right through it.
            if winding < 0.0 {
                normal = -normal;
            }
            if normal.length() <= 1.0e-12 || !normal.is_finite() {
                continue;
            }
            // The base face's lightmap spreads evenly over the grid; each
            // triangle maps its corners' places on it, which is affine within it.
            let light = map.light.face(surface.face).and_then(|face_light| {
                map.light.raw_lightmap(surface.face)?;
                let corners = surface.grid.get(triangle)?.map(|[s, t]| {
                    [
                        s * f64::from(face_light.size[0]) + 0.5,
                        t * f64::from(face_light.size[1]) + 0.5,
                    ]
                });
                Some(crate::voxel::surface::FaceLight {
                    face: surface.face,
                    luxel: crate::bsp::texcoord::BlockTexCoord::fit([a, b, c], corners)?,
                })
            });
            // The blend alpha is linear over each triangle, as Source
            // interpolates it between the displacement's vertices.
            let blend = surface.alpha.get(triangle).and_then(|alpha| {
                let fit = crate::bsp::texcoord::BlockTexCoord::fit([a, b, c], alpha.map(|v| [v, 0.0]))?;
                Some(fit.u)
            });
            polygons.push(Polygon::new(
                FaceSource {
                    provenance: SourceProvenance::Displacement {
                        displacement: surface.index,
                        triangle,
                    },
                    material,
                    uv,
                    light,
                    blend,
                },
                normal.normalized(),
                vec![a, b, c],
            ));
        }
    }
    (polygons, unmatched)
}

/// What [`split_brush_meshes`] leaves: brushes still to voxelize, the drawn
/// thin ones, the cells those would have filled, and the thin ones' geometry
/// with their contents flags, for collision.
pub(super) type SplitBrushes = (
    Vec<Solid>,
    Vec<BrushMesh>,
    BTreeSet<IVec3>,
    Vec<(vbsp::BrushFlags, BlockSolid)>,
);

pub(super) fn split_brush_meshes(
    map: &Map,
    config: &Config,
    transform: &Transform,
    origins: &std::collections::HashMap<usize, Vec3>,
    solids: Vec<Solid>,
) -> SplitBrushes {
    if !config.output.brush_meshes.enabled {
        return (solids, Vec::new(), BTreeSet::new(), Vec::new());
    }
    let limit = config.output.brush_meshes.max_thickness_units / transform.units_per_block();
    let mut voxelized = Vec::with_capacity(solids.len());
    let mut meshes = Vec::new();
    let mut occluders = BTreeSet::new();
    let mut thin = Vec::new();
    for solid in solids {
        let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
        let block = to_block_solid(&solid, transform, origin);
        // Inclusive: 8-unit plates are the single most common thin brush in a
        // Source map, and the transform leaves their measured thickness a hair
        // either side of the cut-off, so the slack decides them consistently.
        if crate::voxel::brush::thickness(&block) > limit + MESH_THICKNESS_SLACK {
            voxelized.push(solid);
            continue;
        }
        let Some(mesh) = brush_mesh(map, config, &solid, &block, transform, origin) else {
            // Nothing drawn means nothing there as far as the map is
            // concerned, so it must not darken anything either.
            continue;
        };
        if config.output.brush_meshes.occlude_light {
            // Exactly the cells voxelizing would have filled. They carry no
            // block; the runtime reads them as opaque so a ceiling of plates
            // keeps the daylight out the way the source map does.
            crate::voxel::brush::voxelize(&block, &config.output.voxelize, |cell, _| {
                occluders.insert(cell);
            });
        }
        meshes.push(mesh);
        thin.push((solid.flags, block));
    }
    (voxelized, meshes, occluders, thin)
}

/// Build the drawable mesh for one brush, or `None` when none of its sides is
/// ever drawn.
pub(super) fn brush_mesh(
    map: &Map,
    config: &Config,
    solid: &Solid,
    block: &BlockSolid,
    transform: &Transform,
    origin: Vec3,
) -> Option<BrushMesh> {
    use vbsp::TextureFlags as F;
    let corners = crate::geom::polyhedron_vertices(&block.planes, MESH_PLANE_EPSILON);
    if corners.len() < 4 {
        return None;
    }
    let mut bounds = Aabb::empty();
    for corner in &corners {
        bounds.extend(*corner);
    }
    let centre = bounds.center();

    let mut parts: BTreeMap<String, BrushMeshPart> = BTreeMap::new();
    for (index, side) in solid.sides.iter().enumerate() {
        if is_invisible(side.texture_flags)
            || (config.contents.skip_sky && side.texture_flags.intersects(F::SKY | F::SKY2D))
        {
            continue;
        }
        let Some(info_index) = side.texture_info else {
            continue;
        };
        let Some(material_index) = map.material_index(info_index) else {
            continue;
        };
        let Some(material) = map.materials().get(material_index) else {
            continue;
        };
        let Some(info) = map.bsp.textures_info.get(info_index) else {
            continue;
        };
        let Some(size) = texture_size(map, info) else {
            continue;
        };
        let plane = block.planes[index];
        let polygon = crate::geom::polyhedron_face(&corners, plane, MESH_PLANE_EPSILON);
        if polygon.len() < 3 {
            continue;
        }
        let texcoord = crate::bsp::texcoord::TexCoord::of(info).in_block_space(transform, origin);
        let uv_of = |p: Vec3| [texcoord.s(p) / size[0], texcoord.t(p) / size[1]];

        let part = parts
            .entry(material.name.clone())
            .or_insert_with(|| BrushMeshPart {
                material: material.name.clone(),
                triangles: Vec::new(),
                normals: Vec::new(),
                uvs: Vec::new(),
                blocks_per_repeat: [0.0; 2],
            });
        // Texels per block along each projection axis, turned into how many
        // blocks one repeat of the sprite covers. That is what decides how much
        // of the texture is worth keeping: a sprite tiled every half block
        // needs far fewer texels than one stretched over ten.
        let span = |axis: [f64; 4], texels: f64| {
            let rate = (axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]).sqrt();
            (rate > 0.0).then(|| texels / rate)
        };
        for (index, blocks) in [span(texcoord.u, size[0]), span(texcoord.v, size[1])]
            .into_iter()
            .enumerate()
        {
            if let Some(blocks) = blocks.filter(|value| value.is_finite()) {
                part.blocks_per_repeat[index] = part.blocks_per_repeat[index].max(blocks);
            }
        }
        for corner in 1..polygon.len() - 1 {
            let triangle = [polygon[0], polygon[corner], polygon[corner + 1]];
            part.triangles.push(triangle.map(|point| point - centre));
            part.normals.push(plane.normal);
            part.uvs.push(triangle.map(uv_of));
        }
    }

    let parts: Vec<BrushMeshPart> = parts
        .into_values()
        .filter(|part| !part.triangles.is_empty())
        .collect();
    if parts.is_empty() {
        return None;
    }
    Some(BrushMesh {
        brush_index: solid.brush_index,
        origin: centre,
        bounds,
        parts,
    })
}
