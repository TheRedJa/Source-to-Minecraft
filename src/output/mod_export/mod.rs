//! Assembly of complete v1 campaign bundles and their transport schematics.

use crate::geom::Vec3;
use crate::output::{atlas, bundle, metadata, placement, schem, surface};
use crate::source::vmt::EnvmapMask;
use crate::voxel::grid::{IVec3, Palette};
use anyhow::{Context, Result, ensure};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

mod campaign;
mod materials;
mod movers;
mod props;
mod summaries;
#[cfg(test)]
mod tests;

pub use campaign::*;
use materials::*;
use movers::*;
use props::*;

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

/// Logical textures by content ID: the bytes they are named by, the image,
/// and their bump and blend layers.
type TextureAssets = BTreeMap<
    String,
    (
        Vec<u8>,
        image::RgbaImage,
        Option<image::RgbaImage>,
        Option<image::RgbaImage>,
    ),
>;

pub struct TextureAsset {
    pub content_id: String,
    pub bytes: Vec<u8>,
    pub image: image::RgbaImage,
    /// The bump layer drawn with it: a normal or self-shadowed bump map at
    /// the same size, packed at the same atlas place on the bump pages.
    pub bump: Option<image::RgbaImage>,
    /// The blend layer: a blended displacement material's `$basetexture2`
    /// at the same size, at the same atlas place on the blend pages.
    pub blend: Option<image::RgbaImage>,
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
    /// The cubemap files the materials reflect, by `envmap.cubemap`.
    cubemap_files: Vec<String>,
    /// Those cubemaps decoded; black where a file does not decode.
    cubemaps: Vec<crate::source::cubemap::Cubemap>,
    /// Detail and blend modulation textures by content ID: their PNG.
    details: BTreeMap<String, Vec<u8>>,
}

/// The material's reflection, its cubemap added to `cubemaps`; `None` when
/// the cubemap file is missing. `env_cubemap` left unpatched by vbsp, as on a
/// map built without cubemaps, reflects the map's default cubemap; a map with
/// HDR light reads the `.hdr.vtf`, as the material system does with HDR on.
fn cubemap_reference(
    envmap: &crate::source::vmt::Envmap,
    map: &crate::bsp::Map,
    resolver: &crate::source::vmt::Materials,
    cubemaps: &mut Vec<String>,
) -> Option<metadata::EnvmapReference> {
    let texture = if envmap.texture.eq_ignore_ascii_case("env_cubemap") {
        let stem = map.path.file_stem()?.to_string_lossy().to_ascii_lowercase();
        format!("maps/{stem}/cubemapdefault")
    } else {
        envmap
            .texture
            .to_ascii_lowercase()
            .trim_end_matches(".vtf")
            .to_string()
    };
    let candidates = [
        map.light
            .hdr
            .then(|| format!("materials/{texture}.hdr.vtf")),
        Some(format!("materials/{texture}.vtf")),
    ];
    let file = candidates
        .into_iter()
        .flatten()
        .find(|path| cubemaps.contains(path) || resolver.read(path).is_some())?;
    let index = match cubemaps.iter().position(|known| *known == file) {
        Some(index) => index,
        None => {
            cubemaps.push(file);
            cubemaps.len() - 1
        }
    };
    let f = |v: f32| f64::from(v);
    Some(metadata::EnvmapReference {
        cubemap: index as u32,
        tint: envmap.tint.map(f),
        contrast: f(envmap.contrast),
        saturation: envmap.saturation.map(f),
        fresnel: f(envmap.fresnel),
    })
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
    /// The cubemaps the map's materials reflect (format section 25).
    pub cubemaps: Vec<crate::source::cubemap::Cubemap>,
    /// Detail and blend modulation textures by content ID: their PNG.
    pub details: BTreeMap<String, Vec<u8>>,
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
    let (extracted, riding) = split_riders(extracted, &attachments);
    let mut world_collision = prop_collision(config, conversion, &extracted)?;
    crate::timing::mark("export: prop collision");
    // Carriers are blocks too, and may sit a cell outside the grid: the anchor
    // layer below the map must stay free of them.
    for cell in world_collision.collision.iter().flat_map(|c| &c.carriers) {
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
    // fallback material. A prop the logic reskins needs every skin family's
    // materials, not just the one it starts with.
    let mut prop_texture_spans = BTreeMap::<String, f64>::new();
    for item in extracted.iter().chain(riding.values().flatten()) {
        let models = std::iter::once(&item.model).chain(&item.skins);
        for part in models.flat_map(|model| &model.parts) {
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
        cubemaps,
        details,
        ..
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
    let model_by_path = build_models(
        &extracted,
        &riding,
        &prop_bucket_ids,
        &mut material_ids,
        &mut materials,
    )?;
    crate::timing::mark("export: prop meshes");
    let pvs_tracking = pvs_index.is_some().then_some(&mut pvs_sections);
    let WorldProps {
        props,
        logic: mut logic_props,
        taken: _,
        cell_min,
        cell_max,
    } = place_props(
        map,
        conversion,
        extracted,
        &model_by_path,
        &mut world_collision,
        pvs_tracking,
        (anchor_cell, grid_max),
    )?;
    let PropCollision {
        collision,
        solid_props,
        prop_cells,
        ..
    } = world_collision;
    let mut diagnostics = Vec::new();
    let mut mover_exports = export_movers(
        config,
        conversion,
        &entities,
        &mover_entities,
        riding,
        &model_by_path,
        &mover_face_ids,
        &light_atlas,
    )?;
    logic_props.append(&mut mover_exports.logic);
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

    diagnostics.extend(summaries::surface_fragments(conversion));
    // Every cell that stops daylight: the drawn thin brushes, which hold no
    // block, and every block of the map. The mod's sky bake cannot see the
    // world's blocks, only this bundle, and the only blocks the surface table
    // names are those that own a visible fragment. Without the rest, daylight
    // seeped through the map's hidden mass into the air hollowing leaves
    // inside thick floors and walls, and anything lit from such a pocket --
    // the foot of a wall below a terrain floor -- glowed in a dark room.
    let mut light_blockers = conversion.occluders.clone();
    light_blockers.extend(conversion.grid.iter().map(|(cell, _)| cell));
    diagnostics.extend(summaries::light_occlusion(
        light_blockers.len(),
        conversion.occluders.len(),
    ));
    if !mover_entities.is_empty() {
        diagnostics.push(summaries::movers(
            &mover_exports,
            attachments.ambiguous.len(),
        ));
    }
    diagnostics.extend(summaries::ambiguous_parents(&attachments.ambiguous));
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
            diagnostics.push(summaries::collision(collision, solid_props, prop_cells));
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
        movers: mover_exports.movers,
        logic_props,
        sky,
        skybox: skybox_table,
        light: Some(LightExport {
            ambient: crate::output::light::ambient(map, &conversion.transform),
            atlas: light_atlas,
        }),
        particles,
        look,
        cubemaps,
        details,
        diagnostics: metadata::Diagnostics::new(diagnostics)?,
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

/// Every 16-block section a block-space box spans.
fn insert_bounds_sections(bounds: crate::geom::Aabb, sections: &mut BTreeSet<[i32; 3]>) {
    for x in section_coord(bounds.min.x)..=section_coord(bounds.max.x - 1.0e-8) {
        for y in section_coord(bounds.min.y)..=section_coord(bounds.max.y - 1.0e-8) {
            for z in section_coord(bounds.min.z)..=section_coord(bounds.max.z - 1.0e-8) {
                sections.insert([x, y, z]);
            }
        }
    }
}
