//! Brush entities: which models convert, where their origins are, and the
//! movers cut out of the world.

use super::*;

/// Translate a brush into block space, mapping each plane through `transform`.
///
/// `origin` is the brush entity's own origin, which has to be added back
/// first. VBSP rewrites a brush entity's geometry to be relative to its
/// `origin` keyvalue, leaving the model's stored origin at zero, so the
/// coordinates in the plane lump are *not* world space. In
/// `d1_trainstation_02` that is 103 of 115 brush entity models: taken at face
/// value, every door, button, trigger and func_brush in the map piles up
/// around wherever Source's origin happens to land.
pub(crate) fn to_block_solid(solid: &Solid, transform: &Transform, origin: Vec3) -> BlockSolid {
    let planes = solid
        .sides
        .iter()
        .map(|side| {
            // Translating a plane moves its distance along its own normal.
            let plane = crate::geom::Plane::new(
                side.plane.normal,
                side.plane.dist + side.plane.normal.dot(origin),
            );
            transform.transform_plane(plane)
        })
        .collect();
    BlockSolid {
        planes,
        bounds: transform.transform_bounds(Aabb::new(
            solid.bounds.min + origin,
            solid.bounds.max + origin,
        )),
        side_of_plane: (0..solid.sides.len()).collect(),
    }
}

/// Which brush model each entity owns, and what to do with it.
pub(super) struct EntityModel {
    pub(super) entity: usize,
    pub(super) classname: String,
    pub(super) targetname: Option<String>,
    pub(super) model: usize,
    /// The entity's `origin`, which its geometry is stored relative to.
    pub(super) origin: Vec3,
    pub(super) mode: crate::config::BrushEntityMode,
}

/// Match every brush entity to its model and the mode configured for its
/// classname.
pub(super) fn entity_models(map: &Map, config: &Config, transform: &Transform) -> Vec<EntityModel> {
    let records = crate::bsp::entities::extract(map, transform);
    // What `func_areaportalwindow`s fade: `CFuncAreaPortalWindow::Activate`
    // takes the model of the first entity of its `target` name.
    let faded: BTreeSet<usize> = if config.entities.separate_fade_brushes {
        records
            .iter()
            .filter(|r| r.classname.eq_ignore_ascii_case("func_areaportalwindow"))
            .filter_map(|window| window.get("target"))
            .filter_map(|target| {
                records
                    .iter()
                    .find(|r| r.targetname.as_deref() == Some(target) && r.brush_model.is_some())
                    .map(|r| r.index)
            })
            .collect()
    } else {
        BTreeSet::new()
    };
    records
        .into_iter()
        .filter_map(|record| {
            let model = record.brush_model?;
            // Worldspawn is model 0 and is never a brush entity.
            if model == 0 || model >= map.bsp.models.len() {
                return None;
            }
            let mode = if faded.contains(&record.index) {
                crate::config::BrushEntityMode::Separate
            } else {
                config
                    .entities
                    .classname_modes
                    .get(&record.classname)
                    .copied()
                    .unwrap_or(config.entities.brush_entities)
            };
            let origin = record
                .origin_source
                .map(|[x, y, z]| Vec3::new(x, y, z))
                .unwrap_or(Vec3::ZERO);
            Some(EntityModel {
                entity: record.index,
                classname: record.classname,
                targetname: record.targetname,
                model,
                origin,
                mode,
            })
        })
        .collect()
}

/// Where each brush entity model's geometry has to be moved back to.
pub(super) fn model_origins(entities: &[EntityModel]) -> std::collections::HashMap<usize, Vec3> {
    entities.iter().map(|e| (e.model, e.origin)).collect()
}

/// Which models go into the world grid: worldspawn, plus every brush entity
/// not being written separately or skipped.
pub(super) fn models_to_convert(map: &Map, config: &Config, entities: &[EntityModel]) -> Vec<usize> {
    use crate::config::BrushEntityMode;
    let modes: std::collections::HashMap<usize, BrushEntityMode> =
        entities.iter().map(|e| (e.model, e.mode)).collect();

    (0..map.bsp.models.len())
        .filter(|model| {
            // Worldspawn is the world and is always converted. A model no
            // entity claims falls back to the global setting rather than being
            // dropped, so malformed entity data cannot silently lose geometry.
            *model == 0
                || modes
                    .get(model)
                    .copied()
                    .unwrap_or(config.entities.brush_entities)
                    == BrushEntityMode::Include
        })
        .collect()
}

/// Run one brush entity through the world's exact pipeline on its own: thin
/// brushes split off, the rest voxelized, the face lump's faces cut against
/// the entity's own grid, and collision computed from both.
///
/// Nothing is hollowed: a mover is a door or a platform, rarely more than a
/// block or two thick, and it is seen from every side once it moves. Shapes
/// are not fitted either; the mod only ever reads which cells hold a block.
#[allow(clippy::too_many_arguments)]
pub(super) fn mover_geometry(
    entity: &EntityModel,
    map: &Map,
    config: &Config,
    resolver: &Resolver,
    transform: &Transform,
    origins: &std::collections::HashMap<usize, Vec3>,
    tiles: &[Option<TileSet>],
    palette: &Mutex<Palette>,
    skybox: Option<&crate::bsp::skybox::Skybox>,
) -> MoverGeometry {
    let all: Vec<Solid> = map
        .solids(entity.model)
        .into_iter()
        // A brush entity's brushes are stored relative to its origin, so the
        // skybox room is tested against where they really are: tested where
        // they are stored, every mover near Source's origin fell inside an
        // INFRA skybox room and lost its brushes.
        .filter(|solid| {
            let placed = Aabb::new(
                solid.bounds.min + entity.origin,
                solid.bounds.max + entity.origin,
            );
            !skybox.is_some_and(|room| room.contains(&placed))
        })
        .collect();
    let (solids, _, _, thin) = split_brush_meshes(map, config, transform, origins, all.clone());
    let skipped = std::sync::atomic::AtomicUsize::new(0);
    let (grid, _, _) = voxelize_solids(
        &solids, map, config, resolver, transform, origins, tiles, palette, &skipped,
    );
    let (polygons, faces_unmatched) = exact_polygons(
        map,
        config,
        resolver,
        transform,
        origins,
        &[entity.model],
        &all,
        skybox,
        &[],
    );
    let fragments: Vec<crate::voxel::fragments::Fragment> = polygons
        .par_iter()
        .flat_map_iter(|polygon| crate::voxel::fragments::fragments(polygon, &grid))
        .collect();
    let block_solids: Vec<BlockSolid> = solids
        .iter()
        .filter(|solid| makes_blocks(solid, map, config, resolver))
        .map(|solid| {
            let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
            to_block_solid(solid, transform, origin)
        })
        .collect();
    // A brush the mover is made of collides in Source whether or not any side
    // is drawn: sp_a2_bts4's conveyor trains are a single 2-unit plate of
    // `tools/toolsplayerclip` with solid contents under a belt prop that does
    // not collide.
    let unseen: Vec<BlockSolid> = all
        .iter()
        .filter(|solid| collides_unseen(solid))
        .map(|solid| {
            let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
            to_block_solid(solid, transform, origin)
        })
        .collect();
    let collision = crate::voxel::collision::compute(&crate::voxel::collision::Sources {
        grid: &grid,
        // Never hollowed, so no cell is sealed away inside it.
        interior: &|_| false,
        solids: block_solids.iter().collect(),
        thin: thin
            .iter()
            .filter(|(flags, _)| resolver.decide(*flags) != Decision::Skip)
            .map(|(_, block)| block)
            .chain(&unseen)
            .collect(),
        terrain: Vec::new(),
    });
    let only_unseen = grid.iter().next().is_none()
        && fragments.is_empty()
        && block_solids.is_empty()
        && thin.is_empty()
        && !unseen.is_empty();
    MoverGeometry {
        entity: entity.entity,
        classname: entity.classname.clone(),
        targetname: entity.targetname.clone(),
        model: entity.model,
        only_unseen,
        grid,
        fragments,
        collision,
        faces_unmatched,
    }
}
