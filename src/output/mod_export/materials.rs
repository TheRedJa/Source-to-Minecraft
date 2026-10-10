//! Material assignment: atlas pages, standalone materials and their Source shader rules.

use super::*;

pub(super) fn assign_material(
    face_material_ids: &mut [u32],
    face_indices: &[usize],
    material_index: u32,
) {
    for &face_index in face_indices {
        face_material_ids[face_index] = material_index;
    }
}

pub(super) fn extract_materials(
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
    let mut extracted = assign_materials(
        map,
        prop_texture_spans,
        room_texture_spans,
        effect_materials,
        surfaces,
        &resolver,
        &mut decoder,
        quality,
        &mut |request| produced.get(&request).cloned().flatten(),
    );
    extracted.cubemaps = extracted
        .cubemap_files
        .iter()
        .map(|file| {
            resolver
                .read(file)
                .and_then(|bytes| crate::source::cubemap::decode(&bytes).ok())
                .unwrap_or(crate::source::cubemap::Cubemap {
                    size: 1,
                    faces: Default::default(),
                })
        })
        .map(|mut cube| {
            for face in &mut cube.faces {
                face.resize((cube.size * cube.size) as usize, [0.0; 3]);
            }
            cube
        })
        .collect();
    extracted
}

/// One texture as a material needs it: the file, the size, and how its alpha
/// is treated.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(super) struct TextureRequest {
    pub(super) texture: String,
    pub(super) output: [u32; 2],
    pub(super) alpha_test: bool,
    pub(super) opaque: bool,
}

/// Give every map and prop material its reference and texture, taking each
/// texture's PNG bytes and image from `produce`.
#[allow(clippy::type_complexity)]
#[allow(clippy::too_many_arguments)]
pub(super) fn assign_materials(
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
    let mut cubemaps: Vec<String> = Vec::new();
    let mut details: BTreeMap<String, Vec<u8>> = BTreeMap::new();
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
            bump: None,
            envmap: None,
            blend: false,
            blend_modulate: None,
            detail: None,
            selfillum: None,
        };
        let face_indices = &faces_by_material[index];
        let Some(mut material_assets) = resolver.assets(&material.name, Some(&material.raw_name))
        else {
            materials.push(base_reference);
            assign_material(
                &mut face_material_ids,
                face_indices,
                (materials.len() - 1) as u32,
            );
            continue;
        };
        base_alpha_rules(&mut material_assets, decoder);
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
            // The bump map at the base texture's size, so it shares its atlas
            // place; the pair is one logical texture, named by both. Asked for
            // first: the request pass gets nothing back for the base texture.
            let bump = material_assets.bump_map.as_ref().and_then(|bump_map| {
                produce(TextureRequest {
                    texture: bump_map.clone(),
                    output,
                    alpha_test: false,
                    opaque: false,
                })
            });
            // The reflection's mask, kept in the layer's alpha (asked for before the
            // base texture, as the bump map is): LightmappedGeneric
            // reads it from the bump map's alpha, an $envmapmask texture, or one
            // minus the base texture's alpha.
            let mask = match material_assets.envmap.as_ref().map(|e| &e.mask) {
                Some(EnvmapMask::Texture(mask)) => produce(TextureRequest {
                    texture: mask.clone(),
                    output,
                    alpha_test: false,
                    opaque: false,
                })
                .map(|(_, mask)| {
                    mask.pixels()
                        .map(|p| ((u16::from(p[0]) + u16::from(p[1]) + u16::from(p[2])) / 3) as u8)
                        .collect::<Vec<u8>>()
                }),
                Some(EnvmapMask::BaseAlpha) => produce(TextureRequest {
                    texture: material_assets.base_texture.clone(),
                    output,
                    alpha_test: false,
                    opaque: false,
                })
                .map(|(_, base)| base.pixels().map(|p| 255 - p[3]).collect::<Vec<u8>>()),
                _ => None,
            };
            // A blended displacement's second texture at the base texture's size.
            let blend_image = material_assets.base_texture2.as_ref().and_then(|texture| {
                produce(TextureRequest {
                    texture: texture.clone(),
                    output,
                    alpha_test: false,
                    opaque: false,
                })
                .map(|(_, image)| image)
            });
            // Detail and blend modulation textures repeat on their own; a
            // power of two at most 512 a side, mipmapped by the mod.
            let detail_ssbump = material_assets
                .detail
                .as_ref()
                .and_then(|detail| decoder.header(&detail.texture))
                .is_some_and(|h| h.flags & crate::source::vtf::FLAG_SSBUMP != 0);
            let mut repeating = |texture: &str| -> Option<String> {
                let header = decoder.header(texture)?;
                let side = |n: u32| 1u32 << (n.clamp(1, 512).ilog2());
                let (bytes, _) = produce(TextureRequest {
                    texture: texture.to_string(),
                    output: [side(header.size[0]), side(header.size[1])],
                    alpha_test: false,
                    opaque: false,
                })?;
                let id = bundle::content_id(&bytes);
                details.entry(id.clone()).or_insert(bytes);
                Some(id)
            };
            let detail = material_assets.detail.as_ref().and_then(|detail| {
                let texture = repeating(&detail.texture)?;
                let blend_mode = match (detail_ssbump, material_assets.bump_map.is_some()) {
                    (true, true) => 10,
                    (true, false) => 11,
                    _ => detail.blend_mode,
                };
                let f = |v: f32| f64::from(v);
                Some(metadata::DetailReference {
                    texture,
                    scale: detail.scale.map(f),
                    blend_factor: f(detail.blend_factor),
                    blend_mode,
                    tint: detail.tint.map(f),
                })
            });
            let blend_modulate = material_assets
                .blend_modulate
                .as_deref()
                .and_then(&mut repeating);
            // $selfillum glows by the base texture's alpha, which an opaque
            // texture otherwise drops.
            let Some((bytes, image)) = produce(TextureRequest {
                texture: material_assets.base_texture.clone(),
                output,
                alpha_test: material_assets.alpha_test,
                opaque: !material_assets.alpha_test
                    && !material_assets.translucent
                    && material_assets.selfillum.is_none(),
            }) else {
                continue;
            };
            let normal_alpha = matches!(
                material_assets.envmap.as_ref().map(|e| &e.mask),
                Some(EnvmapMask::NormalAlpha)
            );
            let bump_kind = bump.as_ref().map(|_| {
                if material_assets.ssbump {
                    metadata::BumpKind::Ssbump
                } else {
                    metadata::BumpKind::Normal
                }
            });
            let layer = match (bump, mask) {
                (Some((_, mut layer)), mask) => {
                    if !normal_alpha {
                        for (index, pixel) in layer.pixels_mut().enumerate() {
                            pixel[3] = mask.as_ref().map_or(255, |m| m[index]);
                        }
                    }
                    Some(layer)
                }
                (None, Some(mask)) => {
                    let mut layer = image::RgbaImage::new(output[0], output[1]);
                    for (pixel, alpha) in layer.pixels_mut().zip(mask) {
                        *pixel = image::Rgba([128, 128, 255, alpha]);
                    }
                    Some(layer)
                }
                (None, None) => None,
            };
            // With layers the texture is one logical texture, named by all.
            let mut bytes = bytes;
            for extra in [&layer, &blend_image].into_iter().flatten() {
                bytes.extend_from_slice(extra.as_raw());
            }
            let bump_image = layer;
            let blend = blend_image.is_some();
            let content_id = bundle::content_id(&bytes);
            assets_by_id.entry(content_id.clone()).or_insert((
                bytes,
                image,
                bump_image,
                blend_image,
            ));
            let mut reference = reference_template.clone();
            reference.bump = bump_kind;
            reference.blend = blend;
            reference.blend_modulate = blend_modulate;
            reference.detail = detail;
            reference.selfillum = material_assets.selfillum.map(|t| t.map(f64::from));
            reference.envmap = material_assets
                .envmap
                .as_ref()
                .and_then(|envmap| cubemap_reference(envmap, map, resolver, &mut cubemaps));
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
        .map(|(content_id, (bytes, image, bump, blend))| TextureAsset {
            room_only: !drawn_by_map.contains(&content_id),
            content_id,
            bytes,
            image,
            bump,
            blend,
        })
        .collect();
    ExtractedMaterials {
        materials,
        textures,
        face_material_ids,
        prop_bucket_ids,
        room_material_ids,
        effect_material_ids,
        cubemap_files: cubemaps,
        cubemaps: Vec::new(),
        details,
    }
}

/// A projection span large enough that a particle texture keeps every texel
/// it has.
pub(super) const EFFECT_SPAN: f64 = 1.0e6;

/// LightmappedGeneric and VertexLitGeneric drop `$selfillum` and
/// `$basealphaenvmapmask` when the base texture has no alpha channel, and
/// alpha test no texture whose alpha means either.
pub(super) fn base_alpha_rules(
    material_assets: &mut crate::source::vmt::MaterialAssets,
    decoder: &mut crate::source::vtf::Textures,
) {
    let base_alpha = decoder
        .header(&material_assets.base_texture)
        .is_some_and(|h| h.flags & crate::source::vtf::FLAG_ALPHA != 0);
    if !base_alpha {
        material_assets.selfillum = None;
        if let Some(envmap) = &mut material_assets.envmap
            && envmap.mask == EnvmapMask::BaseAlpha
        {
            envmap.mask = EnvmapMask::None;
        }
    }
    if material_assets.selfillum.is_some()
        || material_assets
            .envmap
            .as_ref()
            .is_some_and(|envmap| envmap.mask == EnvmapMask::BaseAlpha)
    {
        material_assets.alpha_test = false;
    }
}

/// A material drawn outside the map's faces, by props or the 3D skybox room,
/// with one texture of the detail `blocks_spanned` asks; without a texture
/// when it cannot be resolved or decoded.
#[allow(clippy::too_many_arguments)]
pub(super) fn standalone_material(
    name: &str,
    blocks_spanned: f64,
    effect: bool,
    resolver: &crate::source::vmt::Materials,
    decoder: &mut crate::source::vtf::Textures,
    quality: atlas::TextureQuality,
    produce: &mut dyn FnMut(TextureRequest) -> Option<(Vec<u8>, image::RgbaImage)>,
    assets_by_id: &mut TextureAssets,
) -> metadata::MaterialReference {
    let mut reference = metadata::MaterialReference {
        source_material: name.to_string(),
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
    };
    let Some(mut material_assets) = resolver.assets(name, None) else {
        return reference;
    };
    if effect {
        material_assets.selfillum = None;
    }
    base_alpha_rules(&mut material_assets, decoder);
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
        // $selfillum glows by the base texture's alpha.
        opaque: !effect
            && !material_assets.alpha_test
            && !material_assets.translucent
            && material_assets.selfillum.is_none(),
    }) else {
        return reference;
    };
    reference.selfillum = material_assets.selfillum.map(|t| t.map(f64::from));
    let content_id = bundle::content_id(&bytes);
    assets_by_id
        .entry(content_id.clone())
        .or_insert((bytes, image, None, None));
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
pub(super) fn per_face_rates(
    surfaces: &[&crate::voxel::fragments::Fragment],
) -> Vec<Option<[f64; 2]>> {
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
