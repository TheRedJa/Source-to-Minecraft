//! Writing a campaign: bundle, manifest and transport schematics.

use super::*;

/// Logical textures by content ID: image, bump, blend, and room-only.
type AtlasAssets = BTreeMap<
    String,
    (
        image::RgbaImage,
        Option<image::RgbaImage>,
        Option<image::RgbaImage>,
        bool,
    ),
>;

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

    let atlas_assets = gather_textures(&mut maps)?;
    let atlas_path = (!atlas_assets.is_empty()).then_some("atlas.json".to_string());
    crate::timing::mark("  write: gather textures");
    if atlas_path.is_some() {
        write_atlas(&mut archive, atlas_assets)?;
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
        for (id, png) in &map.details {
            let added = archive.add_content("details", "png", png.clone())?;
            ensure!(added == *id, "detail texture content ID changed");
        }
        if !map.cubemaps.is_empty() {
            archive.add(
                format!("{prefix}/cubemaps.s2cube"),
                crate::output::cubemaps::encode(&map.cubemaps)?,
            )?;
        }
        let has_movers = !map.movers.is_empty();
        if has_movers {
            write_movers(
                &mut archive,
                &map.map_id,
                map.movers,
                &model_ids,
                model_refs.len() as u32,
            )?;
        }
        let has_logic_props = !map.logic_props.is_empty();
        if has_logic_props {
            write_logic_props(
                &mut archive,
                &map.map_id,
                map.logic_props,
                &model_ids,
                model_refs.len() as u32,
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
            cubemaps: (!map.cubemaps.is_empty()).then(|| format!("{prefix}/cubemaps.s2cube")),
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

/// Every map's logical textures, once each by content ID: image, bump and
/// blend pages, and whether only a 3D skybox room draws it.
fn gather_textures(maps: &mut [MapExport]) -> Result<AtlasAssets> {
    let mut atlas_assets = BTreeMap::new();
    for map in maps.iter_mut() {
        for texture in std::mem::take(&mut map.textures) {
            ensure!(
                bundle::content_id(&texture.bytes) == texture.content_id,
                "logical texture content ID changed"
            );
            // Room-only when no map of the bundle draws it.
            let room_only = atlas_assets
                .get(&texture.content_id)
                .is_none_or(|(_, _, _, room_only)| *room_only)
                && texture.room_only;
            if let Some((old, _, _, _)) = atlas_assets.insert(
                texture.content_id.clone(),
                (texture.image, texture.bump, texture.blend, room_only),
            ) {
                ensure!(
                    old.as_raw() == atlas_assets[&texture.content_id].0.as_raw(),
                    "shared logical texture differs"
                );
            }
        }
    }
    Ok(atlas_assets)
}

/// The atlas pages, their mips encoded in parallel, and `atlas.json`.
fn write_atlas(archive: &mut bundle::Bundle, atlas_assets: AtlasAssets) -> Result<()> {
    let logical = atlas_assets
        .iter()
        .map(
            |(content_id, (image, _, _, room_only))| atlas::LogicalTexture {
                content_id: content_id.clone(),
                width: image.width(),
                height: image.height(),
                after_map: *room_only,
            },
        )
        .collect::<Vec<_>>();
    let layout = atlas::pack(&logical)?;
    let images = atlas_assets
        .into_iter()
        .map(|(content_id, (image, bump, blend, _))| atlas::ImageAsset {
            content_id,
            image,
            bump,
            blend,
        })
        .collect::<Vec<_>>();
    let pages = atlas::build_pages(&layout, &images)?;
    crate::timing::mark("  write: pack atlas pages");
    // Encoded in parallel, added in page and level order.
    use rayon::prelude::*;
    let encode_mips = |mips: &Vec<image::RgbaImage>| -> Vec<Result<(u32, u32, Vec<u8>)>> {
        mips.par_iter()
            .map(|image| {
                Ok((
                    image.width(),
                    image.height(),
                    crate::source::vtf::to_png(image)?,
                ))
            })
            .collect()
    };
    #[allow(clippy::type_complexity)]
    let encoded: Vec<(
        Vec<Result<(u32, u32, Vec<u8>)>>,
        Option<Vec<Result<(u32, u32, Vec<u8>)>>>,
        Option<Vec<Result<(u32, u32, Vec<u8>)>>>,
    )> = pages
        .par_iter()
        .map(|images| {
            (
                encode_mips(&images.mips),
                images.bump_mips.as_ref().map(encode_mips),
                images.blend_mips.as_ref().map(encode_mips),
            )
        })
        .collect();
    let mut page_meta = Vec::with_capacity(encoded.len());
    let mut add_mips =
        |images: Vec<Result<(u32, u32, Vec<u8>)>>| -> Result<Vec<metadata::AtlasMip>> {
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
            Ok(mips)
        };
    for (page, (images, bump, blend)) in encoded.into_iter().enumerate() {
        let mips = add_mips(images)?;
        let bump_mips = bump.map(&mut add_mips).transpose()?;
        let blend_mips = blend.map(&mut add_mips).transpose()?;
        page_meta.push(metadata::AtlasPage {
            page: page as u32,
            mips,
            bump_mips,
            blend_mips,
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
            version: 3,
            page_size: atlas::PAGE_SIZE,
            max_mip_level: atlas::MAX_MIP_LEVEL,
            gutter: atlas::GUTTER,
            pages: page_meta,
            textures,
        }
        .encode()?,
    )?;
    Ok(())
}

/// A map's movers: each one's surfaces and collision, and the mover table.
fn write_movers(
    archive: &mut bundle::Bundle,
    map_id: &str,
    movers: Vec<MoverExport>,
    model_ids: &BTreeMap<ModelKey, u32>,
    model_count: u32,
) -> Result<()> {
    use crate::output::movers;
    let mut records = Vec::with_capacity(movers.len());
    for mover in movers {
        let entity = u32::try_from(mover.entity).context("mover entity index")?;
        let surfaces = if mover.faces.is_empty() {
            None
        } else {
            let path = movers::Mover::surfaces_path(map_id, entity);
            archive.add(
                &path,
                surface::encode(mover.faces, surface::Limits::default())?,
            )?;
            Some(path)
        };
        let collision = match mover.collision {
            Some(bytes) => {
                let path = movers::Mover::collision_path(map_id, entity);
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
        format!("maps/{map_id}/movers.json"),
        movers::MoverTable::new(records).encode(map_id, model_count)?,
    )?;
    Ok(())
}

/// A map's logic props: each one's collision and poses, and their table.
fn write_logic_props(
    archive: &mut bundle::Bundle,
    map_id: &str,
    logic_props: Vec<LogicPropExport>,
    model_ids: &BTreeMap<ModelKey, u32>,
    model_count: u32,
) -> Result<()> {
    use crate::output::logic_props;
    let mut records = Vec::with_capacity(logic_props.len());
    for prop in logic_props {
        let entity = u32::try_from(prop.entity).context("logic prop entity index")?;
        let (stable_id, mover) = match &prop.placed {
            LogicPlacement::World {
                source_ordinal,
                source_model,
            } => {
                let id = stable_prop_id(map_id, *source_ordinal, source_model)?;
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
                let path = logic_props::LogicProp::collision_path(map_id, entity);
                archive.add(&path, bytes)?;
                Some(path)
            }
            None => None,
        };
        let mut poses = Vec::with_capacity(prop.poses.len());
        for (sequence, bytes) in prop.poses {
            let sequence = u32::try_from(sequence).context("pose sequence index")?;
            let path = logic_props::LogicProp::pose_path(map_id, entity, sequence);
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
        format!("maps/{map_id}/logic_props.json"),
        logic_props::LogicPropTable::new(records).encode(map_id, model_count)?,
    )?;
    Ok(())
}
