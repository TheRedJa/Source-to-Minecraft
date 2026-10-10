//! Voxelizing brushes, displacements and props into the block grid, and
//! baking props into blocks.

use super::*;

/// Texture flags that mean "this face is never drawn", so it should never
/// become blocks either. Checked per side, because a brush commonly mixes one
/// visible face with five nodraw ones.
pub(super) fn is_invisible(flags: vbsp::TextureFlags) -> bool {
    use vbsp::TextureFlags as F;
    flags.intersects(F::NODRAW | F::SKIP | F::HINT | F::TRIGGER)
}

/// Whether a brush no side of which is drawn still collides with a player,
/// as Source's does: solid contents, or a player clip. Sky brushes are left
/// out: they only keep a player from leaving the map, and filling them more
/// than doubled furnace's carrier blocks (D26). Such a brush makes no
/// block and no mesh, so its volume joins the collision on its own, as a thin
/// brush's does. Monster clips stop only NPCs, which Minecraft has none of.
pub(super) fn collides_unseen(solid: &Solid) -> bool {
    pub(super) const SOLID: u32 = 0x1;
    pub(super) const AREAPORTAL: u32 = 0x8000;
    pub(super) const PLAYERCLIP: u32 = 0x10000;
    pub(super) const MONSTERCLIP: u32 = 0x20000;
    pub(super) const ORIGIN: u32 = 0x100_0000;
    let bits = solid.flags.bits();
    bits & (SOLID | PLAYERCLIP) != 0
        && bits & (AREAPORTAL | MONSTERCLIP | ORIGIN) == 0
        && solid
            .sides
            .iter()
            .all(|side| is_invisible(side.texture_flags))
}

/// `solid` cut down to the cells `min..=max`; `None` when it lies wholly
/// outside them.
pub(super) fn clipped_to_cells(solid: &BlockSolid, min: IVec3, max: IVec3) -> Option<BlockSolid> {
    let low = Vec3::new(f64::from(min[0]), f64::from(min[1]), f64::from(min[2]));
    let high = Vec3::new(
        f64::from(max[0] + 1),
        f64::from(max[1] + 1),
        f64::from(max[2] + 1),
    );
    let bounds = Aabb::new(
        Vec3::new(
            solid.bounds.min.x.max(low.x),
            solid.bounds.min.y.max(low.y),
            solid.bounds.min.z.max(low.z),
        ),
        Vec3::new(
            solid.bounds.max.x.min(high.x),
            solid.bounds.max.y.min(high.y),
            solid.bounds.max.z.min(high.z),
        ),
    );
    if bounds.min.x >= bounds.max.x || bounds.min.y >= bounds.max.y || bounds.min.z >= bounds.max.z
    {
        return None;
    }
    let mut planes = solid.planes.clone();
    let mut side_of_plane = solid.side_of_plane.clone();
    for (normal, dist) in [
        (Vec3::new(1.0, 0.0, 0.0), high.x),
        (Vec3::new(-1.0, 0.0, 0.0), -low.x),
        (Vec3::new(0.0, 1.0, 0.0), high.y),
        (Vec3::new(0.0, -1.0, 0.0), -low.y),
        (Vec3::new(0.0, 0.0, 1.0), high.z),
        (Vec3::new(0.0, 0.0, -1.0), -low.z),
    ] {
        planes.push(crate::geom::Plane::new(normal, dist));
        side_of_plane.push(usize::MAX);
    }
    Some(BlockSolid {
        planes,
        bounds,
        side_of_plane,
    })
}

/// The block one brush side contributes, or `None` if it contributes nothing.
///
/// The material can veto the brush's contents, which matters more than it
/// sounds. A fog volume is a brush flagged `WINDOW`, because fog is
/// translucent, wearing `tools/toolsfog`. Taking the contents at face value
/// turns a city block of atmosphere into a solid cube of glass: in `az_c1_2`
/// that was 1.9 million glass blocks, six times the rest of the map put
/// together. The material knows it is not a window.
pub(super) fn side_block<'a>(
    decision: &'a Decision,
    resolver: &'a Resolver,
    material: Option<usize>,
    flags: vbsp::TextureFlags,
    skip_sky: bool,
) -> Option<&'a str> {
    use vbsp::TextureFlags as F;
    if is_invisible(flags) || (skip_sky && flags.intersects(F::SKY | F::SKY2D)) {
        return None;
    }

    match material {
        // A material that resolves to no block drops the side outright.
        Some(index) => {
            let block = resolver.block_for_material(index)?;
            match decision {
                // A rule that names a block outranks the contents flag, which
                // is only ever a guess about what the brush is. Entropy: Zero
                // 2's arctic maps flag their snow sheets `WINDOW` because they
                // are translucent; the `*snow*` rule knows better than to make
                // them glass.
                Decision::Force(_) if resolver.named_by_rule(index) => Some(block),
                Decision::Force(forced) => Some(forced),
                Decision::ByMaterial => Some(block),
                Decision::Skip => None,
            }
        }
        // A side with no texture info at all still bounds the brush, so it
        // gets the fallback rather than punching a hole in it.
        None => match decision {
            Decision::Force(forced) => Some(forced),
            Decision::ByMaterial => Some(resolver.fallback_block()),
            Decision::Skip => None,
        },
    }
}

/// Voxelize one displacement into its own grid.
///
/// The surface is a shell one voxel thick, which you would fall straight
/// through and which looks like paper from below, so it is backed by
/// `solidify` more voxels driven into the solid side. That direction is the
/// face's own inward normal rather than simply down: displacements make walls
/// and ceilings as often as they make ground, and thickening a cliff downwards
/// would leave its face just as thin.
pub(super) fn voxelize_displacement(
    surface: &crate::bsp::displacement::Surface,
    transform: &Transform,
    solidify: u32,
    block: BlockId,
    tiles: Option<&TileSet>,
) -> (
    VoxelGrid,
    crate::voxel::shapes::MaskGrid,
    crate::voxel::surface::FaceCandidates,
) {
    use crate::voxel::mesh::{Triangle, voxelize_triangle};
    use crate::voxel::surface::{FaceCandidates, FaceSource, SourceProvenance};

    let mut grid = VoxelGrid::new();
    let mut masks = crate::voxel::shapes::MaskGrid::new();
    let mut faces = FaceCandidates::new();
    let inward = transform.transform_direction(-surface.normal);
    // Terrain is world geometry like any other, so a split texture is chosen
    // by the same world projection the brushes use.
    let uv = tiles.zip(surface.texcoord).map(|(set, tex)| {
        let uv = Uv::new(
            tex,
            transform,
            Vec3::ZERO,
            set.texels_per_tile,
            transform.units_per_block(),
        );
        (uv, set)
    });

    for (triangle, tri) in surface.triangles.iter().enumerate() {
        let mapped = Triangle::new(
            transform.to_block_space(tri.a),
            transform.to_block_space(tri.b),
            transform.to_block_space(tri.c),
        );
        let source = match (surface.material, surface.texcoord) {
            (Some(material), Some(texcoord)) => Some(FaceSource {
                provenance: SourceProvenance::Displacement {
                    displacement: surface.index,
                    triangle,
                },
                material,
                uv: texcoord.in_block_space(transform, Vec3::ZERO),
                // Voxel-face candidates; the drawn terrain is lit through its polygons.
                light: None,
                blend: None,
            }),
            _ => None,
        };
        let plane = crate::geom::Plane::new(mapped.normal(), mapped.normal().dot(mapped.a));
        voxelize_triangle(&mapped, |pos| {
            let block = match uv {
                Some((uv, set)) => {
                    let centre = Vec3::new(
                        pos[0] as f64 + 0.5,
                        pos[1] as f64 + 0.5,
                        pos[2] as f64 + 0.5,
                    );
                    let (s, t) = uv.at(centre);
                    set.at(s, t)
                }
                None => block,
            };
            grid.set(pos, block);
            // Displacements are emitted as conservative whole voxels. They
            // must never inherit a slab/stair mask from an overlapping base
            // brush: that was sinking INFRA floors to Y + 0.5.
            masks.add(pos, u8::MAX);
            if let Some(source) = source {
                faces.add(pos, source, plane);
            }
            for step in 1..=solidify {
                let offset = inward * step as f64;
                let backing = [
                    pos[0] + offset.x.round() as i32,
                    pos[1] + offset.y.round() as i32,
                    pos[2] + offset.z.round() as i32,
                ];
                grid.set(backing, block);
                masks.add(backing, u8::MAX);
                if let Some(source) = source {
                    faces.add(backing, source, plane);
                }
            }
        });
    }
    (grid, masks, faces)
}

/// Voxelize one static prop's triangles.
///
/// A model is a surface, not a solid, so this is the displacement treatment:
/// rasterize the triangles and, if asked, drive `solidify` more voxels along
/// each one's inward normal. Props are usually closed shells already — a crate
/// really is a box — so the default is none, and a fence stays one block
/// thick instead of becoming a wall.
pub(super) fn voxelize_prop(
    surface: &crate::source::extract::PropSurface,
    transform: &Transform,
    solidify: u32,
    block: BlockId,
    tiles: Option<&TileSet>,
) -> VoxelGrid {
    use crate::voxel::mesh::{Triangle, voxelize_triangle};

    let mut grid = VoxelGrid::new();
    for (index, tri) in surface.triangles.iter().enumerate() {
        let mapped = Triangle::new(
            transform.to_block_space(tri[0]),
            transform.to_block_space(tri[1]),
            transform.to_block_space(tri[2]),
        );
        if mapped.is_degenerate() {
            continue;
        }
        let inward = -mapped.normal();
        // A model's UVs are an unwrap of the whole sheet, so the tile comes
        // off the triangle rather than from a world projection — but
        // interpolated across it, not taken once for the whole triangle. A
        // model's triangles are not block-sized: `rockcliff02a` is a handful
        // of huge ones, and one tile each paints the cliff in patches.
        let corners = tiles.and(surface.uvs.get(index));
        voxelize_triangle(&mapped, |pos| {
            let block = match (tiles, corners) {
                (Some(set), Some(corners)) => {
                    let centre = Vec3::new(
                        pos[0] as f64 + 0.5,
                        pos[1] as f64 + 0.5,
                        pos[2] as f64 + 0.5,
                    );
                    set.at_uv(interpolate(&mapped, corners, centre))
                }
                _ => block,
            };
            grid.set(pos, block);
            for step in 1..=solidify {
                let offset = inward * step as f64;
                grid.set(
                    [
                        pos[0] + offset.x.round() as i32,
                        pos[1] + offset.y.round() as i32,
                        pos[2] + offset.z.round() as i32,
                    ],
                    block,
                );
            }
        });
    }
    grid
}

/// Whether props should be solid as shaped blocks rather than barrier cubes.
///
/// A shape has to be registered somewhere, so this needs the generated pack:
/// vanilla output has no pack and falls back to barriers, which is also what
/// `collision = "barrier"` asks for outright.
pub(super) fn shaped_collision_wanted(config: &Config) -> bool {
    config.props.collision == crate::config::CollisionMode::Shaped
        && config.materials.mode == crate::config::MaterialMode::Kubejs
}

/// Whether props should be solid as barrier cubes.
pub(super) fn barriers_wanted(config: &Config) -> bool {
    match config.props.collision {
        crate::config::CollisionMode::None => false,
        crate::config::CollisionMode::Barrier => true,
        // Shaped collision falls back to barriers without a pack to register
        // shapes in. With one, the only barriers left are for the props that
        // stayed display entities, and those are placed separately.
        crate::config::CollisionMode::Shaped => !shaped_collision_wanted(config),
    }
}

/// Voxelize a set of brushes into one grid, interning blocks into `palette`.
// Every argument here is one of the conversion's inputs; bundling them into a
// struct would only move the same list somewhere else.
#[allow(clippy::too_many_arguments)]
pub(super) fn voxelize_solids(
    solids: &[Solid],
    map: &Map,
    config: &Config,
    resolver: &Resolver,
    transform: &Transform,
    origins: &std::collections::HashMap<usize, Vec3>,
    tiles: &[Option<TileSet>],
    palette: &Mutex<Palette>,
    skipped: &std::sync::atomic::AtomicUsize,
) -> (
    VoxelGrid,
    crate::voxel::shapes::MaskGrid,
    crate::voxel::surface::FaceCandidates,
) {
    use crate::voxel::shapes::MaskGrid;
    use crate::voxel::surface::{FaceCandidates, FaceSource, SourceProvenance};
    let skip_sky = config.contents.skip_sky;
    let want_masks = config.shapes.enabled;

    solids
        .par_iter()
        .fold(
            || (VoxelGrid::new(), MaskGrid::new(), FaceCandidates::new()),
            |(mut grid, mut masks, mut faces), solid| {
                let decision = resolver.decide(solid.flags);
                if decision == Decision::Skip {
                    skipped.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    return (grid, masks, faces);
                }

                let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
                let block_solid = to_block_solid(solid, transform, origin);

                // Resolve the block for each side once, rather than per voxel.
                let side_blocks: Vec<Option<BlockId>> = solid
                    .sides
                    .iter()
                    .map(|side| {
                        let material = side.texture_info.and_then(|i| map.material_index(i));
                        side_block(&decision, resolver, material, side.texture_flags, skip_sky)
                            .map(|name| palette.lock().unwrap().intern(name))
                    })
                    .collect();

                // A brush whose every side was vetoed contributes nothing.
                if side_blocks.iter().all(Option::is_none) {
                    skipped.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    return (grid, masks, faces);
                }
                // The side a voxel falls back to when its own nearest face is one
                // the engine never draws. Half a brush's sides are nodraw, so this
                // is not a rare case: it decides the block for a large share of
                // the voxels in the map, and taking the tiled path here too is
                // what keeps a wall from being half one repeated tile.
                let default_side = side_blocks.iter().position(Option::is_some);

                // Where a face's material was split across several blocks, the
                // projection that says which piece belongs at each voxel.
                let side_tiles: Vec<Option<(Uv, &TileSet)>> = solid
                    .sides
                    .iter()
                    .map(|side| {
                        let info_index = side.texture_info?;
                        let set = tiles.get(map.material_index(info_index)?)?.as_ref()?;
                        let info = map.bsp.textures_info.get(info_index)?;
                        let tex = crate::bsp::texcoord::TexCoord::of(info);
                        let uv = Uv::new(
                            tex,
                            transform,
                            origin,
                            set.texels_per_tile,
                            transform.units_per_block(),
                        );
                        Some((uv, set))
                    })
                    .collect();
                let side_sources: Vec<Option<FaceSource>> = solid
                    .sides
                    .iter()
                    .enumerate()
                    .map(|(side, source_side)| {
                        side_blocks.get(side).copied().flatten()?;
                        let info_index = source_side.texture_info?;
                        let material = map.material_index(info_index)?;
                        let texcoord = map.bsp.textures_info.get(info_index)?;
                        Some(FaceSource {
                            provenance: SourceProvenance::Brush {
                                brush: solid.brush_index,
                                side,
                            },
                            material,
                            uv: crate::bsp::texcoord::TexCoord::of(texcoord)
                                .in_block_space(transform, origin),
                            // A brush side is not a BSP face, and has no lightmap.
                            light: None,
                            blend: None,
                        })
                    })
                    .collect();

                let block_of = |side: usize, pos: IVec3| -> Option<BlockId> {
                    let base = side_blocks.get(side).copied().flatten()?;
                    let Some((uv, set)) = side_tiles.get(side).and_then(Option::as_ref) else {
                        return Some(base);
                    };
                    let centre = Vec3::new(
                        pos[0] as f64 + 0.5,
                        pos[1] as f64 + 0.5,
                        pos[2] as f64 + 0.5,
                    );
                    let (s, t) = uv.at(centre);
                    Some(set.at(s, t))
                };

                crate::voxel::brush::voxelize_with_shape(
                    &block_solid,
                    &config.output.voxelize,
                    |pos, side, mask| {
                        let block = side
                            .and_then(|s| block_of(s, pos))
                            .or_else(|| default_side.and_then(|s| block_of(s, pos)));
                        if let Some(block) = block {
                            grid.set(pos, block);
                            for (plane, source_plane) in block_solid.planes.iter().enumerate() {
                                let side = block_solid.side_of_plane[plane];
                                if let Some(source) = side_sources.get(side).copied().flatten() {
                                    faces.add(pos, source, *source_plane);
                                }
                            }
                            if want_masks {
                                masks.add(pos, mask);
                            }
                        }
                    },
                );
                (grid, masks, faces)
            },
        )
        .reduce(
            || (VoxelGrid::new(), MaskGrid::new(), FaceCandidates::new()),
            |(mut ga, mut ma, mut fa), (gb, mb, fb)| {
                ga.merge(gb);
                ma.merge(mb);
                fa.merge(fb);
                (ga, ma, fa)
            },
        )
}

/// Whether a voxelized brush makes any blocks at all: not skipped outright,
/// and with at least one side whose material resolves. Only such a brush
/// collides, exactly as only such a brush is drawn.
pub(super) fn makes_blocks(solid: &Solid, map: &Map, config: &Config, resolver: &Resolver) -> bool {
    let skip_sky = config.contents.skip_sky;
    let decision = resolver.decide(solid.flags);
    decision != Decision::Skip
        && solid.sides.iter().any(|side| {
            let material = side.texture_info.and_then(|i| map.material_index(i));
            side_block(&decision, resolver, material, side.texture_flags, skip_sky).is_some()
        })
}

/// Replace full cubes with slabs and stairs wherever the octant mask says the
/// geometry was really half-height or stepped.
///
/// Only blocks that have vanilla slab and stair variants can change; anything
/// else keeps its full cube. A generated textured block has no variants unless
/// the pack was told to register them, so this quietly does nothing there
/// rather than naming a block that does not exist.
pub(super) fn fit_shapes(
    grid: &VoxelGrid,
    masks: &crate::voxel::shapes::MaskGrid,
    palette: &mut Palette,
    pack: &crate::output::kubejs::Pack,
) -> (VoxelGrid, usize) {
    use crate::voxel::shapes::{Shape, shape_for};

    // Resolve every (block, shape) pair once. A palette holds a few hundred
    // entries against millions of voxels, so doing this per voxel would spend
    // the whole pass formatting strings.
    let shapes: Vec<Shape> = (0..=u8::MAX).map(shape_for).collect();
    let mut resolved: std::collections::HashMap<(BlockId, Shape), Option<BlockId>> =
        std::collections::HashMap::new();

    let mut out = VoxelGrid::new();
    let mut fitted = 0;

    for (pos, id) in grid.iter() {
        let shape = shapes[masks.get(pos) as usize];
        if shape == Shape::Full {
            out.set(pos, id);
            continue;
        }

        let block = match resolved.get(&(id, shape)) {
            Some(cached) => *cached,
            None => {
                let name = palette.name(id).to_string();
                let block = crate::palette::blocks::shaped(&name, shape.variant())
                    .map(str::to_string)
                    .or_else(|| pack.shaped(&name, shape.variant()))
                    .zip(shape.state())
                    .map(|(base, state)| palette.intern(&format!("{base}{state}")));
                resolved.insert((id, shape), block);
                block
            }
        };

        match block {
            Some(block) => {
                out.set(pos, block);
                fitted += 1;
            }
            None => out.set(pos, id),
        }
    }
    (out, fitted)
}

/// Every displacement voxelized with the block its material resolves to, and
/// how many were skipped for having none.
pub(super) fn voxelize_displacements(
    surfaces: &[crate::bsp::displacement::Surface],
    config: &Config,
    resolver: &Resolver,
    transform: &Transform,
    tiles: &[Option<TileSet>],
    palette: &Mutex<Palette>,
) -> (
    (
        VoxelGrid,
        crate::voxel::shapes::MaskGrid,
        crate::voxel::surface::FaceCandidates,
    ),
    usize,
) {
    let displacements_skipped = std::sync::atomic::AtomicUsize::new(0);
    let terrain = surfaces
        .par_iter()
        .fold(
            || {
                (
                    VoxelGrid::new(),
                    crate::voxel::shapes::MaskGrid::new(),
                    crate::voxel::surface::FaceCandidates::new(),
                )
            },
            |(mut grid, mut masks, mut faces), surface| {
                let block = surface
                    .material
                    .and_then(|m| resolver.block_for_material(m))
                    .map(|name| palette.lock().unwrap().intern(name));
                match block {
                    Some(block) => {
                        let (surface_grid, surface_masks, surface_faces) =
                            voxelize_displacement(
                                surface,
                                transform,
                                config.displacement.solidify,
                                block,
                                surface
                                    .material
                                    .and_then(|m| tiles.get(m))
                                    .and_then(Option::as_ref),
                            );
                        grid.merge(surface_grid);
                        masks.merge(surface_masks);
                        faces.merge(surface_faces);
                    }
                    None => {
                        displacements_skipped
                            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    }
                }
                (grid, masks, faces)
            },
        )
        .reduce(
            || {
                (
                    VoxelGrid::new(),
                    crate::voxel::shapes::MaskGrid::new(),
                    crate::voxel::surface::FaceCandidates::new(),
                )
            },
            |(mut a, mut ma, mut fa), (b, mb, fb)| {
                a.merge(b);
                ma.merge(mb);
                fa.merge(fb);
                (a, ma, fa)
            },
        );
    (terrain, displacements_skipped.into_inner())
}

/// Each prop the config bakes into blocks, all of its pieces in free cells or
/// none of them: by placement index, the cell and block ID of every piece.
pub(super) fn bake_props(
    config: &Config,
    transform: &Transform,
    assets: &crate::source::extract::Assets,
    settle: &[f64],
    grid: &VoxelGrid,
    pack: &mut crate::output::kubejs::Pack,
) -> Vec<(usize, IVec3, String)> {
    let mut baked = Vec::new();
    let mut taken: std::collections::HashSet<IVec3> = std::collections::HashSet::new();
    for (index, placement) in assets.placements.iter().enumerate() {
        let size = placement.bounds.size();
        let longest = size.x.max(size.y).max(size.z);
        if config.props.bake_max_size > 0.0 && longest > config.props.bake_max_size {
            continue;
        }
        let Some(mesh) = assets.prop_meshes.get(placement.mesh) else {
            continue;
        };

        let quaternion = crate::output::display::rotation(&placement.prop, transform);
        let rounded = crate::output::display::dequantize(crate::output::display::quantize(
            quaternion,
            config.props.bake_angle_steps,
        ));
        let basis = crate::output::display::basis_of(rounded);
        let origin = transform.to_block_space(placement.prop.origin);
        let origin = Vec3::new(origin.x, origin.y + settle[index], origin.z);

        // A block model may be drawn outside its own block, but Sodium
        // packs chunk vertex coordinates into a range only 32 blocks wide
        // and masks off the rest, so a mesh reaching too far folds back on
        // itself. Anything that big is carried by several blocks instead.
        let reach = config.props.bake_reach.max(f64::MIN_POSITIVE);
        let pieces = mesh.split(basis, placement.prop.scale, reach);

        // All of a prop's pieces are placed or none of them is: half a
        // gantry is worse than a gantry drawn the slow way. Cells are
        // claimed as they are chosen and given back if the prop is
        // abandoned, so two pieces of the same prop cannot be handed the
        // same cell — the second block would replace the first and that
        // part of the mesh would simply not be drawn.
        let mut placing = Vec::with_capacity(pieces.len());
        let mut claimed: Vec<IVec3> = Vec::new();
        for piece in &pieces {
            // Inside what this piece actually covers, which for a prop
            // small enough not to be split is the whole prop's extent.
            let Some(cell) = crate::output::bake::anchor(
                grid,
                Aabb::new(origin + piece.bounds.min, origin + piece.bounds.max),
                &taken,
            ) else {
                placing.clear();
                break;
            };
            taken.insert(cell);
            claimed.push(cell);
            let key = crate::output::bake::Key::new(
                &mesh.id,
                quaternion,
                origin,
                cell,
                placement.prop.scale,
                config.props.bake_grid,
                config.props.bake_angle_steps,
                piece.centre,
            );
            // What the split actually achieved, rather than what it was
            // asked for: a single triangle wider than the reach cannot be
            // cut up by grouping, since nothing splits one triangle. A
            // prop still over the limit keeps the entity route, which is
            // drawn by the entity renderer and has no such limit.
            if mesh.reach_of(&key.place(config.props.bake_grid), piece) > reach {
                placing.clear();
                break;
            }
            placing.push((key, cell, piece));
        }
        if placing.is_empty() {
            for cell in claimed {
                taken.remove(&cell);
            }
            continue;
        }

        for (key, cell, piece) in placing {
            let id = key.id();
            if pack.prop(&id).is_none() {
                let place = key.place(config.props.bake_grid);
                let asset = mesh.asset(id.clone(), Some(&place), Some(piece));
                pack.insert_prop(asset, Vec::new());
            }
            baked.push((
                index,
                cell,
                format!("{}:{id}", crate::output::kubejs::NAMESPACE),
            ));
        }
    }
    baked
}
