//! Movers: brush entities and doors cut out of the world, each in cells of its
//! own, with the props that ride on them.

use super::*;
use crate::source::extract::ModProp;

/// The movers of a map, and what they carry.
pub(super) struct MoverExports {
    pub(super) movers: Vec<MoverExport>,
    /// The logic props riding on movers, their collision mover-local.
    pub(super) logic: Vec<LogicPropExport>,
    pub(super) attached_props: usize,
    /// Movers with nothing to draw, collide with or carry.
    pub(super) empty: usize,
    /// Movers without blocks.
    pub(super) thin: usize,
}

/// Each mover, in lump order, in cells of its own.
#[allow(clippy::too_many_arguments)]
pub(super) fn export_movers(
    config: &crate::config::Config,
    conversion: &crate::convert::Conversion,
    entities: &[crate::bsp::entities::EntityRecord],
    mover_entities: &BTreeSet<usize>,
    mut riding: BTreeMap<usize, Vec<ModProp>>,
    models: &BTreeMap<ModelUse, BuiltModel>,
    face_ids: &BTreeMap<usize, &[u32]>,
    light_atlas: &crate::output::light::Atlas,
) -> Result<MoverExports> {
    let no_blocks = crate::voxel::grid::VoxelGrid::new();
    let geometry: BTreeMap<usize, &crate::convert::MoverGeometry> = conversion
        .movers
        .iter()
        .map(|mover| (mover.entity, mover))
        .collect();
    let mut out = MoverExports {
        movers: Vec::new(),
        logic: Vec::new(),
        attached_props: 0,
        empty: 0,
        thin: 0,
    };
    for &entity in mover_entities {
        let shape = geometry.get(&entity).copied();
        let grid = shape.map_or(&no_blocks, |mover| &mover.grid);
        let riders = riding.remove(&entity).unwrap_or_default();
        let (mover_collision, mut rider_collision) =
            mover_collision(config, conversion, shape, grid, &riders)?;
        let mut rider_logic = Vec::new();
        let mut props = Vec::with_capacity(riders.len());
        for item in &riders {
            let built = &models[&model_use(item, item.prop.skin)];
            let origin = conversion.transform.to_block_space(item.prop.origin);
            let bounds = conversion.transform.transform_bounds(item.bounds);
            if let (Some(role), Some(prop_entity)) = (item.logic, item.prop.entity) {
                rider_logic.push(LogicPropExport {
                    entity: prop_entity,
                    placed: LogicPlacement::Mover(entity),
                    skins: logic_skins(models, item),
                    skin: item.prop.skin,
                    start_hidden: role.start_hidden,
                    collision: None,
                    sequence: item.animation.as_ref().map(|a| a.spawn),
                    poses: Vec::new(),
                });
            }
            props.push((
                MoverPropExport {
                    entity: item.prop.entity.context("mover prop without an entity")?,
                    source_model: item.prop.model.clone(),
                    model_content_id: built.id.clone(),
                    material_ids: built.slots.clone(),
                    translation: [origin.x, origin.y, origin.z],
                    rotation: crate::output::display::rotation(&item.prop, &conversion.transform),
                    scale: item.prop.scale,
                    skin: item.prop.skin,
                    color: item.prop.color,
                    animation: built.animation_id(),
                },
                bounds,
            ));
        }
        let classname = shape
            .map(|mover| mover.classname.clone())
            .or_else(|| entities.get(entity).map(|e| e.classname.clone()))
            .unwrap_or_default()
            .to_ascii_lowercase();
        let fragments = shape.map_or(&[][..], |mover| &mover.fragments[..]);
        let ids = face_ids.get(&entity).copied().unwrap_or(&[]);
        match localize(
            entity,
            classname,
            grid,
            fragments,
            ids,
            mover_collision,
            props,
        )? {
            Some(mut mover) => {
                light_faces(&mut mover, fragments, light_atlas);
                for mut record in rider_logic {
                    if let Some(own) = rider_collision.remove(&record.entity) {
                        (record.collision, record.poses) = own
                            .encode(mover.cell_origin)
                            .map(|(spawn, poses)| (Some(spawn), poses))?;
                    }
                    out.logic.push(record);
                }
                out.attached_props += mover.props.len();
                if mover.surface_blocks.is_empty() {
                    out.thin += 1;
                }
                out.movers.push(mover);
            }
            None => {
                out.logic.extend(rider_logic);
                out.empty += 1;
            }
        }
    }
    Ok(out)
}

/// A mover's collision with its riders'. A rider the logic can take the
/// collision of, or whose collision follows its pose, keeps it in tables of
/// its own, by entity, made mover-local once the mover's cells are known; the
/// rest is the mover's.
fn mover_collision(
    config: &crate::config::Config,
    conversion: &crate::convert::Conversion,
    shape: Option<&crate::convert::MoverGeometry>,
    grid: &crate::voxel::grid::VoxelGrid,
    riders: &[ModProp],
) -> Result<(
    crate::voxel::collision::CellCollision,
    BTreeMap<usize, ApartCollision>,
)> {
    let mut collision = shape
        .map(|mover| mover.collision.clone())
        .unwrap_or_default();
    // Brushes drawn nowhere collide on a mover that is there anyway, but do
    // not make one of an entity that is nothing else: hundreds of INFRA's
    // invisible button volumes would each become a sub-level.
    if shape.is_some_and(|mover| mover.only_unseen) && riders.is_empty() {
        collision = Default::default();
    }
    // `CFuncIllusionary` is `SOLID_NONE`; one is a mover only when a
    // `func_areaportalwindow` fades it.
    if shape.is_some_and(|mover| mover.classname.eq_ignore_ascii_case("func_illusionary")) {
        collision = Default::default();
    }
    let (merged, apart): (Vec<_>, Vec<_>) = riders.iter().partition(|item| !removable(item));
    add_prop_collision(config, &conversion.transform, grid, &merged, &mut collision);
    let mut rider_collision = BTreeMap::new();
    for item in apart {
        if let Some(own) =
            apart_collision(config, &conversion.transform, grid, item, &mut collision)
        {
            rider_collision.insert(
                item.prop.entity.context("logic prop without an entity")?,
                own,
            );
        }
    }
    Ok((collision, rider_collision))
}

/// Lit as the map's faces are, on the map's pages; the region moves with the
/// faces into mover-local cells.
fn light_faces(
    mover: &mut MoverExport,
    fragments: &[crate::voxel::fragments::Fragment],
    light_atlas: &crate::output::light::Atlas,
) {
    for (face, fragment) in mover.faces.iter_mut().zip(fragments) {
        face.light = fragment.source.light.and_then(|light| {
            let mut region = light_atlas.region(&light)?;
            for projection in [&mut region.st.u, &mut region.st.v] {
                projection[3] += (0..3)
                    .map(|axis| projection[axis] * f64::from(mover.cell_origin[axis]))
                    .sum::<f64>();
            }
            Some(region)
        });
    }
}
