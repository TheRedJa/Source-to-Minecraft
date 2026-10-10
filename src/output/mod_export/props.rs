//! Prop models: which model a prop wears, localisation and collision.

use super::*;
use crate::source::extract::ModProp;

/// A model as a prop wears it: path, skin, tint, and form (0 as loaded, 1 a
/// still dynamic prop's sequence 0, 2 an animated prop's reference pose).
pub(super) type ModelUse = (String, i32, [u8; 3], u8);

pub(super) fn model_use(item: &crate::source::extract::ModProp, skin: i32) -> ModelUse {
    let form = if item.animation.is_some() {
        2
    } else if item.posed {
        1
    } else {
        0
    };
    (item.prop.model.clone(), skin, item.prop.color, form)
}

/// One model reference's mesh, material slots and animation.
pub(super) struct BuiltModel {
    pub(super) id: String,
    pub(super) slots: Vec<u32>,
    pub(super) bytes: Vec<u8>,
    pub(super) surface_prop: Option<String>,
    pub(super) animation: Option<(String, Vec<u8>)>,
    /// Each mesh vertex's `.vtx` hardware vertex, for `.vhv` light.
    pub(super) hardware: Vec<u32>,
}

impl BuiltModel {
    pub(super) fn animation_id(&self) -> Option<String> {
        self.animation.as_ref().map(|(id, _)| id.clone())
    }
}

/// A placed animated prop, whose collision follows its pose. Source only
/// moves the collision of a model of several physics solids, its bone
/// followers; but props collide here as their drawn mesh, not their `.phy`,
/// and the clip brushes a map moves along with a single-solid door are left
/// out, so every animated prop follows (user, 2026-10-04, until `.phy`
/// collision replaces the mesh), riding a mover or not.
pub(super) fn follows_pose(item: &crate::source::extract::ModProp) -> bool {
    item.animation.is_some()
}

/// Move one mover's geometry and riders out of map-local cells into its own.
///
/// The origin is the lowest cell anything of the mover is in, so every
/// mover-local cell is at least zero: its blocks, its carriers, the cells its
/// faces lie in and belong to, a cell its collision reaches into, and for each
/// prop the cells of its box and the cell its origin is in. `None` when the
/// mover has none of these, so nothing to draw, collide with or carry.
pub(super) fn localize(
    entity: usize,
    classname: String,
    grid: &crate::voxel::grid::VoxelGrid,
    fragments: &[crate::voxel::fragments::Fragment],
    material_ids: &[u32],
    collision: crate::voxel::collision::CellCollision,
    props: Vec<(MoverPropExport, crate::geom::Aabb)>,
) -> Result<Option<MoverExport>> {
    use crate::voxel::collision::STEPS;
    ensure!(
        fragments.len() == material_ids.len(),
        "mover fragments and material IDs disagree"
    );
    let mut min = [i32::MAX; 3];
    let mut max = [i32::MIN; 3];
    let mut cover = |cell: IVec3| {
        for axis in 0..3 {
            min[axis] = min[axis].min(cell[axis]);
            max[axis] = max[axis].max(cell[axis]);
        }
    };
    for (cell, _) in grid.iter() {
        cover(cell);
    }
    for fragment in fragments {
        cover(fragment.cell);
        if let Some(owner) = fragment.owner {
            cover(add(fragment.cell, owner));
        }
    }
    for cell in &collision.carriers {
        cover(*cell);
    }
    for (cell, boxes) in &collision.shapes {
        cover(*cell);
        // A piece hanging off a block reaches into the cell beside it.
        for b in boxes {
            cover(std::array::from_fn(|axis| {
                cell[axis] + i32::from(b[axis]).div_euclid(STEPS)
            }));
            cover(std::array::from_fn(|axis| {
                cell[axis] + (i32::from(b[axis + 3]) - 1).div_euclid(STEPS)
            }));
        }
    }
    for (prop, bounds) in &props {
        let floor = |v: f64| v.floor() as i32;
        cover(prop.translation.map(floor));
        if !bounds.is_empty() {
            cover([bounds.min.x, bounds.min.y, bounds.min.z].map(floor));
            cover([bounds.max.x, bounds.max.y, bounds.max.z].map(|v| (v - 1.0e-8).floor() as i32));
        }
    }
    if min[0] > max[0] {
        return Ok(None);
    }
    let origin = min;
    let size: IVec3 = std::array::from_fn(|axis| max[axis] - min[axis] + 1);
    let shift = |value: f64, axis: usize| value - f64::from(origin[axis]);
    let faces = fragments
        .iter()
        .zip(material_ids)
        .map(|(fragment, &material)| {
            let mut face =
                surface::EncodedFace::from_fragment(fragment, surface::MaterialId(material));
            face.cell = sub(face.cell, origin);
            // The UV projection is affine in block coordinates, so moving the
            // geometry by -origin moves the offset by the projection of origin.
            for projection in [&mut face.uv.u, &mut face.uv.v] {
                projection[3] += (0..3)
                    .map(|axis| projection[axis] * f64::from(origin[axis]))
                    .sum::<f64>();
            }
            face
        })
        .collect();
    let shapes: BTreeMap<IVec3, Vec<crate::voxel::collision::Box16>> = collision
        .shapes
        .into_iter()
        .map(|(cell, boxes)| (sub(cell, origin), boxes))
        .collect();
    let encoded_collision = (!shapes.is_empty())
        .then(|| crate::output::cell_collision::encode(&shapes))
        .transpose()
        .context("encoding a mover's collision table")?;
    let surface_blocks: Vec<IVec3> = grid
        .iter()
        .map(|(cell, _)| sub(cell, origin))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let carrier_blocks: Vec<IVec3> = collision
        .carriers
        .iter()
        .filter(|cell| !grid.is_solid(**cell))
        .map(|cell| sub(*cell, origin))
        .collect();
    let props = props
        .into_iter()
        .map(|(mut prop, _)| {
            prop.translation = std::array::from_fn(|axis| shift(prop.translation[axis], axis));
            prop
        })
        .collect();
    Ok(Some(MoverExport {
        entity,
        classname,
        cell_origin: origin,
        size,
        faces,
        collision: encoded_collision,
        surface_blocks,
        carrier_blocks,
        props,
    }))
}

/// Give every solid prop large enough to bump into its collision: Source's
/// `solid` decides whether it has any and whether it is its bounding box or
/// its model. The model's own physics hull (`.phy`) is not read yet, so a
/// physics prop collides as the shell of its drawn mesh. Returns how many
/// props and cells took part.
pub(super) fn add_prop_collision(
    config: &crate::config::Config,
    transform: &crate::voxel::transform::Transform,
    grid: &crate::voxel::grid::VoxelGrid,
    extracted: &[&crate::source::extract::ModProp],
    collision: &mut crate::voxel::collision::CellCollision,
) -> (usize, usize) {
    use crate::voxel::collision::SubCells;
    use rayon::prelude::*;
    let volumes: Vec<std::collections::HashMap<IVec3, SubCells>> = extracted
        .par_iter()
        .filter_map(|item| prop_volume(config, transform, item))
        .collect();
    crate::timing::mark("  prop collision: shell volumes");
    let solid = volumes.len();
    let mut cells: std::collections::HashMap<IVec3, SubCells> = std::collections::HashMap::new();
    for volume in volumes {
        for (cell, bits) in volume {
            cells
                .entry(cell)
                .or_insert_with(SubCells::empty)
                .union(&bits);
        }
    }
    crate::timing::mark("  prop collision: merge");
    let added = crate::voxel::collision::add_props(collision, grid, cells);
    crate::timing::mark("  prop collision: add to table");
    (solid, added)
}

/// The solid volume of one prop, per cell, by its Source `solid`; `None`
/// for a prop that is not solid or is below the collision size floor.
pub(super) fn prop_volume(
    config: &crate::config::Config,
    transform: &crate::voxel::transform::Transform,
    item: &crate::source::extract::ModProp,
) -> Option<std::collections::HashMap<IVec3, crate::voxel::collision::SubCells>> {
    prop_volume_of(config, transform, item, item.standing_model())
}

/// [`prop_volume`] with the prop's model in another pose.
pub(super) fn prop_volume_of(
    config: &crate::config::Config,
    transform: &crate::voxel::transform::Transform,
    item: &crate::source::extract::ModProp,
    model: &crate::source::mdl::Model,
) -> Option<std::collections::HashMap<IVec3, crate::voxel::collision::SubCells>> {
    use crate::bsp::props::{SOLID_BBOX, SOLID_NONE};
    use crate::voxel::collision::{box_volume, shell_volume};
    let size = item.bounds.size();
    if item.prop.solid == SOLID_NONE
        || size.x.max(size.y).max(size.z) < config.props.collision_min_size
    {
        return None;
    }
    if item.prop.solid == SOLID_BBOX {
        let bounds = transform.transform_bounds(item.bounds);
        return Some(box_volume(bounds.min, bounds.max));
    }
    let triangles: Vec<[crate::geom::Vec3; 3]> = model
        .parts
        .iter()
        .flat_map(|part| &part.triangles)
        .map(|tri| tri.map(|v| transform.to_block_space(item.prop.place(v))))
        .collect();
    Some(shell_volume(&triangles))
}

/// A removable prop's own collision: as it spawns, and for one whose
/// collision follows its pose, as each sequence leaves it; per cell of the
/// grid it is in.
pub(super) struct ApartCollision {
    pub(super) spawn: BTreeMap<IVec3, Vec<crate::voxel::collision::Box16>>,
    pub(super) poses: Vec<(usize, BTreeMap<IVec3, Vec<crate::voxel::collision::Box16>>)>,
}

impl ApartCollision {
    /// Both encoded, with `origin` moved to cell zero.
    #[allow(clippy::type_complexity)]
    pub(super) fn encode(self, origin: IVec3) -> Result<(Vec<u8>, Vec<(usize, Vec<u8>)>)> {
        let shift = |shapes: BTreeMap<IVec3, Vec<crate::voxel::collision::Box16>>| {
            shapes
                .into_iter()
                .map(|(cell, boxes)| (sub(cell, origin), boxes))
                .collect::<BTreeMap<_, _>>()
        };
        let spawn = crate::output::cell_collision::encode(&shift(self.spawn))
            .context("encoding a logic prop's collision")?;
        let poses = self
            .poses
            .into_iter()
            .map(|(sequence, shapes)| {
                crate::output::cell_collision::encode(&shift(shapes))
                    .map(|bytes| (sequence, bytes))
                    .context("encoding a pose's collision")
            })
            .collect::<Result<_>>()?;
        Ok((spawn, poses))
    }
}

/// [`ApartCollision`] of one prop, its carriers added to `collision`; `None`
/// for a prop without collision.
pub(super) fn apart_collision(
    config: &crate::config::Config,
    transform: &crate::voxel::transform::Transform,
    grid: &crate::voxel::grid::VoxelGrid,
    item: &crate::source::extract::ModProp,
    collision: &mut crate::voxel::collision::CellCollision,
) -> Option<ApartCollision> {
    let volume = prop_volume(config, transform, item)?;
    let spawn = separate_prop_collision(collision, grid, volume);
    if spawn.is_empty() {
        return None;
    }
    // One table per pose a sequence leaves it in.
    let mut poses = Vec::new();
    if let Some(animated) = item.animation.as_ref().filter(|_| follows_pose(item)) {
        for &sequence in &animated.sequences {
            let model = crate::source::anim::pose_model(
                &item.model,
                &animated.animation,
                animated.animation.settled(sequence),
            );
            if let Some(volume) = prop_volume_of(config, transform, item, &model) {
                poses.push((sequence, separate_prop_collision(collision, grid, volume)));
            }
        }
    }
    Some(ApartCollision { spawn, poses })
}

/// A removable prop's collision as a table of its own, per cell, by the
/// rules [`crate::voxel::collision::add_props`] merges the others by: a cell
/// whose map block is already a full cube gains nothing, and a cell with no
/// block gets a carrier, which collides as nothing until the prop is added.
pub(super) fn separate_prop_collision(
    collision: &mut crate::voxel::collision::CellCollision,
    grid: &crate::voxel::grid::VoxelGrid,
    volume: std::collections::HashMap<IVec3, crate::voxel::collision::SubCells>,
) -> BTreeMap<IVec3, Vec<crate::voxel::collision::Box16>> {
    let mut shapes = BTreeMap::new();
    for (cell, bits) in volume {
        if bits.is_empty() || (grid.is_solid(cell) && !collision.shapes.contains_key(&cell)) {
            continue;
        }
        if !grid.is_solid(cell) {
            collision.carriers.insert(cell);
        }
        shapes.insert(cell, bits.boxes());
    }
    shapes
}

/// A single face or prop use contributing texel demand to a material.
#[derive(Debug, Clone, Copy)]
pub(super) enum Contrib {
    Face(usize),
    Prop,
}

/// Group contributions by the exact output resolution `analyze_resolution`
/// would assign them, so faces/props needing the same resolution share one
/// texture and nobody is forced onto another use's worst-case size.
pub(super) fn bucket_by_output(
    original: [u32; 2],
    quality: atlas::TextureQuality,
    contributions: impl IntoIterator<Item = (Contrib, [f64; 2])>,
) -> BTreeMap<[u32; 2], Vec<Contrib>> {
    let mut buckets: BTreeMap<[u32; 2], Vec<Contrib>> = BTreeMap::new();
    for (contrib, blocks_spanned) in contributions {
        if let Ok(decision) = atlas::analyze_resolution(original, blocks_spanned, quality) {
            buckets.entry(decision.output).or_default().push(contrib);
        }
    }
    buckets
}

/// Props parented to a mover ride on it, by mover entity; the rest stand in
/// the world.
pub(super) fn split_riders(
    extracted: Vec<ModProp>,
    attachments: &crate::output::movers::Attachments,
) -> (Vec<ModProp>, BTreeMap<usize, Vec<ModProp>>) {
    let mut riding: BTreeMap<usize, Vec<ModProp>> = BTreeMap::new();
    let mut placed = Vec::with_capacity(extracted.len());
    for item in extracted {
        match item
            .prop
            .entity
            .and_then(|entity| attachments.mover_of.get(&entity))
        {
            Some(&mover) => riding.entry(mover).or_default().push(item),
            None => placed.push(item),
        }
    }
    (placed, riding)
}

/// A prop the logic can remove keeps its collision in a table of its own,
/// added to the map's cells at runtime while it stands.
pub(super) fn removable(item: &ModProp) -> bool {
    item.logic.is_some_and(|role| role.collision) || follows_pose(item)
}

/// The map's cell collision with the world props' added, and the collision
/// the removable props keep apart.
pub(super) struct PropCollision {
    pub(super) collision: Option<crate::voxel::collision::CellCollision>,
    pub(super) solid_props: usize,
    pub(super) prop_cells: usize,
    /// Each removable prop's collision as it spawns, by entity.
    pub(super) logic: BTreeMap<usize, Vec<u8>>,
    /// Each pose-following prop's collision per pose, by entity.
    pub(super) poses: BTreeMap<usize, Vec<(usize, Vec<u8>)>>,
}

pub(super) fn prop_collision(
    config: &crate::config::Config,
    conversion: &crate::convert::Conversion,
    extracted: &[ModProp],
) -> Result<PropCollision> {
    let mut out = PropCollision {
        collision: conversion.collision.clone(),
        solid_props: 0,
        prop_cells: 0,
        logic: BTreeMap::new(),
        poses: BTreeMap::new(),
    };
    if let Some(collision) = out.collision.as_mut() {
        let (merged, apart): (Vec<_>, Vec<_>) = extracted.iter().partition(|item| !removable(item));
        (out.solid_props, out.prop_cells) = add_prop_collision(
            config,
            &conversion.transform,
            &conversion.grid,
            &merged,
            collision,
        );
        for item in apart {
            let Some(own) = apart_collision(
                config,
                &conversion.transform,
                &conversion.grid,
                item,
                collision,
            ) else {
                continue;
            };
            let entity = item.prop.entity.context("logic prop without an entity")?;
            let (spawn, poses) = own.encode([0; 3])?;
            out.logic.insert(entity, spawn);
            if !poses.is_empty() {
                out.poses.insert(entity, poses);
            }
        }
    }
    Ok(out)
}

/// Every model the props wear, built once per [`ModelUse`]. Keyed by skin and
/// tint as well: one mesh, but each skin its own material slots, and each tint
/// its own reference. A prop the logic changes brings every skin family of its
/// model. Keyed by form too: a still dynamic prop is its sequence 0, an
/// animated one its reference pose with the animation that moves it. A
/// material only a model names gets a fallback entry of its own.
pub(super) fn build_models(
    extracted: &[ModProp],
    riding: &BTreeMap<usize, Vec<ModProp>>,
    prop_bucket_ids: &BTreeMap<String, u32>,
    material_ids: &mut BTreeMap<String, u32>,
    materials: &mut Vec<metadata::MaterialReference>,
) -> Result<BTreeMap<ModelUse, BuiltModel>> {
    let mut model_by_path: BTreeMap<ModelUse, BuiltModel> = BTreeMap::new();
    // The sequences an animated model keeps the frames of: every one any of
    // the map's props of that model can come to play.
    let mut kept_sequences: BTreeMap<String, BTreeSet<usize>> = BTreeMap::new();
    for item in extracted.iter().chain(riding.values().flatten()) {
        if let Some(animated) = &item.animation {
            kept_sequences
                .entry(item.prop.model.clone())
                .or_default()
                .extend(&animated.sequences);
        }
    }
    let wanted = extracted
        .iter()
        .chain(riding.values().flatten())
        .flat_map(|item| {
            std::iter::once((model_use(item, item.prop.skin), item, &item.model)).chain(
                item.skins
                    .iter()
                    .enumerate()
                    .map(move |(family, model)| (model_use(item, family as i32), item, model)),
            )
        });
    for (key, item, model) in wanted {
        if model_by_path.contains_key(&key) {
            continue;
        }
        let (mesh, slots, bindings) =
            crate::output::mesh::from_source_model(model, UNITS_PER_BLOCK)?;
        let slot_ids = slots
            .into_iter()
            .map(|name| {
                if let Some(id) = prop_bucket_ids.get(&name) {
                    return *id;
                }
                if let Some(id) = material_ids.get(&name) {
                    return *id;
                }
                let id = materials.len() as u32;
                material_ids.insert(name.clone(), id);
                materials.push(fallback_material(name));
                id
            })
            .collect::<Vec<_>>();
        let bytes = crate::output::mesh::encode(&mesh)?;
        let id = bundle::content_id(&bytes);
        let animation = match &item.animation {
            Some(animated) => {
                let bytes = crate::output::animation::encode(
                    &animated.animation,
                    model.root,
                    &bindings,
                    &kept_sequences[&item.prop.model],
                )
                .with_context(|| format!("encoding the animation of {}", item.prop.model))?;
                Some((bundle::content_id(&bytes), bytes))
            }
            None => None,
        };
        model_by_path.insert(
            key,
            BuiltModel {
                id,
                slots: slot_ids,
                bytes,
                surface_prop: model.surface_prop.clone(),
                animation,
                hardware: mesh.hardware.clone(),
            },
        );
    }
    Ok(model_by_path)
}

/// A material only a model names, with nothing resolved for it.
fn fallback_material(name: String) -> metadata::MaterialReference {
    metadata::MaterialReference {
        source_material: name,
        source_material_raw: None,
        render_class: metadata::RenderClass::Fallback,
        texture: None,
        surface_prop: None,
        reflectivity: [0.0; 3],
        double_sided: false,
        bump: None,
        envmap: None,
        blend: false,
        blend_modulate: None,
        detail: None,
        selfillum: None,
    }
}

/// Each skin family a prop the logic reskins can wear, as the model key the
/// logic table names.
pub(super) fn logic_skins(
    models: &BTreeMap<ModelUse, BuiltModel>,
    item: &ModProp,
) -> Vec<ModelKey> {
    (0..item.skins.len())
        .map(|family| {
            let built = &models[&model_use(item, family as i32)];
            (
                built.id.clone(),
                item.prop.model.clone(),
                built.slots.clone(),
                item.prop.color,
                built.animation_id(),
            )
        })
        .collect()
}

/// The world's props, and what placing them spans.
pub(super) struct WorldProps {
    pub(super) props: Vec<Prop>,
    pub(super) logic: Vec<LogicPropExport>,
    /// Cells holding a prop root.
    pub(super) taken: std::collections::HashSet<IVec3>,
    pub(super) cell_min: IVec3,
    pub(super) cell_max: IVec3,
}

/// Props go exactly where the map puts them. They used to be settled onto the
/// voxel floor and nudged out of voxel walls, which undid the grid's rounding
/// while surfaces were snapped to it; now surfaces are exact, the same
/// corrections move props off the geometry they really stand on. A root may
/// land in a carrier's cell: it then carries that cell's collision itself, and
/// the carrier is left out. Every section a prop spans goes into
/// `pvs_sections` when the map has a visibility table.
pub(super) fn place_props(
    map: &crate::bsp::Map,
    conversion: &crate::convert::Conversion,
    extracted: Vec<ModProp>,
    models: &BTreeMap<ModelUse, BuiltModel>,
    collision: &mut PropCollision,
    mut pvs_sections: Option<&mut BTreeSet<[i32; 3]>>,
    (cell_min, cell_max): (IVec3, IVec3),
) -> Result<WorldProps> {
    let mut out = WorldProps {
        props: Vec::new(),
        logic: Vec::new(),
        taken: std::collections::HashSet::new(),
        cell_min,
        cell_max,
    };
    for item in extracted {
        if let (Some(role), Some(entity)) = (item.logic, item.prop.entity) {
            out.logic.push(LogicPropExport {
                entity,
                placed: LogicPlacement::World {
                    source_ordinal: item.source_ordinal,
                    source_model: item.prop.model.clone(),
                },
                skins: logic_skins(models, &item),
                skin: item.prop.skin,
                start_hidden: role.start_hidden,
                collision: collision.logic.remove(&entity),
                sequence: item.animation.as_ref().map(|a| a.spawn),
                poses: collision.poses.remove(&entity).unwrap_or_default(),
            });
        }
        let transformed_bounds = conversion.transform.transform_bounds(item.bounds);
        let root_cell =
            crate::output::bake::anchor(&conversion.grid, transformed_bounds, &out.taken)
                .with_context(|| {
                    format!(
                        "{}: no free root cell for prop {}",
                        crate::output::limits::ErrorCode::NoFreePropRoot.as_str(),
                        item.source_ordinal
                    )
                })?;
        out.taken.insert(root_cell);
        if let Some(sections) = pvs_sections.as_deref_mut() {
            insert_bounds_sections(transformed_bounds, sections);
        }
        for (axis, &cell) in root_cell.iter().enumerate() {
            out.cell_max[axis] = out.cell_max[axis].max(cell);
            out.cell_min[axis] = out.cell_min[axis].min(cell);
        }
        let built = &models[&model_use(&item, item.prop.skin)];
        let origin = conversion.transform.to_block_space(item.prop.origin);
        out.props.push(Prop {
            source_ordinal: item.source_ordinal,
            source_model: item.prop.model.clone(),
            model_content_id: built.id.clone(),
            root_cell,
            translation: [origin.x, origin.y, origin.z],
            rotation: crate::output::display::rotation(&item.prop, &conversion.transform),
            scale: item.prop.scale,
            material_ids: built.slots.clone(),
            color: item.prop.color,
            animation: built.animation_id(),
            vertex_light: crate::output::light::prop_vertex_light(
                map,
                item.prop.static_index,
                &built.hardware,
            ),
        });
    }
    if let Some(collision) = collision.collision.as_mut() {
        collision.carriers.retain(|cell| !out.taken.contains(cell));
    }
    Ok(out)
}
