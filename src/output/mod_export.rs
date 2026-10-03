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
    pub surface_prop: Option<String>,
}

pub struct TextureAsset {
    pub content_id: String,
    pub bytes: Vec<u8>,
    pub image: image::RgbaImage,
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
    pub diagnostics: metadata::Diagnostics,
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
    let mut collision = conversion.collision.clone();
    let (mut solid_props, mut prop_cells) = (0, 0);
    if let Some(collision) = collision.as_mut() {
        (solid_props, prop_cells) = add_prop_collision(config, conversion, &extracted, collision);
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
    for item in &extracted {
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
    let (mut materials, textures, face_material_ids, prop_bucket_ids) = extract_materials(
        map,
        config,
        &prop_texture_spans,
        &conversion.fragments,
        quality,
    );
    crate::timing::mark("export: materials and textures");
    let faces = conversion
        .fragments
        .iter()
        .zip(face_material_ids.iter().copied())
        .map(|(fragment, material_id)| {
            surface::EncodedFace::from_fragment(fragment, surface::MaterialId(material_id))
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
    // Keyed by skin as well: one mesh, but each skin its own material slots.
    let mut model_by_path: BTreeMap<(String, i32), (String, Vec<u32>, Vec<u8>, Option<String>)> =
        BTreeMap::new();
    for item in &extracted {
        let key = (item.prop.model.clone(), item.prop.skin);
        if model_by_path.contains_key(&key) {
            continue;
        }
        let (mesh, slots) = crate::output::mesh::from_source_model(&item.model, UNITS_PER_BLOCK)?;
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
        model_by_path.insert(key, (id, slot_ids, bytes, item.model.surface_prop.clone()));
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
    for item in extracted {
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
        let (model_content_id, slots, _, _) =
            &model_by_path[&(item.prop.model.clone(), item.prop.skin)];
        let origin = conversion.transform.to_block_space(item.prop.origin);
        props.push(Prop {
            source_ordinal: item.source_ordinal,
            source_model: item.prop.model.clone(),
            model_content_id: model_content_id.clone(),
            root_cell,
            translation: [origin.x, origin.y, origin.z],
            rotation: crate::output::display::rotation(&item.prop, &conversion.transform),
            scale: item.prop.scale,
            material_ids: slots.clone(),
        });
    }
    if let Some(collision) = collision.as_mut() {
        collision.carriers.retain(|cell| !taken.contains(cell));
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
    crate::timing::mark("export: prop roots and diagnostics");
    let models: Vec<ModelAsset> = model_by_path
        .into_iter()
        .map(
            |((source_model, _), (_, materials, bytes, surface_prop))| ModelAsset {
                source_model,
                bytes,
                materials,
                surface_prop,
            },
        )
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
    let entities = crate::bsp::entities::extract(map, &conversion.transform);
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
        diagnostics: metadata::Diagnostics::new(diagnostics)?,
    })
}

/// Give every solid prop large enough to bump into its collision: Source's
/// `solid` decides whether it has any and whether it is its bounding box or
/// its model. The model's own physics hull (`.phy`) is not read yet, so a
/// physics prop collides as the shell of its drawn mesh. Returns how many
/// props and cells took part.
fn add_prop_collision(
    config: &crate::config::Config,
    conversion: &crate::convert::Conversion,
    extracted: &[crate::source::extract::ModProp],
    collision: &mut crate::voxel::collision::CellCollision,
) -> (usize, usize) {
    use crate::bsp::props::{SOLID_BBOX, SOLID_NONE};
    use crate::voxel::collision::{SubCells, box_volume, shell_volume};
    use rayon::prelude::*;
    let transform = &conversion.transform;
    let volumes: Vec<std::collections::HashMap<IVec3, SubCells>> = extracted
        .par_iter()
        .filter_map(|item| {
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
            let triangles: Vec<[crate::geom::Vec3; 3]> = item
                .model
                .parts
                .iter()
                .flat_map(|part| &part.triangles)
                .map(|tri| tri.map(|v| transform.to_block_space(item.prop.place(v))))
                .collect();
            Some(shell_volume(&triangles))
        })
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
    let added = crate::voxel::collision::add_props(collision, &conversion.grid, cells);
    crate::timing::mark("  prop collision: add to table");
    (solid, added)
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
    surfaces: &[crate::voxel::fragments::Fragment],
    quality: atlas::TextureQuality,
) -> (
    Vec<metadata::MaterialReference>,
    Vec<TextureAsset>,
    Vec<u32>,
    BTreeMap<String, u32>,
) {
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
fn assign_materials(
    map: &crate::bsp::Map,
    prop_texture_spans: &BTreeMap<String, f64>,
    surfaces: &[crate::voxel::fragments::Fragment],
    resolver: &crate::source::vmt::Materials,
    decoder: &mut crate::source::vtf::Textures,
    quality: atlas::TextureQuality,
    produce: &mut dyn FnMut(TextureRequest) -> Option<(Vec<u8>, image::RgbaImage)>,
) -> (
    Vec<metadata::MaterialReference>,
    Vec<TextureAsset>,
    Vec<u32>,
    BTreeMap<String, u32>,
) {
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
        let mut reference = metadata::MaterialReference {
            source_material: name.clone(),
            source_material_raw: None,
            render_class: metadata::RenderClass::Fallback,
            texture: None,
            surface_prop: None,
            reflectivity: [0.0; 3],
            double_sided: false,
        };
        let Some(material_assets) = resolver.assets(name, None) else {
            materials.push(reference);
            continue;
        };
        reference.render_class = if material_assets.alpha_test {
            metadata::RenderClass::Cutout
        } else if material_assets.translucent {
            metadata::RenderClass::Translucent
        } else {
            metadata::RenderClass::Solid
        };
        reference.surface_prop = material_assets.surface_prop;
        reference.double_sided = material_assets.no_cull;
        let Some(header) = decoder.header(&material_assets.base_texture) else {
            materials.push(reference);
            continue;
        };
        reference.reflectivity = header.reflectivity;
        let Ok(decision) = atlas::analyze_resolution(header.size, [*blocks_spanned; 2], quality)
        else {
            materials.push(reference);
            continue;
        };
        let Some((bytes, image)) = produce(TextureRequest {
            texture: material_assets.base_texture.clone(),
            output: decision.output,
            alpha_test: material_assets.alpha_test,
            opaque: !material_assets.alpha_test && !material_assets.translucent,
        }) else {
            materials.push(reference);
            continue;
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
        materials.push(reference);
        prop_bucket_ids.insert(name.clone(), (materials.len() - 1) as u32);
    }
    let textures = assets_by_id
        .into_iter()
        .map(|(content_id, (bytes, image))| TextureAsset {
            content_id,
            bytes,
            image,
        })
        .collect();
    (materials, textures, face_material_ids, prop_bucket_ids)
}

/// Texels of stretch per block along each texture axis, measured in the
/// plane of each fragment: the part of the projection along the normal moves
/// nothing on the face.
fn per_face_rates(surfaces: &[crate::voxel::fragments::Fragment]) -> Vec<Option<[f64; 2]>> {
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
            if let Some(old) = atlas_assets.insert(texture.content_id.clone(), texture.image) {
                ensure!(
                    old.as_raw() == atlas_assets[&texture.content_id].as_raw(),
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
            .map(|(content_id, image)| atlas::LogicalTexture {
                content_id: content_id.clone(),
                width: image.width(),
                height: image.height(),
            })
            .collect::<Vec<_>>();
        let layout = atlas::pack(&logical)?;
        let images = atlas_assets
            .into_iter()
            .map(|(content_id, image)| atlas::ImageAsset { content_id, image })
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
            ensure!(
                !model.source_model.is_empty(),
                "source model path must not be empty"
            );
            model_refs.push(metadata::ModelReference {
                content_id,
                source_model: model.source_model,
                materials: model.materials,
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
        }
        archive.add(
            format!("{prefix}/props.s2props"),
            placement::encode(placements, model_refs.len() as u32)?,
        )?;
        let has_pvs = map.pvs.is_some();
        if let Some(pvs) = map.pvs {
            archive.add(format!("{prefix}/pvs.s2pvs"), pvs)?;
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
                surface_prop: None,
            }],
            pvs: None,
            occlusion: None,
            collision: None,
            audio: None,
            logic: None,
            props: vec![Prop {
                source_ordinal: 0,
                source_model: source_model.into(),
                model_content_id: content_id,
                root_cell: [1, 0, 0],
                translation: [0.5, 0.0, 0.5],
                rotation: [0.0, 0.0, 0.0, 1.0],
                scale: 1.0,
                material_ids: vec![0],
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
        let rates = per_face_rates(&surfaces);
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
        assert_eq!(per_face_rates(&[face]), vec![Some([1.0, 2.0])]);
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
            surface_prop: None,
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
}
