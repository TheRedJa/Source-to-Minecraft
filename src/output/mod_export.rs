//! Assembly of complete v1 campaign bundles and their transport schematics.

use crate::geom::Vec3;
use crate::output::{atlas, bundle, metadata, placement, schem, surface};
use crate::voxel::grid::{IVec3, Palette};
use anyhow::{Context, Result, ensure};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

pub const UNITS_PER_BLOCK: f64 = 32.0;

pub struct ModelAsset {
    pub source_model: String,
    pub bytes: Vec<u8>,
    pub materials: Vec<u32>,
    /// The tint, RGB; white for none.
    pub color: [u8; 3],
    pub surface_prop: Option<String>,
    /// The `.s2anim` payload of an animated prop's model.
    pub animation: Option<Vec<u8>>,
}

/// What identifies a model reference: mesh, provenance, materials, tint and
/// animation.
pub type ModelKey = (String, String, Vec<u32>, [u8; 3], Option<String>);

pub struct TextureAsset {
    pub content_id: String,
    pub bytes: Vec<u8>,
    pub image: image::RgbaImage,
    /// Only the 3D skybox room draws it: packed after every texture the map
    /// draws, so the room never spreads the map's textures over more pages.
    pub room_only: bool,
}

/// Every material of a map and the textures they need.
struct ExtractedMaterials {
    materials: Vec<metadata::MaterialReference>,
    textures: Vec<TextureAsset>,
    /// Per face, map faces then movers' faces.
    face_material_ids: Vec<u32>,
    /// The material ID props draw each material with.
    prop_bucket_ids: BTreeMap<String, u32>,
    /// The material ID the 3D skybox room draws each material with.
    room_material_ids: BTreeMap<String, u32>,
    /// The material ID each particle material draws with.
    effect_material_ids: BTreeMap<String, u32>,
}

pub struct Prop {
    pub source_ordinal: u64,
    pub source_model: String,
    pub model_content_id: String,
    pub root_cell: IVec3,
    pub translation: [f64; 3],
    pub rotation: [f64; 4],
    pub scale: f64,
    pub material_ids: Vec<u32>,
    pub color: [u8; 3],
    /// Content ID of the model's animation, for an animated prop.
    pub animation: Option<String>,
    /// A static prop's `.vhv` light, one colour per mesh vertex (format
    /// section 22); `None` lights it by the ambient cube.
    pub vertex_light: Option<Vec<[u8; 3]>>,
}

/// A prop the map's logic changes (format section 18).
pub struct LogicPropExport {
    pub entity: usize,
    pub placed: LogicPlacement,
    /// The model reference of each skin family, family 0 first.
    pub skins: Vec<ModelKey>,
    /// The skin the map gives it, as written; may name no family.
    pub skin: i32,
    pub start_hidden: bool,
    /// Its own collision table, map-local, when it has collision the logic
    /// can take away.
    pub collision: Option<Vec<u8>>,
    /// The sequence an animated prop spawns in.
    pub sequence: Option<usize>,
    /// For a prop whose collision follows its bones: the collision table of
    /// the pose each sequence leaves it in, by sequence.
    pub poses: Vec<(usize, Vec<u8>)>,
}

pub enum LogicPlacement {
    /// In `props.s2props`, identified as its stable ID is derived.
    World {
        source_ordinal: u64,
        source_model: String,
    },
    /// Riding on this mover.
    Mover(usize),
}

pub struct MapExport {
    pub map_id: String,
    pub source_name: String,
    pub cell_min: IVec3,
    pub cell_max: IVec3,
    pub anchor_cell: IVec3,
    pub blocks: Vec<(IVec3, crate::voxel::grid::BlockId)>,
    pub palette: Palette,
    pub faces: Vec<surface::EncodedFace>,
    pub materials: Vec<metadata::MaterialReference>,
    pub textures: Vec<TextureAsset>,
    pub models: Vec<ModelAsset>,
    pub props: Vec<Prop>,
    /// Encoded prop visibility table; absent when the map has no usable PVS.
    pub pvs: Option<Vec<u8>>,
    /// Encoded light-occlusion mask; absent when no brush is drawn as geometry
    /// or the map was exported with the mask turned off.
    pub occlusion: Option<Vec<u8>>,
    /// Encoded per-cell collision shapes; absent when the map was converted
    /// without them.
    pub collision: Option<Vec<u8>>,
    /// The map's sound; absent when exported without it.
    pub audio: Option<crate::output::audio::AudioExport>,
    /// The map's entity logic; absent when the map has no entities.
    pub logic: Option<crate::output::logic::LogicTable>,
    /// The map's moving entities, in entity lump order (format section 17).
    pub movers: Vec<MoverExport>,
    /// The props the logic changes, in entity lump order (format section 18).
    pub logic_props: Vec<LogicPropExport>,
    /// The map's sky (format section 20); absent when it has no sky face.
    pub sky: Option<crate::output::sky::SkyExport>,
    /// The encoded 3D skybox (format section 21); absent without one.
    pub skybox: Option<Vec<u8>>,
    /// The map's baked light (format section 22); the props' vertex light is
    /// on the props.
    pub light: Option<LightExport>,
    /// The map's particle systems (format section 23); absent when it has
    /// none and its impacts draw nothing.
    pub particles: Option<crate::output::particles::ParticleTable>,
    /// The map's HDR flag and colour lookups (format section 24).
    pub look: crate::output::look::LookTable,
    pub diagnostics: metadata::Diagnostics,
}

/// A static prop's `.vhv` colours, one per mesh vertex.
type VertexLight = Vec<[u8; 3]>;

/// The lightmap pages and ambient samples of a map.
pub struct LightExport {
    pub atlas: crate::output::light::Atlas,
    pub ambient: Option<crate::output::light::AmbientExport>,
}

/// One moving entity, cut out of the world and moved into cells of its own.
pub struct MoverExport {
    /// Index of the entity in the BSP entity lump.
    pub entity: usize,
    /// Lowercase.
    pub classname: String,
    /// The map-local cell of mover-local `[0, 0, 0]`.
    pub cell_origin: IVec3,
    pub size: IVec3,
    /// Mover-local, UVs included.
    pub faces: Vec<surface::EncodedFace>,
    /// Encoded mover-local collision table; absent when nothing collides.
    pub collision: Option<Vec<u8>>,
    /// Mover-local, sorted.
    pub surface_blocks: Vec<IVec3>,
    /// Mover-local, sorted, none of them a surface block.
    pub carrier_blocks: Vec<IVec3>,
    pub props: Vec<MoverPropExport>,
}

/// A prop riding on a mover. The model reference is resolved when the map's
/// model table is, at write time, exactly as for a placed prop.
pub struct MoverPropExport {
    pub entity: usize,
    pub source_model: String,
    pub model_content_id: String,
    pub material_ids: Vec<u32>,
    /// Mover-local block coordinates of the model origin.
    pub translation: [f64; 3],
    pub rotation: [f64; 4],
    pub scale: f64,
    pub skin: i32,
    pub color: [u8; 3],
    pub animation: Option<String>,
}

pub struct WrittenCampaign {
    pub bundle: PathBuf,
    pub schematics: Vec<PathBuf>,
    pub manifest: bundle::Manifest,
}

/// Build one export map from the canonical converter result. The anchor gets
/// its own guaranteed-free layer directly below the map, as agreed for v1.
pub fn from_conversion(
    map: &crate::bsp::Map,
    config: &crate::config::Config,
    conversion: &crate::convert::Conversion,
    quality: atlas::TextureQuality,
    with_audio: bool,
) -> Result<MapExport> {
    ensure!(
        conversion.transform.units_per_block() == UNITS_PER_BLOCK,
        "mod export requires exactly 32 Source units per block"
    );
    let (mut grid_min, mut grid_max) = conversion
        .grid
        .bounds()
        .context("mod export produced an empty map")?;
    let extracted = crate::source::extract::extract_mod_props(map, config);
    crate::timing::mark("export: load prop models");
    // Movers: every brush entity the conversion took out of the world, and
    // every `prop_door_rotating`, a door that is a model rather than brushes.
    // A prop parented to one rides on it and leaves the world with it.
    let entities = crate::bsp::entities::extract(map, &conversion.transform);
    let mover_entities: BTreeSet<usize> = conversion
        .movers
        .iter()
        .map(|mover| mover.entity)
        .chain(
            entities
                .iter()
                .filter(|e| e.classname.eq_ignore_ascii_case("prop_door_rotating"))
                .map(|e| e.index),
        )
        .collect();
    let attachments = crate::output::movers::attach(
        &entities,
        &mover_entities,
        extracted.iter().filter_map(|item| item.prop.entity),
    );
    let mut riding: BTreeMap<usize, Vec<crate::source::extract::ModProp>> = BTreeMap::new();
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
    let extracted = placed;
    let mut collision = conversion.collision.clone();
    let (mut solid_props, mut prop_cells) = (0, 0);
    // A prop the logic can remove keeps its collision in a table of its own,
    // added to the map's cells at runtime while it stands.
    let removable = |item: &crate::source::extract::ModProp| {
        item.logic.is_some_and(|role| role.collision) || follows_pose(item)
    };
    let mut pose_collision: BTreeMap<usize, Vec<(usize, Vec<u8>)>> = BTreeMap::new();
    let mut logic_collision: BTreeMap<usize, Vec<u8>> = BTreeMap::new();
    if let Some(collision) = collision.as_mut() {
        let (merged, apart): (Vec<_>, Vec<_>) = extracted.iter().partition(|item| !removable(item));
        (solid_props, prop_cells) = add_prop_collision(
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
            logic_collision.insert(entity, spawn);
            if !poses.is_empty() {
                pose_collision.insert(entity, poses);
            }
        }
    }
    crate::timing::mark("export: prop collision");
    // Carriers are blocks too, and may sit a cell outside the grid: the anchor
    // layer below the map must stay free of them.
    for cell in collision.iter().flat_map(|c| &c.carriers) {
        for axis in 0..3 {
            grid_min[axis] = grid_min[axis].min(cell[axis]);
            grid_max[axis] = grid_max[axis].max(cell[axis]);
        }
    }
    let anchor_cell = [
        grid_min[0],
        grid_min[1]
            .checked_sub(1)
            .context("anchor coordinate underflow")?,
        grid_min[2],
    ];
    let mut palette = Palette::new();
    let surface_block = palette.intern("src2mc:surface");
    palette.intern("src2mc:map_anchor");
    palette.intern("src2mc:prop_root");
    let carrier_block = palette.intern("src2mc:carrier");
    // Prop and surface sections both drive the visibility table: every
    // 16-block section a transformed prop box spans, or a surface face's
    // cell falls in, collects the clusters of leaves whose boxes overlap it,
    // so the runtime can reject sections no visible leaf covers.
    let mut pvs_sections: BTreeSet<[i32; 3]> = BTreeSet::new();
    let visibility = crate::bsp::pvs::ClusterVisibility::from_map(map);
    let pvs_index = visibility.as_ref().map(|visibility| {
        crate::bsp::pvs::LeafSectionIndex::build(visibility, |corner| {
            let block = conversion
                .transform
                .to_block_space(crate::geom::Vec3::new(corner[0], corner[1], corner[2]));
            [block.x, block.y, block.z]
        })
    });
    if pvs_index.is_some() {
        insert_surface_sections(&conversion.fragments, &mut pvs_sections);
    }
    crate::timing::mark("export: PVS index");
    // Model UVs are normalized sheet coordinates. Preserve enough pixels for
    // the largest world-space use of each sheet; otherwise model-only
    // materials never enter the atlas and every prop silently becomes a
    // fallback material.
    let mut prop_texture_spans = BTreeMap::<String, f64>::new();
    for item in extracted.iter().chain(riding.values().flatten()) {
        for part in &item.model.parts {
            if part.uv_per_unit.is_finite() && part.uv_per_unit > 0.0 {
                let blocks = 1.0 / (part.uv_per_unit * UNITS_PER_BLOCK);
                prop_texture_spans
                    .entry(part.material.clone())
                    .and_modify(|old| *old = old.max(blocks))
                    .or_insert(blocks);
            }
        }
    }
    // The 3D skybox room draws through the sky faces with the map's atlas.
    let skybox_room = config
        .contents
        .skip_3d_skybox
        .then(|| map.skybox())
        .flatten();
    let skybox_vfs =
        crate::source::vfs::Vfs::for_map(&map.path, &config.materials.game_dir_paths());
    let skybox = crate::output::skybox::build(
        map,
        &skybox_vfs,
        &conversion.transform,
        skybox_room,
        visibility.as_ref().map(|v| v.rows.len()),
    )
    .context("exporting the 3D skybox")?;
    crate::timing::mark("export: 3D skybox");
    // The movers' faces are the map's faces too: they share its material table
    // and atlas, so they are resolved in the same pass, after the world's.
    let all_fragments: Vec<&crate::voxel::fragments::Fragment> = conversion
        .fragments
        .iter()
        .chain(conversion.movers.iter().flat_map(|mover| &mover.fragments))
        .collect();
    // Every lightmap a drawn face wears, the movers' too, on one set of pages.
    let lit_faces: BTreeSet<usize> = all_fragments
        .iter()
        .filter_map(|fragment| fragment.source.light.map(|light| light.face))
        .collect();
    let light_atlas = crate::output::light::Atlas::build(&map.light, &lit_faces)
        .context("packing the map's lightmaps")?;
    crate::timing::mark("export: lightmaps");
    // The room gets textures of its own, at the detail of its size as the
    // player sees it, enlarged by its scale; the map's materials keep theirs.
    // Asking room detail of the map's own textures enlarged every texture the
    // two share: furnace's atlas grew from 1 page to 6 and its props' shadow
    // pass slowed with a shader pack (user, 2026-10-05).
    let room_texture_spans: BTreeMap<String, f64> = skybox
        .as_ref()
        .map(|skybox| {
            let scale = f64::from(skybox.scale.max(1.0));
            skybox
                .texture_spans()
                .into_iter()
                .map(|(material, span)| (material, span * scale))
                .collect()
        })
        .unwrap_or_default();
    let particle_vfs =
        crate::source::vfs::Vfs::for_map(&map.path, &config.materials.game_dir_paths());
    let particles_collected = crate::output::particles::collect(map, &particle_vfs);
    let ExtractedMaterials {
        mut materials,
        textures,
        face_material_ids,
        prop_bucket_ids,
        room_material_ids: skybox_material_ids,
        effect_material_ids,
    } = extract_materials(
        map,
        config,
        &prop_texture_spans,
        &room_texture_spans,
        &particles_collected.materials,
        &all_fragments,
        quality,
    );
    drop(all_fragments);
    crate::timing::mark("export: materials and textures");
    let (particles, particle_diagnostics) = {
        let resolver = crate::source::vmt::Materials::new(&particle_vfs, Some(&map.bsp.pack));
        let decoder =
            crate::source::vtf::Textures::new(&particle_vfs, config.materials.texture_size);
        crate::output::particles::finish(
            particles_collected,
            &effect_material_ids,
            &resolver,
            &decoder,
        )
    };
    let (look, look_diagnostics) = crate::output::look::collect(map, &particle_vfs);
    drop(particle_vfs);
    crate::timing::mark("export: particles");
    let skybox_diagnostics = skybox
        .as_ref()
        .map_or_else(Vec::new, |s| s.diagnostics.clone());
    let skybox_table = skybox
        .as_ref()
        .map(|skybox| crate::output::skybox::encode(skybox, &skybox_material_ids))
        .transpose()
        .context("encoding the 3D skybox")?;
    drop(skybox);
    let (face_material_ids, mut mover_material_ids) =
        face_material_ids.split_at(conversion.fragments.len());
    let mut mover_face_ids: BTreeMap<usize, &[u32]> = BTreeMap::new();
    for mover in &conversion.movers {
        let (ids, rest) = mover_material_ids.split_at(mover.fragments.len());
        mover_face_ids.insert(mover.entity, ids);
        mover_material_ids = rest;
    }
    let faces = conversion
        .fragments
        .iter()
        .zip(face_material_ids.iter().copied())
        .map(|(fragment, material_id)| {
            let mut face =
                surface::EncodedFace::from_fragment(fragment, surface::MaterialId(material_id));
            face.light = fragment
                .source
                .light
                .and_then(|light| light_atlas.region(&light));
            face
        })
        .collect();
    let mut material_ids: BTreeMap<String, u32> =
        materials
            .iter()
            .enumerate()
            .fold(BTreeMap::new(), |mut ids, (i, m)| {
                ids.entry(m.source_material.clone()).or_insert(i as u32);
                ids
            });
    // Keyed by skin and tint as well: one mesh, but each skin its own
    // material slots, and each tint its own reference. A prop the logic
    // changes brings every skin family of its model.
    // Keyed by form too: a still dynamic prop is its sequence 0, an animated
    // one its reference pose with the animation that moves it.
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
                materials.push(metadata::MaterialReference {
                    source_material: name,
                    source_material_raw: None,
                    render_class: metadata::RenderClass::Fallback,
                    texture: None,
                    surface_prop: None,
                    reflectivity: [0.0; 3],
                    double_sided: false,
                });
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
    crate::timing::mark("export: prop meshes");
    // Props go exactly where the map puts them. They used to be settled onto
    // the voxel floor and nudged out of voxel walls, which undid the grid's
    // rounding while surfaces were snapped to it; now surfaces are exact, the
    // same corrections move props off the geometry they really stand on.
    // A root may land in a carrier's cell: it then carries that cell's
    // collision itself, and the carrier is left out.
    let mut taken = std::collections::HashSet::new();
    let mut props = Vec::new();
    let mut diagnostics = Vec::new();
    let mut cell_max = grid_max;
    let mut cell_min = anchor_cell;
    let mut logic_props = Vec::new();
    let logic_skins = |item: &crate::source::extract::ModProp| -> Vec<ModelKey> {
        (0..item.skins.len())
            .map(|family| {
                let built = &model_by_path[&model_use(item, family as i32)];
                (
                    built.id.clone(),
                    item.prop.model.clone(),
                    built.slots.clone(),
                    item.prop.color,
                    built.animation_id(),
                )
            })
            .collect()
    };
    for item in extracted {
        if let (Some(role), Some(entity)) = (item.logic, item.prop.entity) {
            logic_props.push(LogicPropExport {
                entity,
                placed: LogicPlacement::World {
                    source_ordinal: item.source_ordinal,
                    source_model: item.prop.model.clone(),
                },
                skins: logic_skins(&item),
                skin: item.prop.skin,
                start_hidden: role.start_hidden,
                collision: logic_collision.remove(&entity),
                sequence: item.animation.as_ref().map(|a| a.spawn),
                poses: pose_collision.remove(&entity).unwrap_or_default(),
            });
        }
        let transformed_bounds = conversion.transform.transform_bounds(item.bounds);
        let root_cell = crate::output::bake::anchor(&conversion.grid, transformed_bounds, &taken)
            .with_context(|| {
            format!(
                "{}: no free root cell for prop {}",
                crate::output::limits::ErrorCode::NoFreePropRoot.as_str(),
                item.source_ordinal
            )
        })?;
        taken.insert(root_cell);
        if pvs_index.is_some() {
            for x in section_coord(transformed_bounds.min.x)
                ..=section_coord(transformed_bounds.max.x - 1.0e-8)
            {
                for y in section_coord(transformed_bounds.min.y)
                    ..=section_coord(transformed_bounds.max.y - 1.0e-8)
                {
                    for z in section_coord(transformed_bounds.min.z)
                        ..=section_coord(transformed_bounds.max.z - 1.0e-8)
                    {
                        pvs_sections.insert([x, y, z]);
                    }
                }
            }
        }
        for axis in 0..3 {
            cell_max[axis] = cell_max[axis].max(root_cell[axis]);
            cell_min[axis] = cell_min[axis].min(root_cell[axis]);
        }
        let built = &model_by_path[&model_use(&item, item.prop.skin)];
        let origin = conversion.transform.to_block_space(item.prop.origin);
        props.push(Prop {
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
    if let Some(collision) = collision.as_mut() {
        collision.carriers.retain(|cell| !taken.contains(cell));
    }
    // Each mover, in lump order, in cells of its own.
    let no_blocks = crate::voxel::grid::VoxelGrid::new();
    let geometry: BTreeMap<usize, &crate::convert::MoverGeometry> = conversion
        .movers
        .iter()
        .map(|mover| (mover.entity, mover))
        .collect();
    let mut movers = Vec::new();
    let (mut attached_props, mut empty_movers, mut thin_movers) = (0, 0, 0);
    for &entity in &mover_entities {
        let shape = geometry.get(&entity).copied();
        let grid = shape.map_or(&no_blocks, |mover| &mover.grid);
        let mut mover_collision = shape
            .map(|mover| mover.collision.clone())
            .unwrap_or_default();
        let riders = riding.remove(&entity).unwrap_or_default();
        // Brushes drawn nowhere collide on a mover that is there anyway, but do
        // not make one of an entity that is nothing else: hundreds of INFRA's
        // invisible button volumes would each become a sub-level.
        if shape.is_some_and(|mover| mover.only_unseen) && riders.is_empty() {
            mover_collision = Default::default();
        }
        // `CFuncIllusionary` is `SOLID_NONE`; one is a mover only when a
        // `func_areaportalwindow` fades it.
        if shape.is_some_and(|mover| mover.classname.eq_ignore_ascii_case("func_illusionary")) {
            mover_collision = Default::default();
        }
        // A rider the logic can take the collision of, or whose collision
        // follows its pose, keeps it in tables of its own, mover-local once
        // the mover's cells are known; the rest is the mover's.
        let (merged, apart): (Vec<_>, Vec<_>) = riders.iter().partition(|item| !removable(item));
        add_prop_collision(
            config,
            &conversion.transform,
            grid,
            &merged,
            &mut mover_collision,
        );
        let mut rider_collision = BTreeMap::new();
        for item in apart {
            if let Some(own) = apart_collision(
                config,
                &conversion.transform,
                grid,
                item,
                &mut mover_collision,
            ) {
                rider_collision.insert(
                    item.prop.entity.context("logic prop without an entity")?,
                    own,
                );
            }
        }
        let mut rider_logic = Vec::new();
        let mut props = Vec::with_capacity(riders.len());
        for item in &riders {
            let built = &model_by_path[&model_use(item, item.prop.skin)];
            let origin = conversion.transform.to_block_space(item.prop.origin);
            let bounds = conversion.transform.transform_bounds(item.bounds);
            if let (Some(role), Some(prop_entity)) = (item.logic, item.prop.entity) {
                rider_logic.push(LogicPropExport {
                    entity: prop_entity,
                    placed: LogicPlacement::Mover(entity),
                    skins: logic_skins(item),
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
        let ids = mover_face_ids.get(&entity).copied().unwrap_or(&[]);
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
                // Lit as the map's faces are, on the map's pages; the region
                // moves with the faces into mover-local cells.
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
                for mut record in rider_logic {
                    if let Some(own) = rider_collision.remove(&record.entity) {
                        (record.collision, record.poses) = own
                            .encode(mover.cell_origin)
                            .map(|(spawn, poses)| (Some(spawn), poses))?;
                    }
                    logic_props.push(record);
                }
                attached_props += mover.props.len();
                if mover.surface_blocks.is_empty() {
                    thin_movers += 1;
                }
                movers.push(mover);
            }
            None => {
                logic_props.extend(rider_logic);
                empty_movers += 1;
            }
        }
    }
    let blocks = conversion
        .grid
        .iter()
        .map(|(cell, _)| (cell, surface_block))
        .chain(
            collision
                .iter()
                .flat_map(|c| &c.carriers)
                .map(|cell| (*cell, carrier_block)),
        )
        .collect();

    // Brushes too thin to voxelize are no longer drawn as props of their own:
    // their faces are in the face lump like any other and arrive as surface
    // fragments, unowned because the cells they sit in hold no block.
    if !conversion.fragments.is_empty() {
        let owned = conversion
            .fragments
            .iter()
            .filter(|f| f.owner.is_some())
            .count();
        let mut context = BTreeMap::new();
        context.insert("fragments".into(), conversion.fragments.len().to_string());
        context.insert("owned".into(), owned.to_string());
        context.insert(
            "unowned".into(),
            (conversion.fragments.len() - owned).to_string(),
        );
        context.insert(
            "faces_without_brush".into(),
            conversion.stats.exact_faces_unmatched.to_string(),
        );
        diagnostics.push(metadata::Diagnostic {
            severity: metadata::Severity::Info,
            code: "SURFACE_FRAGMENT_SUMMARY".into(),
            message: "exact visible faces cut into per-cell fragments; unowned ones sit in cells with no block".into(),
            context,
        });
    }
    // Every cell that stops daylight: the drawn thin brushes, which hold no
    // block, and every block of the map. The mod's sky bake cannot see the
    // world's blocks, only this bundle, and the only blocks the surface table
    // names are those that own a visible fragment. Without the rest, daylight
    // seeped through the map's hidden mass into the air hollowing leaves
    // inside thick floors and walls, and anything lit from such a pocket --
    // the foot of a wall below a terrain floor -- glowed in a dark room.
    let mut light_blockers = conversion.occluders.clone();
    light_blockers.extend(conversion.grid.iter().map(|(cell, _)| cell));
    if !light_blockers.is_empty() {
        let mut context = BTreeMap::new();
        context.insert("cells".into(), light_blockers.len().to_string());
        context.insert(
            "without_block".into(),
            conversion.occluders.len().to_string(),
        );
        diagnostics.push(metadata::Diagnostic {
            severity: metadata::Severity::Info,
            code: "LIGHT_OCCLUSION_SUMMARY".into(),
            message: "cells recorded as blocking daylight: every block of the map, and drawn geometry that holds none".into(),
            context,
        });
    }
    if !mover_entities.is_empty() {
        let mut context = BTreeMap::new();
        context.insert("movers".into(), movers.len().to_string());
        context.insert("without_blocks".into(), thin_movers.to_string());
        context.insert("empty".into(), empty_movers.to_string());
        context.insert("attached_props".into(), attached_props.to_string());
        context.insert(
            "ambiguous_parents".into(),
            attachments.ambiguous.len().to_string(),
        );
        diagnostics.push(metadata::Diagnostic {
            severity: metadata::Severity::Info,
            code: "MOVER_SUMMARY".into(),
            message: "moving entities cut out of the world into the mover table; empty ones had nothing to draw, collide with or carry".into(),
            context,
        });
    }
    if !attachments.ambiguous.is_empty() {
        let mut context = BTreeMap::new();
        context.insert(
            "targetnames".into(),
            attachments
                .ambiguous
                .iter()
                .cloned()
                .collect::<Vec<_>>()
                .join(","),
        );
        diagnostics.push(metadata::Diagnostic {
            severity: metadata::Severity::Warning,
            code: "MOVER_PARENT_AMBIGUOUS".into(),
            message: "props are parented to a name several entities share; each rides on the first mover of that name in lump order".into(),
            context,
        });
    }
    crate::timing::mark("export: prop roots and diagnostics");
    let models: Vec<ModelAsset> = model_by_path
        .into_iter()
        .map(|((source_model, _, color, _), built)| ModelAsset {
            source_model,
            bytes: built.bytes,
            materials: built.slots,
            color,
            surface_prop: built.surface_prop,
            animation: built.animation.map(|(_, bytes)| bytes),
        })
        .collect();
    let pvs = if let Some((visibility, index)) = visibility.as_ref().zip(pvs_index.as_ref()) {
        let rows = visibility.rows.clone();
        let Some((root, nodes)) =
            crate::output::pvs::tree_from_visibility(visibility, &conversion.transform)
        else {
            return Err(anyhow::anyhow!("validated PVS has no exportable BSP tree"));
        };
        let sections: BTreeMap<[i32; 3], BTreeSet<u16>> = pvs_sections
            .iter()
            .map(|&section| (section, index.clusters_in_section(section)))
            .filter(|(_, clusters)| !clusters.is_empty())
            .collect();
        Some(
            crate::output::pvs::encode(rows, nodes, root, sections)
                .context("encoding validated PVS table")?,
        )
    } else {
        None
    };
    let collision = match &collision {
        Some(collision) => {
            let mut context = BTreeMap::new();
            context.insert("solid_props".into(), solid_props.to_string());
            context.insert("prop_cells".into(), prop_cells.to_string());
            context.insert("shaped_cells".into(), collision.shapes.len().to_string());
            context.insert("carriers".into(), collision.carriers.len().to_string());
            context.insert("attached_pieces".into(), collision.attached.to_string());
            context.insert(
                "empty_blocks".into(),
                collision
                    .shapes
                    .iter()
                    .filter(|(cell, boxes)| boxes.is_empty() && !collision.carriers.contains(*cell))
                    .count()
                    .to_string(),
            );
            diagnostics.push(metadata::Diagnostic {
                severity: metadata::Severity::Info,
                code: "COLLISION_SUMMARY".into(),
                message: "cells that collide as something other than a full block".into(),
                context,
            });
            Some(
                crate::output::cell_collision::encode(&collision.shapes)
                    .context("encoding the collision table")?,
            )
        }
        None => None,
    };
    crate::timing::mark("export: PVS and collision encoding");
    let occlusion = (!light_blockers.is_empty())
        .then(|| crate::output::occlusion::encode(&light_blockers))
        .transpose()
        .context("encoding the light-occlusion mask")?;
    // Scenes come first: the sound table carries the lines they speak, and
    // the logic table the captions of the sound table's scripts.
    let vfs = crate::source::vfs::Vfs::for_map(&map.path, &config.materials.game_dir_paths());
    let pak = Some(&map.bsp.pack);
    let scenes = crate::output::logic::Scenes::load(&vfs, pak, &entities);
    let audio = if with_audio {
        // What a surface sounds like comes from its material's
        // `$surfaceprop`, and a prop's from its model's.
        let surface_props: BTreeSet<String> = materials
            .iter()
            .filter_map(|m| m.surface_prop.clone())
            .chain(models.iter().filter_map(|m| m.surface_prop.clone()))
            .collect();
        let audio = crate::output::audio::build(
            map,
            &vfs,
            &entities,
            &surface_props,
            &scenes.speak_scripts(),
        )
        .context("exporting the map's sound")?;
        diagnostics.extend(audio.diagnostics.iter().cloned());
        crate::timing::mark("export: audio");
        (!audio.table.is_empty()).then_some(audio)
    } else {
        None
    };
    let logic = if entities.is_empty() {
        None
    } else {
        let audio_scripts: BTreeSet<String> = audio
            .iter()
            .flat_map(|a| &a.table.scripts)
            .map(|s| s.name.clone())
            .collect();
        let logic = crate::output::logic::build(
            map,
            &vfs,
            &conversion.transform,
            &entities,
            scenes,
            &audio_scripts,
        )
        .context("exporting the map's logic")?;
        diagnostics.extend(logic.diagnostics);
        crate::timing::mark("export: logic");
        Some(logic.table)
    };
    let skybox = config
        .contents
        .skip_3d_skybox
        .then(|| map.skybox())
        .flatten();
    let sky = crate::output::sky::build(map, &vfs, &conversion.transform, skybox)
        .context("exporting the map's sky")?;
    if let Some(sky) = &sky {
        diagnostics.extend(sky.diagnostics.iter().cloned());
    }
    diagnostics.extend(skybox_diagnostics);
    diagnostics.extend(particle_diagnostics);
    diagnostics.extend(look_diagnostics);
    crate::timing::mark("export: sky");
    Ok(MapExport {
        map_id: portable_id(&map.name),
        source_name: map.name.clone(),
        cell_min,
        cell_max,
        anchor_cell,
        blocks,
        palette,
        faces,
        materials,
        textures,
        models,
        props,
        pvs,
        occlusion,
        collision,
        audio,
        logic,
        movers,
        logic_props,
        sky,
        skybox: skybox_table,
        light: Some(LightExport {
            ambient: crate::output::light::ambient(map, &conversion.transform),
            atlas: light_atlas,
        }),
        particles,
        look,
        diagnostics: metadata::Diagnostics::new(diagnostics)?,
    })
}

/// A model as a prop wears it: path, skin, tint, and form (0 as loaded, 1 a
/// still dynamic prop's sequence 0, 2 an animated prop's reference pose).
type ModelUse = (String, i32, [u8; 3], u8);

fn model_use(item: &crate::source::extract::ModProp, skin: i32) -> ModelUse {
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
struct BuiltModel {
    id: String,
    slots: Vec<u32>,
    bytes: Vec<u8>,
    surface_prop: Option<String>,
    animation: Option<(String, Vec<u8>)>,
    /// Each mesh vertex's `.vtx` hardware vertex, for `.vhv` light.
    hardware: Vec<u32>,
}

impl BuiltModel {
    fn animation_id(&self) -> Option<String> {
        self.animation.as_ref().map(|(id, _)| id.clone())
    }
}

/// A placed animated prop, whose collision follows its pose. Source only
/// moves the collision of a model of several physics solids, its bone
/// followers; but props collide here as their drawn mesh, not their `.phy`,
/// and the clip brushes a map moves along with a single-solid door are left
/// out, so every animated prop follows (user, 2026-10-04, until `.phy`
/// collision replaces the mesh), riding a mover or not.
fn follows_pose(item: &crate::source::extract::ModProp) -> bool {
    item.animation.is_some()
}

/// Move one mover's geometry and riders out of map-local cells into its own.
///
/// The origin is the lowest cell anything of the mover is in, so every
/// mover-local cell is at least zero: its blocks, its carriers, the cells its
/// faces lie in and belong to, a cell its collision reaches into, and for each
/// prop the cells of its box and the cell its origin is in. `None` when the
/// mover has none of these, so nothing to draw, collide with or carry.
fn localize(
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
fn add_prop_collision(
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
fn prop_volume(
    config: &crate::config::Config,
    transform: &crate::voxel::transform::Transform,
    item: &crate::source::extract::ModProp,
) -> Option<std::collections::HashMap<IVec3, crate::voxel::collision::SubCells>> {
    prop_volume_of(config, transform, item, item.standing_model())
}

/// [`prop_volume`] with the prop's model in another pose.
fn prop_volume_of(
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
struct ApartCollision {
    spawn: BTreeMap<IVec3, Vec<crate::voxel::collision::Box16>>,
    poses: Vec<(usize, BTreeMap<IVec3, Vec<crate::voxel::collision::Box16>>)>,
}

impl ApartCollision {
    /// Both encoded, with `origin` moved to cell zero.
    #[allow(clippy::type_complexity)]
    fn encode(self, origin: IVec3) -> Result<(Vec<u8>, Vec<(usize, Vec<u8>)>)> {
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
fn apart_collision(
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
fn separate_prop_collision(
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
enum Contrib {
    Face(usize),
    Prop,
}

/// Group contributions by the exact output resolution `analyze_resolution`
/// would assign them, so faces/props needing the same resolution share one
/// texture and nobody is forced onto another use's worst-case size.
fn bucket_by_output(
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

fn assign_material(face_material_ids: &mut [u32], face_indices: &[usize], material_index: u32) {
    for &face_index in face_indices {
        face_material_ids[face_index] = material_index;
    }
}

fn extract_materials(
    map: &crate::bsp::Map,
    config: &crate::config::Config,
    prop_texture_spans: &BTreeMap<String, f64>,
    room_texture_spans: &BTreeMap<String, f64>,
    effect_materials: &BTreeSet<String>,
    surfaces: &[&crate::voxel::fragments::Fragment],
    quality: atlas::TextureQuality,
) -> ExtractedMaterials {
    use rayon::prelude::*;
    let vfs = crate::source::vfs::Vfs::for_map(&map.path, &config.materials.game_dir_paths());
    let resolver = crate::source::vmt::Materials::new(&vfs, Some(&map.bsp.pack));
    let mut decoder = crate::source::vtf::Textures::new(&vfs, config.materials.texture_size);
    // Decoding, resampling and encoding every texture is most of the work, and
    // which textures are wanted does not depend on whether any of them decode.
    // So a first pass only records the requests, they are all produced in
    // parallel, and the real pass reads the results.
    let mut requests: BTreeSet<TextureRequest> = BTreeSet::new();
    let _ = assign_materials(
        map,
        prop_texture_spans,
        room_texture_spans,
        effect_materials,
        surfaces,
        &resolver,
        &mut decoder,
        quality,
        &mut |request| {
            requests.insert(request);
            None
        },
    );
    let produced: BTreeMap<TextureRequest, Option<(Vec<u8>, image::RgbaImage)>> = requests
        .into_par_iter()
        .map(|request| {
            let image = decoder
                .resized(&request.texture, request.output, request.alpha_test)
                .and_then(|mut image| {
                    if request.opaque {
                        crate::source::vtf::force_opaque(&mut image);
                    }
                    let bytes = crate::source::vtf::to_png(&image).ok()?;
                    Some((bytes, image))
                });
            (request, image)
        })
        .collect();
    assign_materials(
        map,
        prop_texture_spans,
        room_texture_spans,
        effect_materials,
        surfaces,
        &resolver,
        &mut decoder,
        quality,
        &mut |request| produced.get(&request).cloned().flatten(),
    )
}

/// One texture as a material needs it: the file, the size, and how its alpha
/// is treated.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct TextureRequest {
    texture: String,
    output: [u32; 2],
    alpha_test: bool,
    opaque: bool,
}

/// Give every map and prop material its reference and texture, taking each
/// texture's PNG bytes and image from `produce`.
#[allow(clippy::type_complexity)]
#[allow(clippy::too_many_arguments)]
fn assign_materials(
    map: &crate::bsp::Map,
    prop_texture_spans: &BTreeMap<String, f64>,
    room_texture_spans: &BTreeMap<String, f64>,
    effect_materials: &BTreeSet<String>,
    surfaces: &[&crate::voxel::fragments::Fragment],
    resolver: &crate::source::vmt::Materials,
    decoder: &mut crate::source::vtf::Textures,
    quality: atlas::TextureQuality,
    produce: &mut dyn FnMut(TextureRequest) -> Option<(Vec<u8>, image::RgbaImage)>,
) -> ExtractedMaterials {
    let mut assets_by_id = BTreeMap::new();
    let face_rates = per_face_rates(surfaces);
    let mut faces_by_material: Vec<Vec<usize>> = vec![Vec::new(); map.materials().len()];
    for (face_index, face) in surfaces.iter().enumerate() {
        if let Some(bucket) = faces_by_material.get_mut(face.source.material) {
            bucket.push(face_index);
        }
    }
    let mut face_material_ids: Vec<u32> = vec![0; surfaces.len()];
    let mut prop_bucket_ids: BTreeMap<String, u32> = BTreeMap::new();
    let mut materials: Vec<metadata::MaterialReference> = Vec::new();

    for (index, material) in map.materials().iter().enumerate() {
        let base_reference = metadata::MaterialReference {
            source_material: material.name.clone(),
            source_material_raw: (material.raw_name != material.name)
                .then(|| material.raw_name.clone()),
            render_class: metadata::RenderClass::Fallback,
            texture: None,
            surface_prop: None,
            reflectivity: material.reflectivity,
            double_sided: false,
        };
        let face_indices = &faces_by_material[index];
        let Some(material_assets) = resolver.assets(&material.name, Some(&material.raw_name))
        else {
            materials.push(base_reference);
            assign_material(
                &mut face_material_ids,
                face_indices,
                (materials.len() - 1) as u32,
            );
            continue;
        };
        let mut reference_template = base_reference;
        reference_template.render_class = if material_assets.alpha_test {
            metadata::RenderClass::Cutout
        } else if material_assets.translucent {
            metadata::RenderClass::Translucent
        } else {
            metadata::RenderClass::Solid
        };
        reference_template.surface_prop = material_assets.surface_prop;
        reference_template.double_sided = material_assets.no_cull;
        let Some(header) = decoder.header(&material_assets.base_texture) else {
            materials.push(reference_template);
            assign_material(
                &mut face_material_ids,
                face_indices,
                (materials.len() - 1) as u32,
            );
            continue;
        };
        let mut contributions: Vec<(Contrib, [f64; 2])> = Vec::new();
        for &face_index in face_indices {
            let Some(rate) = face_rates[face_index] else {
                continue;
            };
            let blocks_spanned = std::array::from_fn(|axis| header.size[axis] as f64 / rate[axis]);
            contributions.push((Contrib::Face(face_index), blocks_spanned));
        }
        let prop_span = prop_texture_spans.get(&material.name).copied();
        if let Some(prop) = prop_span {
            contributions.push((Contrib::Prop, [prop; 2]));
        }
        let buckets = bucket_by_output(header.size, quality, contributions);
        if buckets.is_empty() {
            materials.push(reference_template);
            assign_material(
                &mut face_material_ids,
                face_indices,
                (materials.len() - 1) as u32,
            );
            continue;
        }
        let mut assigned: BTreeSet<usize> = BTreeSet::new();
        let mut first_bucket_material_index: Option<u32> = None;
        for (output, contribs) in buckets {
            let Some((bytes, image)) = produce(TextureRequest {
                texture: material_assets.base_texture.clone(),
                output,
                alpha_test: material_assets.alpha_test,
                opaque: !material_assets.alpha_test && !material_assets.translucent,
            }) else {
                continue;
            };
            let content_id = bundle::content_id(&bytes);
            assets_by_id
                .entry(content_id.clone())
                .or_insert((bytes, image));
            let mut reference = reference_template.clone();
            reference.texture = Some(metadata::TextureReference {
                content_id,
                original_width: header.size[0],
                original_height: header.size[1],
                output_width: output[0],
                output_height: output[1],
            });
            materials.push(reference);
            let material_index = (materials.len() - 1) as u32;
            first_bucket_material_index.get_or_insert(material_index);
            for contrib in contribs {
                match contrib {
                    Contrib::Face(face_index) => {
                        face_material_ids[face_index] = material_index;
                        assigned.insert(face_index);
                    }
                    Contrib::Prop => {
                        prop_bucket_ids.insert(material.name.clone(), material_index);
                    }
                }
            }
        }
        let unassigned: Vec<usize> = face_indices
            .iter()
            .copied()
            .filter(|face_index| !assigned.contains(face_index))
            .collect();
        if !unassigned.is_empty() {
            let fallback_index = first_bucket_material_index.unwrap_or_else(|| {
                materials.push(reference_template.clone());
                (materials.len() - 1) as u32
            });
            assign_material(&mut face_material_ids, &unassigned, fallback_index);
        }
    }
    let existing: BTreeSet<String> = materials
        .iter()
        .map(|material| material.source_material.clone())
        .collect();
    for (name, blocks_spanned) in prop_texture_spans {
        if existing.contains(name.as_str()) {
            continue;
        }
        let reference = standalone_material(
            name,
            *blocks_spanned,
            false,
            resolver,
            decoder,
            quality,
            produce,
            &mut assets_by_id,
        );
        let textured = reference.texture.is_some();
        materials.push(reference);
        if textured {
            prop_bucket_ids.insert(name.clone(), (materials.len() - 1) as u32);
        }
    }
    let drawn_by_map: BTreeSet<String> = assets_by_id.keys().cloned().collect();
    let mut room_material_ids = BTreeMap::new();
    for (name, blocks_spanned) in room_texture_spans {
        let reference = standalone_material(
            name,
            *blocks_spanned,
            false,
            resolver,
            decoder,
            quality,
            produce,
            &mut assets_by_id,
        );
        if reference.texture.is_some() {
            materials.push(reference);
            room_material_ids.insert(name.clone(), (materials.len() - 1) as u32);
        }
    }
    // Particle textures keep their own resolution, alpha and all: a sprite
    // sheet is many small frames, and an additive sprite still fades by its
    // alpha. Packed after the map's, as the room's are.
    let mut effect_material_ids = BTreeMap::new();
    for name in effect_materials {
        let reference = standalone_material(
            name,
            EFFECT_SPAN,
            true,
            resolver,
            decoder,
            quality,
            produce,
            &mut assets_by_id,
        );
        if reference.texture.is_some() {
            materials.push(reference);
            effect_material_ids.insert(name.clone(), (materials.len() - 1) as u32);
        }
    }
    let textures = assets_by_id
        .into_iter()
        .map(|(content_id, (bytes, image))| TextureAsset {
            room_only: !drawn_by_map.contains(&content_id),
            content_id,
            bytes,
            image,
        })
        .collect();
    ExtractedMaterials {
        materials,
        textures,
        face_material_ids,
        prop_bucket_ids,
        room_material_ids,
        effect_material_ids,
    }
}

/// A projection span large enough that a particle texture keeps every texel
/// it has.
const EFFECT_SPAN: f64 = 1.0e6;

/// A material drawn outside the map's faces, by props or the 3D skybox room,
/// with one texture of the detail `blocks_spanned` asks; without a texture
/// when it cannot be resolved or decoded.
#[allow(clippy::too_many_arguments)]
fn standalone_material(
    name: &str,
    blocks_spanned: f64,
    effect: bool,
    resolver: &crate::source::vmt::Materials,
    decoder: &mut crate::source::vtf::Textures,
    quality: atlas::TextureQuality,
    produce: &mut dyn FnMut(TextureRequest) -> Option<(Vec<u8>, image::RgbaImage)>,
    assets_by_id: &mut BTreeMap<String, (Vec<u8>, image::RgbaImage)>,
) -> metadata::MaterialReference {
    let mut reference = metadata::MaterialReference {
        source_material: name.to_string(),
        source_material_raw: None,
        render_class: metadata::RenderClass::Fallback,
        texture: None,
        surface_prop: None,
        reflectivity: [0.0; 3],
        double_sided: false,
    };
    let Some(material_assets) = resolver.assets(name, None) else {
        return reference;
    };
    reference.render_class = if effect {
        metadata::RenderClass::Translucent
    } else if material_assets.alpha_test {
        metadata::RenderClass::Cutout
    } else if material_assets.translucent {
        metadata::RenderClass::Translucent
    } else {
        metadata::RenderClass::Solid
    };
    reference.surface_prop = material_assets.surface_prop;
    reference.double_sided = material_assets.no_cull;
    let Some(header) = decoder.header(&material_assets.base_texture) else {
        return reference;
    };
    reference.reflectivity = header.reflectivity;
    let Ok(mut decision) = atlas::analyze_resolution(header.size, [blocks_spanned; 2], quality)
    else {
        return reference;
    };
    if effect {
        // Particles and decals draw from one atlas region; a texture wider
        // than a page's usable area would be split into several.
        decision.output = atlas::fit_one_region(decision.output);
        decision.resampled = decision.output != decision.original;
    }
    let Some((bytes, image)) = produce(TextureRequest {
        texture: material_assets.base_texture.clone(),
        output: decision.output,
        alpha_test: material_assets.alpha_test && !effect,
        opaque: !effect && !material_assets.alpha_test && !material_assets.translucent,
    }) else {
        return reference;
    };
    let content_id = bundle::content_id(&bytes);
    assets_by_id
        .entry(content_id.clone())
        .or_insert((bytes, image));
    reference.texture = Some(metadata::TextureReference {
        content_id,
        original_width: decision.original[0],
        original_height: decision.original[1],
        output_width: decision.output[0],
        output_height: decision.output[1],
    });
    reference
}

/// Texels of stretch per block along each texture axis, measured in the
/// plane of each fragment: the part of the projection along the normal moves
/// nothing on the face.
fn per_face_rates(surfaces: &[&crate::voxel::fragments::Fragment]) -> Vec<Option<[f64; 2]>> {
    surfaces
        .iter()
        .map(|face| {
            let normal = face.normal;
            let rate = std::array::from_fn(|texture_axis| {
                let projection = if texture_axis == 0 {
                    face.source.uv.u
                } else {
                    face.source.uv.v
                };
                let axis = Vec3::new(projection[0], projection[1], projection[2]);
                let along = axis.dot(normal);
                (axis.dot(axis) - along * along).max(0.0).sqrt()
            });
            rate.iter()
                .all(|value: &f64| value.is_finite() && *value > 0.0)
                .then_some(rate)
        })
        .collect()
}

pub fn portable_id(name: &str) -> String {
    let mut id: String = name
        .to_ascii_lowercase()
        .chars()
        .map(|c| {
            if c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect();
    if id.is_empty() {
        id.push_str("map");
    }
    id
}

/// Placement identity deliberately excludes the selected root cell.
pub fn stable_prop_id(map_id: &str, source_ordinal: u64, source_model: &str) -> Result<[u8; 32]> {
    bundle::validate_id(map_id, "map")?;
    ensure!(
        !source_model.is_empty(),
        "source model path must not be empty"
    );
    let mut hash = Sha256::new();
    hash.update(b"src2mc-prop-placement-v1\0");
    hash.update((map_id.len() as u32).to_le_bytes());
    hash.update(map_id.as_bytes());
    hash.update(source_ordinal.to_le_bytes());
    hash.update((source_model.len() as u32).to_le_bytes());
    hash.update(source_model.as_bytes());
    Ok(hash.finalize().into())
}

pub fn write_campaign(
    out: &Path,
    campaign_id: &str,
    mut maps: Vec<MapExport>,
) -> Result<WrittenCampaign> {
    bundle::validate_id(campaign_id, "campaign")?;
    maps.sort_by(|a, b| a.map_id.cmp(&b.map_id));
    ensure!(
        maps.windows(2).all(|p| p[0].map_id != p[1].map_id),
        "duplicate map ID"
    );
    std::fs::create_dir_all(out).with_context(|| format!("creating {}", out.display()))?;
    let mut archive = bundle::Bundle::new();
    let mut campaign_maps = Vec::new();
    let mut schematic_outputs = Vec::new();

    let mut atlas_assets = BTreeMap::new();
    for map in &mut maps {
        for texture in std::mem::take(&mut map.textures) {
            ensure!(
                bundle::content_id(&texture.bytes) == texture.content_id,
                "logical texture content ID changed"
            );
            // Room-only when no map of the bundle draws it.
            let room_only = atlas_assets
                .get(&texture.content_id)
                .is_none_or(|(_, room_only)| *room_only)
                && texture.room_only;
            if let Some((old, _)) =
                atlas_assets.insert(texture.content_id.clone(), (texture.image, room_only))
            {
                ensure!(
                    old.as_raw() == atlas_assets[&texture.content_id].0.as_raw(),
                    "shared logical texture differs"
                );
            }
        }
    }
    let atlas_path = (!atlas_assets.is_empty()).then_some("atlas.json".to_string());
    crate::timing::mark("  write: gather textures");
    if atlas_path.is_some() {
        let logical = atlas_assets
            .iter()
            .map(|(content_id, (image, room_only))| atlas::LogicalTexture {
                content_id: content_id.clone(),
                width: image.width(),
                height: image.height(),
                after_map: *room_only,
            })
            .collect::<Vec<_>>();
        let layout = atlas::pack(&logical)?;
        let images = atlas_assets
            .into_iter()
            .map(|(content_id, (image, _))| atlas::ImageAsset { content_id, image })
            .collect::<Vec<_>>();
        let pages = atlas::build_pages(&layout, &images)?;
        crate::timing::mark("  write: pack atlas pages");
        // Encoded in parallel, added in page and level order.
        use rayon::prelude::*;
        let encoded: Vec<Vec<Result<(u32, u32, Vec<u8>)>>> = pages
            .par_iter()
            .map(|images| {
                images
                    .mips
                    .par_iter()
                    .map(|image| {
                        Ok((
                            image.width(),
                            image.height(),
                            crate::source::vtf::to_png(image)?,
                        ))
                    })
                    .collect()
            })
            .collect();
        let mut page_meta = Vec::with_capacity(encoded.len());
        for (page, images) in encoded.into_iter().enumerate() {
            let mut mips = Vec::with_capacity(images.len());
            for (level, image) in images.into_iter().enumerate() {
                let (width, height, png) = image?;
                let content_id = archive.add_content("atlas", "png", png)?;
                mips.push(metadata::AtlasMip {
                    level: level as u8,
                    content_id,
                    width,
                    height,
                });
            }
            page_meta.push(metadata::AtlasPage {
                page: page as u32,
                mips,
            });
        }
        let textures = logical
            .into_iter()
            .map(|texture| metadata::AtlasTexture {
                regions: layout
                    .regions
                    .iter()
                    .filter(|r| r.content_id == texture.content_id)
                    .map(|r| metadata::AtlasRegion {
                        source: [r.source.x, r.source.y, r.source.width, r.source.height],
                        page: r.page,
                        allocation: [
                            r.allocation.x,
                            r.allocation.y,
                            r.allocation.width,
                            r.allocation.height,
                        ],
                    })
                    .collect(),
                content_id: texture.content_id,
                width: texture.width,
                height: texture.height,
            })
            .collect();
        archive.add(
            "atlas.json",
            metadata::AtlasMetadata {
                format: "src2mc-atlas",
                version: 1,
                page_size: atlas::PAGE_SIZE,
                max_mip_level: atlas::MAX_MIP_LEVEL,
                gutter: atlas::GUTTER,
                pages: page_meta,
                textures,
            }
            .encode()?,
        )?;
    }

    crate::timing::mark("  write: encode atlas PNGs");
    for map in maps {
        bundle::validate_id(&map.map_id, "map")?;
        let prefix = format!("maps/{}", map.map_id);
        archive.add(
            format!("{prefix}/surfaces.s2faces"),
            surface::encode(map.faces, surface::Limits::default())?,
        )?;
        let mut model_refs = Vec::new();
        for model in map.models {
            let content_id = archive.add_content("meshes", "s2mesh", model.bytes)?;
            let animation = match model.animation {
                Some(bytes) => Some(archive.add_content("animations", "s2anim", bytes)?),
                None => None,
            };
            ensure!(
                !model.source_model.is_empty(),
                "source model path must not be empty"
            );
            model_refs.push(metadata::ModelReference {
                content_id,
                source_model: model.source_model,
                materials: model.materials,
                color: (model.color != [255; 3]).then_some(model.color),
                animation,
                surface_prop: model.surface_prop,
            });
        }
        model_refs.sort();
        model_refs.dedup();
        let model_ids: BTreeMap<_, _> = model_refs
            .iter()
            .enumerate()
            .map(|(index, model)| {
                (
                    (
                        model.content_id.clone(),
                        model.source_model.clone(),
                        model.materials.clone(),
                        model.color.unwrap_or([255; 3]),
                        model.animation.clone(),
                    ),
                    index as u32,
                )
            })
            .collect();

        let mut roots = Vec::new();
        let mut placements = Vec::new();
        let mut occupied: BTreeSet<IVec3> = map.blocks.iter().map(|(p, _)| *p).collect();
        ensure!(
            !occupied.contains(&map.anchor_cell),
            "map anchor cell {:?} is occupied",
            map.anchor_cell
        );
        let anchor_id = map
            .palette
            .names()
            .iter()
            .position(|v| v == "src2mc:map_anchor")
            .context("palette lacks src2mc:map_anchor")? as u16;
        let root_id = map
            .palette
            .names()
            .iter()
            .position(|v| v == "src2mc:prop_root")
            .context("palette lacks src2mc:prop_root")? as u16;
        let mut blocks = map.blocks;
        blocks.push((map.anchor_cell, anchor_id));
        roots.push(schem::ModBlockEntity::anchor(
            sub(map.anchor_cell, map.cell_min),
            campaign_id,
            &map.map_id,
            map.anchor_cell,
        )?);
        let mut prop_light: Vec<([u8; 32], Option<VertexLight>)> = Vec::new();
        for prop in map.props {
            ensure!(
                occupied.insert(prop.root_cell),
                "prop root cell {:?} is occupied",
                prop.root_cell
            );
            let stable_id = stable_prop_id(&map.map_id, prop.source_ordinal, &prop.source_model)?;
            let model = *model_ids
                .get(&(
                    prop.model_content_id.clone(),
                    prop.source_model.clone(),
                    prop.material_ids.clone(),
                    prop.color,
                    prop.animation.clone(),
                ))
                .with_context(|| format!("prop {stable_id:x?} references a missing model"))?;
            blocks.push((prop.root_cell, root_id));
            roots.push(schem::ModBlockEntity::prop_root(
                sub(prop.root_cell, map.cell_min),
                campaign_id,
                &map.map_id,
                stable_id,
                &prop.model_content_id,
                prop.root_cell,
                prop.translation,
                prop.rotation,
                prop.scale,
                prop.material_ids.clone(),
                &prop.source_model,
            )?);
            placements.push(placement::Placement {
                stable_id,
                model,
                root_cell: prop.root_cell,
                translation: prop.translation,
                rotation: prop.rotation,
                scale: prop.scale,
            });
            prop_light.push((stable_id, prop.vertex_light));
        }
        // In the placement table's order, which is by stable ID.
        prop_light.sort_by_key(|(stable_id, _)| *stable_id);
        archive.add(
            format!("{prefix}/props.s2props"),
            placement::encode(placements, model_refs.len() as u32)?,
        )?;
        let has_pvs = map.pvs.is_some();
        if let Some(pvs) = map.pvs {
            archive.add(format!("{prefix}/pvs.s2pvs"), pvs)?;
        }
        let has_light = map.light.is_some();
        if let Some(light) = &map.light {
            let records: Vec<Option<&[[u8; 3]]>> = prop_light
                .iter()
                .map(|(_, colors)| colors.as_deref())
                .collect();
            archive.add(
                format!("{prefix}/light.s2light"),
                crate::output::light::encode(&light.atlas, light.ambient.as_ref(), &records)?,
            )?;
        }
        let has_occlusion = map.occlusion.is_some();
        if let Some(occlusion) = map.occlusion {
            archive.add(format!("{prefix}/occlusion.s2occl"), occlusion)?;
        }
        let has_collision = map.collision.is_some();
        if let Some(collision) = map.collision {
            archive.add(format!("{prefix}/collision.s2coll"), collision)?;
        }
        let has_audio = map.audio.is_some();
        if let Some(audio) = map.audio {
            for (content_id, bytes) in audio.assets {
                let added = archive.add_content("audio", "ogg", bytes)?;
                ensure!(added == content_id, "sound content ID changed");
            }
            archive.add(format!("{prefix}/audio.json"), audio.table.encode()?)?;
        }
        let has_logic = map.logic.is_some();
        if let Some(logic) = map.logic {
            archive.add(format!("{prefix}/logic.json"), logic.encode()?)?;
        }
        let has_particles = map.particles.is_some();
        if let Some(particles) = &map.particles {
            archive.add(format!("{prefix}/particles.json"), particles.encode()?)?;
        }
        archive.add(format!("{prefix}/look.s2look"), map.look.encode())?;
        let has_movers = !map.movers.is_empty();
        if has_movers {
            use crate::output::movers;
            let mut records = Vec::with_capacity(map.movers.len());
            for mover in map.movers {
                let entity = u32::try_from(mover.entity).context("mover entity index")?;
                let surfaces = if mover.faces.is_empty() {
                    None
                } else {
                    let path = movers::Mover::surfaces_path(&map.map_id, entity);
                    archive.add(
                        &path,
                        surface::encode(mover.faces, surface::Limits::default())?,
                    )?;
                    Some(path)
                };
                let collision = match mover.collision {
                    Some(bytes) => {
                        let path = movers::Mover::collision_path(&map.map_id, entity);
                        archive.add(&path, bytes)?;
                        Some(path)
                    }
                    None => None,
                };
                let mut props = Vec::with_capacity(mover.props.len());
                for prop in mover.props {
                    let model = *model_ids
                        .get(&(
                            prop.model_content_id.clone(),
                            prop.source_model.clone(),
                            prop.material_ids.clone(),
                            prop.color,
                            prop.animation.clone(),
                        ))
                        .with_context(|| {
                            format!(
                                "prop entity {} on mover {entity} references a missing model",
                                prop.entity
                            )
                        })?;
                    props.push(movers::MoverProp {
                        entity: u32::try_from(prop.entity).context("prop entity index")?,
                        model,
                        translation: prop.translation,
                        rotation: prop.rotation,
                        scale: prop.scale,
                        skin: prop.skin,
                    });
                }
                records.push(movers::Mover {
                    entity,
                    classname: mover.classname,
                    cell_origin: mover.cell_origin,
                    size: mover.size,
                    surfaces,
                    collision,
                    blocks: movers::Blocks {
                        surface: mover.surface_blocks,
                        carrier: mover.carrier_blocks,
                    },
                    props,
                });
            }
            archive.add(
                format!("{prefix}/movers.json"),
                movers::MoverTable::new(records).encode(&map.map_id, model_refs.len() as u32)?,
            )?;
        }
        let has_logic_props = !map.logic_props.is_empty();
        if has_logic_props {
            use crate::output::logic_props;
            let mut records = Vec::with_capacity(map.logic_props.len());
            for prop in map.logic_props {
                let entity = u32::try_from(prop.entity).context("logic prop entity index")?;
                let (stable_id, mover) = match &prop.placed {
                    LogicPlacement::World {
                        source_ordinal,
                        source_model,
                    } => {
                        let id = stable_prop_id(&map.map_id, *source_ordinal, source_model)?;
                        (
                            Some(id.iter().map(|b| format!("{b:02x}")).collect::<String>()),
                            None,
                        )
                    }
                    LogicPlacement::Mover(mover) => (
                        None,
                        Some(u32::try_from(*mover).context("mover entity index")?),
                    ),
                };
                let skins = prop
                    .skins
                    .iter()
                    .map(|key| {
                        model_ids.get(key).copied().with_context(|| {
                            format!("logic prop entity {entity} references a missing model")
                        })
                    })
                    .collect::<Result<Vec<u32>>>()?;
                let collision = match prop.collision {
                    Some(bytes) => {
                        let path = logic_props::LogicProp::collision_path(&map.map_id, entity);
                        archive.add(&path, bytes)?;
                        Some(path)
                    }
                    None => None,
                };
                let mut poses = Vec::with_capacity(prop.poses.len());
                for (sequence, bytes) in prop.poses {
                    let sequence = u32::try_from(sequence).context("pose sequence index")?;
                    let path = logic_props::LogicProp::pose_path(&map.map_id, entity, sequence);
                    archive.add(&path, bytes)?;
                    poses.push(logic_props::Pose {
                        sequence,
                        collision: path,
                    });
                }
                records.push(logic_props::LogicProp {
                    entity,
                    stable_id,
                    mover,
                    skins,
                    skin: prop.skin,
                    start_hidden: prop.start_hidden,
                    collision,
                    sequence: prop
                        .sequence
                        .map(u32::try_from)
                        .transpose()
                        .context("spawn sequence")?,
                    poses,
                });
            }
            records.sort_by_key(|record| record.entity);
            archive.add(
                format!("{prefix}/logic_props.json"),
                logic_props::LogicPropTable::new(records)
                    .encode(&map.map_id, model_refs.len() as u32)?,
            )?;
        }
        let has_skybox = map.skybox.is_some();
        if let Some(skybox) = map.skybox {
            archive.add(format!("{prefix}/skybox.s2box"), skybox)?;
        }
        let has_sky = map.sky.is_some();
        if let Some(sky) = &map.sky {
            for side in sky.sides.iter().flatten() {
                let added = archive.add_content("sky", "png", side.png.clone())?;
                ensure!(added == side.content_id, "sky side content ID changed");
            }
            archive.add(
                format!("{prefix}/sky.s2sky"),
                crate::output::sky::encode(sky)?,
            )?;
        }
        archive.add(
            format!("{prefix}/diagnostics.json"),
            map.diagnostics.encode()?,
        )?;
        let metadata_path = format!("maps/{}.json", map.map_id);
        let meta = metadata::MapMetadata {
            format: "src2mc-map",
            version: 1,
            map_id: map.map_id.clone(),
            source_name: map.source_name,
            units_per_block: UNITS_PER_BLOCK,
            cell_min: map.cell_min,
            cell_max: map.cell_max,
            anchor_cell: map.anchor_cell,
            surfaces: format!("{prefix}/surfaces.s2faces"),
            materials: map.materials,
            models: model_refs,
            props: format!("{prefix}/props.s2props"),
            pvs: has_pvs.then(|| format!("{prefix}/pvs.s2pvs")),
            occlusion: has_occlusion.then(|| format!("{prefix}/occlusion.s2occl")),
            collision: has_collision.then(|| format!("{prefix}/collision.s2coll")),
            audio: has_audio.then(|| format!("{prefix}/audio.json")),
            logic: has_logic.then(|| format!("{prefix}/logic.json")),
            movers: has_movers.then(|| format!("{prefix}/movers.json")),
            logic_props: has_logic_props.then(|| format!("{prefix}/logic_props.json")),
            sky: has_sky.then(|| format!("{prefix}/sky.s2sky")),
            skybox: has_skybox.then(|| format!("{prefix}/skybox.s2box")),
            light: has_light.then(|| format!("{prefix}/light.s2light")),
            particles: has_particles.then(|| format!("{prefix}/particles.json")),
            look: Some(format!("{prefix}/look.s2look")),
            diagnostics: format!("{prefix}/diagnostics.json"),
        };
        archive.add(&metadata_path, meta.encode()?)?;
        campaign_maps.push(metadata::CampaignMap {
            map_id: map.map_id.clone(),
            metadata: metadata_path,
        });
        let schematic_path = out.join(format!("{}.schem", map.map_id));
        schem::write_mod(
            &schematic_path,
            &blocks,
            &roots,
            &map.palette,
            map.cell_min,
            map.cell_max,
            &map.map_id,
        )?;
        schematic_outputs.push(schematic_path);
    }
    crate::timing::mark("  write: map tables and schematics");
    archive.add(
        metadata::CAMPAIGN_PATH,
        metadata::Campaign::with_atlas(campaign_id, atlas_path, campaign_maps)?.encode()?,
    )?;
    let bundle_path = out.join(format!("{campaign_id}.src2mc"));
    let manifest = archive.write(&bundle_path, campaign_id)?;
    crate::timing::mark("  write: zip bundle");
    Ok(WrittenCampaign {
        bundle: bundle_path,
        schematics: schematic_outputs,
        manifest,
    })
}

fn sub(a: IVec3, b: IVec3) -> IVec3 {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

fn add(a: IVec3, b: IVec3) -> IVec3 {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

/// The 16-block section coordinate of a map-local block-space position,
/// matching the runtime's `floor(coordinate / 16.0)` section mapping.
fn section_coord(value: f64) -> i32 {
    (value / 16.0).floor() as i32
}

/// Adds every surface face's 16-block section to the PVS section set, the
/// same grid `surface::encode` buckets faces into, so sections with visible
/// geometry but no props still get a cluster-visibility entry.
fn insert_surface_sections(
    surfaces: &[crate::voxel::fragments::Fragment],
    sections: &mut BTreeSet<[i32; 3]>,
) {
    for face in surfaces {
        sections.insert(crate::output::surface::section_of(face.cell));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bsp::texcoord::BlockTexCoord;
    use crate::output::mesh::{Mesh, Submesh, Vertex};
    use crate::voxel::surface::SourceProvenance;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(0);

    fn temp_dir(label: &str) -> PathBuf {
        let sequence = TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!(
            "src2mc-mod-export-{label}-{}-{sequence}",
            std::process::id()
        ))
    }

    fn triangle_mesh() -> Vec<u8> {
        crate::output::mesh::encode(&Mesh {
            bounds_min: [0.0, 0.0, 0.0],
            bounds_max: [1.0, 1.0, 0.0],
            vertices: vec![
                Vertex {
                    position: [0.0, 0.0, 0.0],
                    normal: [0.0, 0.0, 1.0],
                    uv: [0.0, 0.0],
                },
                Vertex {
                    position: [1.0, 0.0, 0.0],
                    normal: [0.0, 0.0, 1.0],
                    uv: [1.0, 0.0],
                },
                Vertex {
                    position: [0.0, 1.0, 0.0],
                    normal: [0.0, 0.0, 1.0],
                    uv: [0.0, 1.0],
                },
            ],
            indices: vec![0, 1, 2],
            submeshes: vec![Submesh {
                first_index: 0,
                index_count: 3,
                material_slot: 0,
            }],
            hardware: Vec::new(),
        })
        .unwrap()
    }

    fn fixture_map(map_id: &str, source_model: &str, mesh_bytes: Vec<u8>) -> MapExport {
        let mut palette = Palette::new();
        let surface_block = palette.intern("src2mc:surface");
        palette.intern("src2mc:map_anchor");
        palette.intern("src2mc:prop_root");
        let material = metadata::MaterialReference {
            source_material: "fixture/grid".into(),
            source_material_raw: None,
            render_class: metadata::RenderClass::Fallback,
            texture: None,
            surface_prop: None,
            reflectivity: [0.25, 0.5, 0.75],
            double_sided: false,
        };
        let content_id = bundle::content_id(&mesh_bytes);
        MapExport {
            map_id: map_id.into(),
            source_name: format!("{map_id}.bsp"),
            cell_min: [0, -1, 0],
            cell_max: [1, 0, 0],
            anchor_cell: [0, -1, 0],
            blocks: vec![([0, 0, 0], surface_block)],
            palette,
            faces: vec![surface::EncodedFace {
                cell: [0, 0, 0],
                owner: Some([0, 0, 0]),
                material: surface::MaterialId(0),
                uv: BlockTexCoord {
                    u: [1.0, 0.0, 0.0, 0.0],
                    v: [0.0, 0.0, 1.0, 0.0],
                },
                light: None,
                provenance: SourceProvenance::Face { face: 0, piece: 0 },
                vertices: vec![
                    [0, 4096, 0],
                    [0, 4096, 4096],
                    [4096, 4096, 4096],
                    [4096, 4096, 0],
                ],
            }],
            materials: vec![material],
            textures: Vec::new(),
            models: vec![ModelAsset {
                source_model: source_model.into(),
                bytes: mesh_bytes,
                materials: vec![0],
                color: [255; 3],
                surface_prop: None,
                animation: None,
            }],
            pvs: None,
            occlusion: None,
            collision: None,
            audio: None,
            logic: None,
            movers: Vec::new(),
            logic_props: Vec::new(),
            sky: None,
            skybox: None,
            light: None,
            particles: None,
            look: crate::output::look::LookTable { hdr: false, post: Default::default(), lookups: Default::default() },
            props: vec![Prop {
                source_ordinal: 0,
                source_model: source_model.into(),
                model_content_id: content_id,
                root_cell: [1, 0, 0],
                translation: [0.5, 0.0, 0.5],
                rotation: [0.0, 0.0, 0.0, 1.0],
                scale: 1.0,
                material_ids: vec![0],
                color: [255; 3],
                animation: None,
                vertex_light: None,
            }],
            diagnostics: metadata::Diagnostics::new(Vec::new()).unwrap(),
        }
    }

    fn fragment(
        cell: IVec3,
        material: usize,
        u: [f64; 4],
        v: [f64; 4],
    ) -> crate::voxel::fragments::Fragment {
        crate::voxel::fragments::Fragment {
            cell,
            owner: Some([0, 0, 0]),
            source: crate::voxel::surface::FaceSource {
                provenance: SourceProvenance::Face { face: 0, piece: 0 },
                material,
                uv: BlockTexCoord { u, v },
                light: None,
            },
            normal: Vec3::new(0.0, 1.0, 0.0),
            vertices: vec![
                [0, 4096, 0],
                [0, 4096, 4096],
                [4096, 4096, 4096],
                [4096, 4096, 0],
            ],
        }
    }

    fn face_at(material: usize, u: [f64; 4], v: [f64; 4]) -> crate::voxel::fragments::Fragment {
        fragment([0, 0, 0], material, u, v)
    }

    fn face_in_cell(cell: IVec3) -> crate::voxel::fragments::Fragment {
        fragment(cell, 0, [1.0, 0.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0])
    }

    #[test]
    fn surface_only_sections_get_pvs_entries_without_any_prop() {
        // A wall-enclosed room with no props: one face near the origin, one
        // two sections away, and a third re-visiting the first section.
        let surfaces = vec![
            face_in_cell([0, 0, 0]),
            face_in_cell([40, 0, 0]),
            face_in_cell([1, 1, 1]),
        ];
        let mut sections = BTreeSet::new();
        insert_surface_sections(&surfaces, &mut sections);
        assert_eq!(
            sections,
            BTreeSet::from([[0, 0, 0], [2, 0, 0]]),
            "sections spanned by surface-only geometry must be present even without props"
        );
    }

    #[test]
    fn bucket_by_output_groups_contributions_sharing_a_resolution() {
        let buckets = bucket_by_output(
            [1024, 1024],
            atlas::TextureQuality::Default,
            [
                (Contrib::Face(0), [4.0, 4.0]),
                (Contrib::Face(1), [4.0, 4.0]),
                (Contrib::Face(2), [1.0, 1.0]),
                (Contrib::Prop, [64.0, 64.0]),
            ],
        );
        assert_eq!(buckets.len(), 3);
        assert_eq!(buckets[&[64, 64]].len(), 2);
        assert_eq!(buckets[&[16, 16]].len(), 1);
        assert_eq!(buckets[&[1024, 1024]].len(), 1);
    }

    #[test]
    fn bucket_by_output_clamps_to_original_and_ignores_bad_contributions() {
        let buckets = bucket_by_output(
            [32, 32],
            atlas::TextureQuality::Default,
            [
                (Contrib::Face(0), [64.0, 64.0]),
                (Contrib::Face(1), [f64::NAN, 1.0]),
            ],
        );
        assert_eq!(buckets.len(), 1);
        assert_eq!(buckets[&[32, 32]].len(), 1);
    }

    #[test]
    fn bucket_by_output_of_no_contributions_is_empty() {
        assert!(bucket_by_output([32, 32], atlas::TextureQuality::Default, Vec::new()).is_empty());
    }

    #[test]
    fn per_face_rates_is_positional_and_flags_degenerate_faces() {
        let surfaces = vec![
            face_at(0, [1.0, 0.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0]),
            face_at(0, [0.0, 0.0, 0.0, 0.0], [0.0, 0.0, 0.0, 0.0]),
        ];
        let rates = per_face_rates(&surfaces.iter().collect::<Vec<_>>());
        assert_eq!(rates.len(), 2);
        assert_eq!(rates[0], Some([1.0, 1.0]));
        assert_eq!(rates[1], None);
    }

    #[test]
    fn per_face_rates_ignore_the_part_of_a_projection_along_the_normal() {
        // A projection leaning 45 degrees out of a floor: only its in-plane
        // half stretches the texture across the floor.
        let mut face = face_at(0, [1.0, 1.0, 0.0, 0.0], [0.0, 0.0, 2.0, 0.0]);
        face.normal = Vec3::new(0.0, 1.0, 0.0);
        assert_eq!(per_face_rates(&[&face]), vec![Some([1.0, 2.0])]);
    }

    #[test]
    fn stable_identity_ignores_root_selection() {
        assert_eq!(
            stable_prop_id("map", 7, "models/a.mdl").unwrap(),
            stable_prop_id("map", 7, "models/a.mdl").unwrap()
        );
        assert_ne!(
            stable_prop_id("map", 7, "models/a.mdl").unwrap(),
            stable_prop_id("map", 8, "models/a.mdl").unwrap()
        );
    }

    #[test]
    fn synthetic_campaign_is_deterministic_and_deduplicates_shared_meshes() {
        let mesh = triangle_mesh();
        let first_dir = temp_dir("first");
        let second_dir = temp_dir("second");
        let first = write_campaign(
            &first_dir,
            "fixture",
            vec![
                fixture_map("map_b", "models/b.mdl", mesh.clone()),
                fixture_map("map_a", "models/a.mdl", mesh.clone()),
            ],
        )
        .unwrap();
        let second = write_campaign(
            &second_dir,
            "fixture",
            vec![
                fixture_map("map_a", "models/a.mdl", mesh.clone()),
                fixture_map("map_b", "models/b.mdl", mesh),
            ],
        )
        .unwrap();

        assert_eq!(first.manifest, second.manifest);
        assert_eq!(
            first.manifest.fingerprint,
            include_str!("../../tests/fixtures/mod_export_fingerprint.txt").trim()
        );
        assert_eq!(
            first
                .manifest
                .entries
                .iter()
                .filter(|entry| entry.path.starts_with("meshes/"))
                .count(),
            1
        );
        for map_id in ["map_a", "map_b"] {
            let first_schematic = std::fs::read(first_dir.join(format!("{map_id}.schem"))).unwrap();
            assert_eq!(
                &first_schematic[..2],
                &[0x1f, 0x8b],
                "WorldEdit requires GZIP NBT"
            );
            assert_eq!(
                first_schematic,
                std::fs::read(second_dir.join(format!("{map_id}.schem"))).unwrap()
            );
        }
        assert_eq!(
            std::fs::read(&first.bundle).unwrap(),
            std::fs::read(&second.bundle).unwrap()
        );

        std::fs::remove_dir_all(first_dir).unwrap();
        std::fs::remove_dir_all(second_dir).unwrap();
    }

    #[test]
    fn identical_mesh_bytes_keep_distinct_material_bindings() {
        let bytes = triangle_mesh();
        let content_id = bundle::content_id(&bytes);
        let mut map = fixture_map("map", "models/a.mdl", bytes.clone());
        map.cell_max[0] = 2;
        map.materials.push(metadata::MaterialReference {
            source_material: "fixture/alternate".into(),
            source_material_raw: None,
            render_class: metadata::RenderClass::Fallback,
            texture: None,
            surface_prop: None,
            reflectivity: [0.0; 3],
            double_sided: false,
        });
        map.models.push(ModelAsset {
            source_model: "models/b.mdl".into(),
            bytes,
            materials: vec![1],
            color: [255; 3],
            surface_prop: None,
            animation: None,
        });
        map.props.push(Prop {
            source_ordinal: 1,
            source_model: "models/b.mdl".into(),
            model_content_id: content_id,
            root_cell: [2, 0, 0],
            translation: [1.5, 0.0, 0.5],
            rotation: [0.0, 0.0, 0.0, 1.0],
            scale: 1.0,
            material_ids: vec![1],
            color: [255; 3],
            animation: None,
            vertex_light: None,
        });
        let dir = temp_dir("material-bindings");
        let written = write_campaign(&dir, "fixture", vec![map]).unwrap();
        assert_eq!(
            written
                .manifest
                .entries
                .iter()
                .filter(|entry| entry.path.starts_with("meshes/"))
                .count(),
            1
        );
        std::fs::remove_dir_all(dir).unwrap();
    }

    /// A tinted prop the logic changes: its model reference carries the tint,
    /// and the logic prop table names its placement by stable ID, every skin
    /// by reference and its own collision table.
    #[test]
    fn logic_props_and_tints_are_written() {
        let bytes = triangle_mesh();
        let content_id = bundle::content_id(&bytes);
        let mut map = fixture_map("map", "models/a.mdl", bytes.clone());
        map.cell_max[0] = 2;
        let red = [200, 10, 10];
        for materials in [vec![0], vec![1]] {
            map.models.push(ModelAsset {
                source_model: "models/b.mdl".into(),
                bytes: bytes.clone(),
                materials,
                color: red,
                surface_prop: None,
                animation: None,
            });
        }
        map.materials.push(map.materials[0].clone());
        map.materials[1].source_material = "fixture/lit".into();
        map.props.push(Prop {
            source_ordinal: 1,
            source_model: "models/b.mdl".into(),
            model_content_id: content_id.clone(),
            root_cell: [2, 0, 0],
            translation: [1.5, 0.0, 0.5],
            rotation: [0.0, 0.0, 0.0, 1.0],
            scale: 1.0,
            material_ids: vec![1],
            color: red,
            animation: None,
            vertex_light: None,
        });
        let mut shapes = BTreeMap::new();
        shapes.insert([2, 0, 0], vec![[0, 0, 0, 16, 8, 16]]);
        map.logic_props.push(LogicPropExport {
            entity: 40,
            placed: LogicPlacement::World {
                source_ordinal: 1,
                source_model: "models/b.mdl".into(),
            },
            skins: [vec![0], vec![1]]
                .into_iter()
                .map(|materials| {
                    (
                        content_id.clone(),
                        "models/b.mdl".to_string(),
                        materials,
                        red,
                        None,
                    )
                })
                .collect(),
            skin: 1,
            start_hidden: true,
            collision: Some(crate::output::cell_collision::encode(&shapes).unwrap()),
            sequence: None,
            poses: Vec::new(),
        });
        let dir = temp_dir("logic-props");
        let written = write_campaign(&dir, "fixture", vec![map]).unwrap();
        let mut zip = zip::ZipArchive::new(std::fs::File::open(&written.bundle).unwrap()).unwrap();
        let mut read = |name: &str| {
            let mut text = String::new();
            std::io::Read::read_to_string(&mut zip.by_name(name).unwrap(), &mut text).unwrap();
            text
        };
        let meta = read("maps/map.json");
        assert!(
            meta.contains(r#""materials":[0],"color":[200,10,10]"#),
            "{meta}"
        );
        assert!(meta.contains(r#""logic_props":"maps/map/logic_props.json","look":"maps/map/look.s2look","diagnostics""#));
        let id: String = stable_prop_id("map", 1, "models/b.mdl")
            .unwrap()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        // References sort by content ID, model path, materials and tint: a.mdl
        // first, then b.mdl's two skins.
        assert_eq!(
            read("maps/map/logic_props.json"),
            format!(
                "{{\"format\":\"src2mc-logic-props\",\"version\":3,\"props\":[{{\"entity\":40,\"stable_id\":\"{id}\",\"skins\":[1,2],\"skin\":1,\"start_hidden\":true,\"collision\":\"maps/map/logic_props/40.s2coll\"}}]}}\n"
            )
        );
        assert!(
            written
                .manifest
                .entries
                .iter()
                .any(|entry| entry.path == "maps/map/logic_props/40.s2coll")
        );
        std::fs::remove_dir_all(dir).unwrap();
    }

    fn rider(translation: [f64; 3]) -> MoverPropExport {
        MoverPropExport {
            entity: 573,
            source_model: "models/b.mdl".into(),
            model_content_id: bundle::content_id(&triangle_mesh()),
            material_ids: vec![0],
            translation,
            rotation: [0.0, 0.0, 0.0, 1.0],
            scale: 1.0,
            skin: 1,
            color: [255; 3],
            animation: None,
        }
    }

    /// Everything of a mover is moved into its own cells by the same offset,
    /// the UV projection along with the geometry, so a texture keeps its phase.
    #[test]
    fn movers_are_moved_into_their_own_cells() {
        let mut grid = crate::voxel::grid::VoxelGrid::new();
        grid.set([10, 5, -3], 1);
        grid.set([11, 5, -3], 1);
        let u = [1.0, 0.0, 0.5, 0.25];
        let v = [0.0, 1.0, 0.0, 0.0];
        let fragments = vec![fragment([10, 5, -3], 0, u, v)];
        let mut collision = crate::voxel::collision::CellCollision::default();
        collision.carriers.insert([12, 5, -3]);
        collision
            .shapes
            .insert([12, 5, -3], vec![[0, 0, 0, 16, 8, 16]]);
        let bounds = crate::geom::Aabb::new(Vec3::new(9.5, 5.0, -3.0), Vec3::new(10.5, 6.0, -2.0));
        let mover = localize(
            7,
            "func_door".into(),
            &grid,
            &fragments,
            &[0],
            collision,
            vec![(rider([9.75, 5.5, -2.5]), bounds)],
        )
        .unwrap()
        .unwrap();
        assert_eq!(
            mover.cell_origin,
            [9, 5, -3],
            "the prop's box reaches furthest"
        );
        assert_eq!(mover.size, [4, 1, 1]);
        assert_eq!(mover.surface_blocks, vec![[1, 0, 0], [2, 0, 0]]);
        assert_eq!(mover.carrier_blocks, vec![[3, 0, 0]]);
        assert_eq!(mover.faces[0].cell, [1, 0, 0]);
        assert_eq!(mover.faces[0].owner, Some([0, 0, 0]));
        assert_eq!(mover.props[0].translation, [0.75, 0.5, 0.5]);
        // A point keeps its texture coordinate across the move.
        let map_point = Vec3::new(10.25, 5.5, -2.75);
        let local = map_point - Vec3::new(9.0, 5.0, -3.0);
        let before = BlockTexCoord { u, v };
        assert!((mover.faces[0].uv.s(local) - before.s(map_point)).abs() < 1e-12);
        assert!((mover.faces[0].uv.t(local) - before.t(map_point)).abs() < 1e-12);
        assert!(mover.collision.is_some());
    }

    /// A door panel too thin to voxelize has no blocks of its own: it is kept,
    /// drawn by unowned faces and solid through carriers.
    #[test]
    fn a_mover_without_blocks_is_kept() {
        let mut face = fragment([-4, 2, 0], 0, [1.0, 0.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0]);
        face.owner = None;
        let mut collision = crate::voxel::collision::CellCollision::default();
        collision.carriers.insert([-4, 2, 0]);
        collision
            .shapes
            .insert([-4, 2, 0], vec![[0, 0, 0, 16, 16, 2]]);
        let mover = localize(
            3,
            "func_door_rotating".into(),
            &crate::voxel::grid::VoxelGrid::new(),
            &[face],
            &[0],
            collision,
            Vec::new(),
        )
        .unwrap()
        .unwrap();
        assert!(mover.surface_blocks.is_empty());
        assert_eq!(mover.carrier_blocks, vec![[0, 0, 0]]);
        assert_eq!(mover.faces[0].owner, None);
        assert_eq!(mover.size, [1, 1, 1]);
        // And a mover with nothing at all is dropped.
        assert!(
            localize(
                4,
                "func_brush".into(),
                &crate::voxel::grid::VoxelGrid::new(),
                &[],
                &[],
                Default::default(),
                Vec::new(),
            )
            .unwrap()
            .is_none()
        );
    }

    /// The mover table is written, referenced from the map metadata between
    /// `logic` and `diagnostics`, and its prop names the map's model table.
    #[test]
    fn movers_are_written_into_the_bundle() {
        use std::io::Read;
        let bytes = triangle_mesh();
        let mut map = fixture_map("map", "models/a.mdl", bytes.clone());
        map.models.push(ModelAsset {
            source_model: "models/b.mdl".into(),
            bytes,
            materials: vec![0],
            color: [255; 3],
            surface_prop: None,
            animation: None,
        });
        let mut grid = crate::voxel::grid::VoxelGrid::new();
        grid.set([5, 0, 5], 1);
        let mover = localize(
            12,
            "func_door".into(),
            &grid,
            &[fragment(
                [5, 0, 5],
                0,
                [1.0, 0.0, 0.0, 0.0],
                [0.0, 0.0, 1.0, 0.0],
            )],
            &[0],
            Default::default(),
            vec![(rider([5.5, 0.5, 5.5]), crate::geom::Aabb::empty())],
        )
        .unwrap()
        .unwrap();
        map.movers.push(mover);
        let dir = temp_dir("movers");
        let written = write_campaign(&dir, "fixture", vec![map]).unwrap();
        let mut zip = zip::ZipArchive::new(std::fs::File::open(&written.bundle).unwrap()).unwrap();
        let mut read = |name: &str| {
            let mut text = String::new();
            zip.by_name(name)
                .unwrap()
                .read_to_string(&mut text)
                .unwrap();
            text
        };
        let meta = read("maps/map.json");
        assert!(
            meta.contains(r#""movers":"maps/map/movers.json","look":"maps/map/look.s2look","diagnostics""#),
            "{meta}"
        );
        let table = read("maps/map/movers.json");
        assert!(
            table.starts_with(r#"{"format":"src2mc-movers","version":1,"movers":[{"entity":12,"classname":"func_door","cell_origin":[5,0,5],"size":[1,1,1],"surfaces":"maps/map/movers/12.s2faces","blocks":{"surface":[[0,0,0]],"carrier":[]},"props":[{"entity":573,"model":1,"translation":[0.5,0.5,0.5]"#),
            "{table}"
        );
        let mut faces = Vec::new();
        zip.by_name("maps/map/movers/12.s2faces")
            .unwrap()
            .read_to_end(&mut faces)
            .unwrap();
        assert_eq!(&faces[..8], &surface::MAGIC);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
