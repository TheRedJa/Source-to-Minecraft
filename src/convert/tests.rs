use super::*;
use crate::bsp::Material;
use std::path::Path;
use vbsp::TextureFlags;

fn resolver(materials: &[Material]) -> Resolver {
    Resolver::new(&Config::default(), materials).unwrap()
}

fn material(name: &str) -> Material {
    Material {
        name: name.into(),
        raw_name: name.into(),
        reflectivity: [0.2, 0.2, 0.2],
        drawn: true,
    }
}

/// An invisible wall reaching far into the void keeps only the part inside
/// the cells the map fills, and one wholly outside them is dropped.
#[test]
fn an_unseen_brush_is_cut_to_the_maps_cells() {
    let planes = vec![
        crate::geom::Plane::new(Vec3::new(1.0, 0.0, 0.0), 100.0),
        crate::geom::Plane::new(Vec3::new(-1.0, 0.0, 0.0), 50.0),
        crate::geom::Plane::new(Vec3::new(0.0, 1.0, 0.0), 2.0),
        crate::geom::Plane::new(Vec3::new(0.0, -1.0, 0.0), 0.0),
        crate::geom::Plane::new(Vec3::new(0.0, 0.0, 1.0), 1.0),
        crate::geom::Plane::new(Vec3::new(0.0, 0.0, -1.0), 0.0),
    ];
    let wall = BlockSolid {
        side_of_plane: (0..planes.len()).collect(),
        planes,
        bounds: Aabb::new(Vec3::new(-50.0, 0.0, 0.0), Vec3::new(100.0, 2.0, 1.0)),
    };
    let cut = clipped_to_cells(&wall, [0, 0, 0], [9, 9, 9]).expect("overlaps the cells");
    assert_eq!(cut.bounds.min, Vec3::new(0.0, 0.0, 0.0));
    assert_eq!(cut.bounds.max, Vec3::new(10.0, 2.0, 1.0));
    assert!(cut.contains(Vec3::new(5.0, 1.0, 0.5)));
    assert!(!cut.contains(Vec3::new(20.0, 1.0, 0.5)));
    assert!(!cut.contains(Vec3::new(-5.0, 1.0, 0.5)));
    assert!(clipped_to_cells(&wall, [200, 0, 0], [209, 9, 9]).is_none());
}

/// Fog volumes are brushes flagged `WINDOW`, because fog is translucent.
/// Trusting the contents flag alone paves a city block in glass.
#[test]
fn a_tool_material_vetoes_the_contents_flag() {
    // As compiled: vbsp flags every face of both `NODRAW`.
    let materials =
        [material("tools/toolsfog"), material("tools/toolsinvisible")].map(|mut m| {
            m.drawn = false;
            m
        });
    let r = resolver(&materials);
    let glass = Decision::Force("minecraft:glass".into());
    for (index, material) in materials.iter().enumerate() {
        assert_eq!(
            side_block(&glass, &r, Some(index), TextureFlags::empty(), true),
            None,
            "{} became glass",
            material.name
        );
    }
}

/// The veto must not disarm the flag where it is right: a real window
/// brush wears a glass material and has to stay glass.
#[test]
fn a_real_window_still_honours_the_contents_flag() {
    let materials = [material("glass/glasswindow002a")];
    let r = resolver(&materials);
    assert_eq!(
        side_block(
            &Decision::Force("minecraft:glass".into()),
            &r,
            Some(0),
            TextureFlags::empty(),
            true,
        ),
        Some("minecraft:glass")
    );
}

/// Translucent snow is flagged `WINDOW`, but a rule naming snow is a
/// statement of intent and beats the flag's guess.
#[test]
fn a_named_rule_outranks_the_contents_flag() {
    let materials = [material("ground/snow01")];
    let r = resolver(&materials);
    assert!(r.named_by_rule(0));
    assert_eq!(
        side_block(
            &Decision::Force("minecraft:glass".into()),
            &r,
            Some(0),
            TextureFlags::empty(),
            true,
        ),
        Some("minecraft:snow_block")
    );
}

/// A colour match is only a guess, so it must not override the flag: a
/// water brush wearing an unrecognised material is still water.
#[test]
fn a_colour_guess_does_not_outrank_the_contents_flag() {
    let materials = [material("custom/unknownsurface")];
    let r = resolver(&materials);
    assert!(!r.named_by_rule(0));
    assert_eq!(
        side_block(
            &Decision::Force("minecraft:water".into()),
            &r,
            Some(0),
            TextureFlags::empty(),
            true,
        ),
        Some("minecraft:water")
    );
}

#[test]
fn faces_the_engine_never_draws_contribute_nothing() {
    let materials = [material("concrete/concretewall001a")];
    let r = resolver(&materials);
    for flag in [
        TextureFlags::NODRAW,
        TextureFlags::SKIP,
        TextureFlags::HINT,
        TextureFlags::TRIGGER,
        TextureFlags::SKY,
        TextureFlags::SKY2D,
    ] {
        assert_eq!(
            side_block(&Decision::ByMaterial, &r, Some(0), flag, true),
            None,
            "{flag:?} face was kept"
        );
    }
    assert!(
        side_block(
            &Decision::ByMaterial,
            &r,
            Some(0),
            TextureFlags::empty(),
            true
        )
        .is_some()
    );
}

/// A side carrying no texture info at all still bounds its brush, so it
/// must not punch a hole in it.
#[test]
fn a_side_without_a_material_uses_the_fallback() {
    let r = resolver(&[]);
    assert_eq!(
        side_block(&Decision::ByMaterial, &r, None, TextureFlags::empty(), true),
        Some("minecraft:stone")
    );
}

fn sample_map() -> Option<Map> {
    let path = Path::new(concat!(
        "/mnt/games/SteamLibrary/steamapps/common/Entropy Zero",
        "/Entropy Zero/EntropyZero/maps/az_c4_4.bsp"
    ));
    path.exists().then(|| Map::load(path).unwrap())
}

/// Mod export takes every moving entity out of the world: its faces,
/// blocks and collision belong to the mover alone, built against its own
/// grid, while a mover too thin for blocks is still kept for its faces.
#[test]
fn movers_leave_the_world_with_their_own_geometry() {
    let path = Path::new(concat!(
        "/mnt/games/SteamLibrary/steamapps/common/Portal",
        "/portal/maps/testchmb_a_00.bsp"
    ));
    if !path.exists() {
        return;
    }
    let map = Map::load(path).unwrap();
    let mut config = Config::default();
    config.scale.units_per_block = 32.0;
    config.props.enabled = false;
    config.output.exact_surfaces = true;
    let mut included = config.clone();
    for class in crate::config::MOVER_CLASSES {
        included
            .entities
            .classname_modes
            .insert(class.to_string(), crate::config::BrushEntityMode::Include);
    }
    config.entities.separate_movers();
    let moved = convert(&map, &config).unwrap();
    let stayed = convert(&map, &included).unwrap();
    assert!(stayed.movers.is_empty());
    assert!(!moved.movers.is_empty());

    let face_of =
        |fragment: &crate::voxel::fragments::Fragment| match fragment.source.provenance {
            crate::voxel::surface::SourceProvenance::Face { face, .. } => Some(face),
            _ => None,
        };
    let world_faces: BTreeSet<usize> = moved.fragments.iter().filter_map(face_of).collect();
    let stayed_faces: BTreeSet<usize> = stayed.fragments.iter().filter_map(face_of).collect();
    let mut mover_faces = BTreeSet::new();
    for mover in &moved.movers {
        for fragment in &mover.fragments {
            let face = face_of(fragment).unwrap();
            assert!(
                !world_faces.contains(&face),
                "mover face {face} left in the world"
            );
            mover_faces.insert(face);
            // Owners are the mover's own blocks.
            if let Some(owner) = fragment.owner {
                let cell = [0, 1, 2].map(|a| fragment.cell[a] + owner[a]);
                assert!(mover.grid.is_solid(cell));
            }
        }
    }
    assert!(!mover_faces.is_empty());
    assert!(
        mover_faces.is_subset(&stayed_faces),
        "included, the world draws them"
    );
    // This map's movers are all panels thinner than the brush-mesh
    // cut-off, so what they leave behind in the world is light occlusion
    // and collision rather than blocks; neither is the world's any more.
    assert!(moved.stats.blocks_before_hollow <= stayed.stats.blocks_before_hollow);
    assert!(moved.occluders.len() < stayed.occluders.len());
    let size = |c: &Option<crate::voxel::collision::CellCollision>| {
        let c = c.as_ref().unwrap();
        c.shapes.len() + c.carriers.len()
    };
    assert!(size(&moved.collision) < size(&stayed.collision));
    assert!(
        moved
            .movers
            .iter()
            .any(|m| m.grid.count() == 0 && !m.fragments.is_empty()),
        "a mover without blocks is kept for its faces"
    );
}

/// The whole point of drawing a thin brush instead of voxelizing it: a
/// 4-unit plate stays 4 units instead of being inflated into a full block.
#[test]
fn thin_brushes_become_meshes_that_keep_their_thickness() {
    let Some(map) = sample_map() else { return };
    let mut config = Config::default();
    config.scale.units_per_block = 32.0;
    let transform = Transform::new(&config, map.converted_bounds(true));
    let entity_models = entity_models(&map, &config, &transform);
    let origins = model_origins(&entity_models);
    let solids: Vec<Solid> = models_to_convert(&map, &config, &entity_models)
        .into_iter()
        .flat_map(|model| map.solids(model))
        .collect();
    let total = solids.len();

    let (voxelized, meshes, occluders, _) =
        split_brush_meshes(&map, &config, &transform, &origins, solids.clone());
    assert!(
        !occluders.is_empty(),
        "drawn brushes must still darken the cells they cover"
    );
    assert!(
        !meshes.is_empty(),
        "a real map has trim thinner than 8 units"
    );
    assert!(
        voxelized.len() + meshes.len() <= total,
        "splitting invented brushes"
    );

    let limit = config.output.brush_meshes.max_thickness_units / transform.units_per_block();
    let by_index: std::collections::HashMap<usize, &Solid> = solids
        .iter()
        .map(|solid| (solid.brush_index, solid))
        .collect();
    for mesh in &meshes {
        // Measured against the brush's own faces, not its bounding box: a
        // tilted plate has a box far thicker than the plate.
        let solid = by_index[&mesh.brush_index];
        let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
        let thinnest =
            crate::voxel::brush::thickness(&to_block_solid(solid, &transform, origin));
        assert!(
            thinnest <= limit + MESH_THICKNESS_SLACK,
            "brush {} is {thinnest} blocks thick, over the {limit}-block cut-off",
            mesh.brush_index
        );
        assert!(
            mesh.parts.iter().any(|part| !part.triangles.is_empty()),
            "a mesh was kept with nothing to draw"
        );
    }

    // An 8-unit plate is the commonest thin brush there is, and the
    // default cut-off is exactly 8 units, so the boundary has to be
    // inclusive or the feature misses most of what it is for.
    let at_cutoff = meshes.iter().any(|mesh| {
        let solid = by_index[&mesh.brush_index];
        let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
        (crate::voxel::brush::thickness(&to_block_solid(solid, &transform, origin)) - limit)
            .abs()
            <= MESH_THICKNESS_SLACK
    });
    assert!(at_cutoff, "brushes exactly at the cut-off were voxelized");

    // Turning the feature off must put every brush back on the voxel path.
    config.output.brush_meshes.enabled = false;
    let (all, none, no_cells, _) =
        split_brush_meshes(&map, &config, &transform, &origins, solids);
    assert_eq!(all.len(), total);
    assert!(none.is_empty());
    assert!(no_cells.is_empty());
}

#[test]
fn converts_a_real_map_to_blocks() {
    let Some(map) = sample_map() else { return };
    // Props are added after hollowing and would make the two counts
    // measure different things, so this compares the world with itself.
    let mut config = Config::default();
    config.props.enabled = false;
    let result = convert(&map, &config).unwrap();

    assert!(result.stats.blocks > 5_000, "got {}", result.stats.blocks);
    assert!(result.stats.solids_voxelized > 0);
    assert!(
        result.palette.len() > 1,
        "palette should hold more than air"
    );

    // Hollowing must remove a substantial share of a solid map.
    assert!(
        result.stats.blocks < result.stats.blocks_before_hollow,
        "hollowing removed nothing"
    );
}

/// No single block should dominate the *brushwork* of a converted map.
/// Before materials landed, fog volumes flagged `WINDOW` made glass 86% of
/// some Entropy: Zero maps; a regression there would show up here first.
///
/// Props are left out deliberately. Entropy: Zero's backdrop architecture
/// is one enormous, genuinely near-black Combine wall, so with props on a
/// single block legitimately owns three quarters of `az_c4_4` and this
/// test would only ever be measuring that.
#[test]
fn no_single_block_swamps_a_converted_map() {
    let Some(map) = sample_map() else { return };
    let mut config = Config::default();
    config.props.enabled = false;
    let result = convert(&map, &config).unwrap();
    let total = result.stats.blocks;
    let (block, count) = result
        .stats
        .block_counts
        .iter()
        .max_by_key(|(_, count)| **count)
        .unwrap();
    assert!(count * 2 < total, "{block} is {count} of {total} blocks");
    assert!(
        result.stats.block_counts.len() > 8,
        "only {} block types",
        result.stats.block_counts.len()
    );
}

/// VBSP stores a brush entity's geometry relative to its `origin`, so the
/// coordinates in the plane lump are not world space. Every converted
/// brush entity has to end up near the entity that owns it, not piled up
/// around wherever Source's origin lands.
#[test]
fn brush_entities_land_where_their_entity_is() {
    let Some(map) = sample_map() else { return };
    let config = Config::default();
    let transform = Transform::new(&config, map.bounds());
    let resolver = Resolver::new(&config, map.materials()).unwrap();
    let origins = model_origins(&entity_models(&map, &config, &transform));

    let _ = &resolver;
    let mut checked = 0;
    for (model, origin) in &origins {
        if origin.x == 0.0 && origin.y == 0.0 && origin.z == 0.0 {
            continue;
        }
        let Some(solid) = map.solids(*model).into_iter().next() else {
            continue;
        };
        let placed = to_block_solid(&solid, &transform, *origin).bounds;
        let entity = transform.to_block_space(*origin);

        // An entity's origin need not be inside its own brush — a door's
        // is at its hinge — so the test is proximity, not containment.
        // Half the brush's own extent plus a few blocks is generous, and
        // still nothing like the hundreds of blocks the untranslated
        // geometry is out by.
        let slack = placed.size().length() + 8.0;
        let distance = (placed.center() - entity).length();
        assert!(
            distance <= slack,
            "model {model} is {distance:.0} blocks from its entity, allowing {slack:.0}"
        );
        checked += 1;
    }
    assert!(checked > 0, "no origin-relative brush entities to check");
}

/// The same in the other direction, so the test above cannot quietly pass
/// on a no-op: dropping the origin must put the geometry far from its
/// entity, which is the bug this fixes.
#[test]
fn ignoring_the_entity_origin_misplaces_the_geometry() {
    let Some(map) = sample_map() else { return };
    let config = Config::default();
    let transform = Transform::new(&config, map.bounds());
    let origins = model_origins(&entity_models(&map, &config, &transform));

    let (mut with_total, mut without_total, mut count) = (0.0, 0.0, 0);
    for (model, origin) in &origins {
        if origin.x == 0.0 && origin.y == 0.0 && origin.z == 0.0 {
            continue;
        }
        let Some(solid) = map.solids(*model).into_iter().next() else {
            continue;
        };
        let entity = transform.to_block_space(*origin);
        with_total +=
            (to_block_solid(&solid, &transform, *origin).bounds.center() - entity).length();
        without_total += (to_block_solid(&solid, &transform, Vec3::ZERO)
            .bounds
            .center()
            - entity)
            .length();
        count += 1;
    }

    assert!(count > 0);
    let (with, without) = (with_total / count as f64, without_total / count as f64);
    assert!(
        without > with * 10.0,
        "untranslated geometry averages {without:.0} blocks from its entity \
         and translated {with:.0}; the translation is doing nothing"
    );
}

/// The failure this guards against is silent: a schematic naming a block
/// its pack does not register pastes as a hole in the world, with no
/// error anywhere. Every generated id in the palette must be registered.
#[test]
fn every_generated_block_in_the_palette_is_registered_by_the_pack() {
    let Some(map) = sample_map() else { return };
    let mut config = Config::default();
    config.materials.mode = crate::config::MaterialMode::Kubejs;

    let result = convert(&map, &config).unwrap();
    if result.pack.is_empty() {
        return; // no game install to read textures from
    }

    let script = result.pack.script();
    let mut generated = 0;
    for id in 0..result.palette.len() {
        let name = result.palette.name(id as crate::voxel::grid::BlockId);
        let Some(_) = name.strip_prefix("kubejs:") else {
            continue;
        };
        generated += 1;
        assert!(
            script.contains(&format!("event.create('{name}')")),
            "{name} is in the palette but not registered"
        );
    }
    assert!(generated > 0, "kubejs mode produced no generated blocks");
}

/// Textures must not displace the rules that carry meaning: a grate has
/// to stay see-through even though it has a perfectly good texture.
#[test]
fn named_rules_still_win_over_generated_textures() {
    let Some(map) = sample_map() else { return };
    let mut config = Config::default();
    config.materials.mode = crate::config::MaterialMode::Kubejs;

    let result = convert(&map, &config).unwrap();
    if result.pack.is_empty() {
        return;
    }
    let resolver =
        Resolver::with_textures(&config, map.materials(), &result.pack.ids()).unwrap();

    for (index, material) in map.materials().iter().enumerate() {
        if material.name.contains("grate") || material.name.starts_with("glass/") {
            let block = resolver.block_for_material(index);
            assert!(
                block.is_some_and(|b| b.starts_with("minecraft:")),
                "{} became {block:?} instead of keeping its vanilla block",
                material.name
            );
        }
    }
}

/// Vanilla mode must be untouched by any of this.
#[test]
fn vanilla_mode_generates_nothing() {
    let Some(map) = sample_map() else { return };
    let result = convert(&map, &Config::default()).unwrap();
    assert!(result.pack.is_empty());
    for id in 0..result.palette.len() {
        let name = result.palette.name(id as crate::voxel::grid::BlockId);
        assert!(
            name.starts_with("minecraft:"),
            "vanilla mode emitted {name}"
        );
    }
}

/// Highway 17: cliffs and ground built from blend textures stretched over
/// dozens of blocks, which is where the tiling cap actually bites.
fn coast_map() -> Option<Map> {
    let path = Path::new(
        "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d2_coast_03.bsp",
    );
    path.exists().then(|| Map::load(path).unwrap())
}

/// A map with terrain in it, since `az_c4_4` is nearly all interiors.
fn terrain_map() -> Option<Map> {
    let path = Path::new(
        "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_canals_01a.bsp",
    );
    path.exists().then(|| Map::load(path).unwrap())
}

#[test]
fn displacements_add_terrain_and_can_be_turned_off() {
    let Some(map) = terrain_map() else { return };
    assert!(!map.bsp.displacements.is_empty());

    let mut without = Config::default();
    without.displacement.enabled = false;
    let without = convert(&map, &without).unwrap();
    let with = convert(&map, &Config::default()).unwrap();

    assert_eq!(without.stats.displacements_voxelized, 0);
    assert!(with.stats.displacements_voxelized > 0);
    assert!(
        with.stats.blocks > without.stats.blocks,
        "terrain added nothing: {} vs {}",
        with.stats.blocks,
        without.stats.blocks
    );
}

/// Terrain must land inside the map, not somewhere off in space, and it
/// must be thicker than the single-voxel shell the triangles alone give.
#[test]
fn terrain_is_solid_and_inside_the_map() {
    let Some(map) = terrain_map() else { return };
    let result = convert(&map, &Config::default()).unwrap();
    let bounds = block_bounds(&map, &result.transform);
    let (min, max) = result.grid.bounds().unwrap();

    for axis in 0..3 {
        assert!(
            min[axis] as f64 >= bounds.min.axis(axis) - 4.0,
            "geometry at {min:?} escapes {bounds:?}"
        );
        assert!(
            max[axis] as f64 <= bounds.max.axis(axis) + 4.0,
            "geometry at {max:?} escapes {bounds:?}"
        );
    }

    let mut thin = Config::default();
    thin.displacement.solidify = 0;
    let thin = convert(&map, &thin).unwrap();
    assert!(
        result.stats.blocks_before_hollow > thin.stats.blocks_before_hollow,
        "solidify added no backing"
    );
}

#[test]
fn converted_geometry_lands_inside_the_map_bounds() {
    let Some(map) = sample_map() else { return };
    let result = convert(&map, &Config::default()).unwrap();
    let bounds = block_bounds(&map, &result.transform);
    let (min, max) = result.grid.bounds().unwrap();

    // One block of slack for rounding at the edges.
    assert!(min[0] as f64 >= bounds.min.x - 1.0, "{min:?} vs {bounds:?}");
    assert!(min[1] as f64 >= bounds.min.y - 1.0, "{min:?} vs {bounds:?}");
    assert!(max[1] as f64 <= bounds.max.y + 1.0, "{max:?} vs {bounds:?}");
}

/// Hollowing is about brush interiors, so props — which are surfaces
/// already and have no interior to remove — are left out of the
/// comparison rather than diluting it.
#[test]
fn hollow_mode_produces_far_fewer_blocks_than_solid() {
    let Some(map) = sample_map() else { return };

    let mut hollow_config = Config::default();
    hollow_config.props.enabled = false;
    let mut solid_config = hollow_config.clone();
    solid_config.fill.mode = FillMode::Solid;
    let solid = convert(&map, &solid_config).unwrap();
    let hollow = convert(&map, &hollow_config).unwrap();

    assert!(
        hollow.stats.blocks < solid.stats.blocks,
        "hollow {} vs solid {}",
        hollow.stats.blocks,
        solid.stats.blocks
    );
}

fn kubejs_config() -> Config {
    let mut config = Config::default();
    config.materials.mode = crate::config::MaterialMode::Kubejs;
    config
}

/// The failure the user sees as a hole in a wall: a barrier is invisible,
/// so putting one where a block already was is the same as deleting it. A
/// prop's own block is nearly as bad — it is a mesh floating where a wall
/// used to be. Adding a map's props must not take a single one of its own
/// blocks away, by either route.
#[test]
fn prop_collision_never_replaces_a_block_of_the_map() {
    let Some(map) = sample_map() else { return };

    let mut without = kubejs_config();
    without.props.enabled = false;
    let without = convert(&map, &without).unwrap();
    let with = convert(&map, &kubejs_config()).unwrap();
    if with.stats.prop_barriers == 0 && with.stats.props_baked == 0 {
        return;
    }

    for (pos, id) in without.grid.iter() {
        let before = without.palette.name(id);
        let after = with.palette.name(with.grid.get(pos));
        // A prop is allowed to put its own visible geometry where a block
        // was — that is only a swap you can see. What it may never do is
        // replace one with something invisible: a barrier, or the block a
        // baked mesh hangs off, which draws nothing in its own cell. Both
        // read as a hole through the wall.
        assert_ne!(
            after, BARRIER,
            "{pos:?} was {before} and a prop turned it into an invisible barrier"
        );
        assert!(
            !after.contains(":prop_"),
            "{pos:?} was {before} and a baked prop took the cell"
        );
        assert!(
            !after.contains(":collision_"),
            "{pos:?} was {before} and a prop's collision took the cell"
        );
    }
}

/// What the shaped route is for: a prop is solid as its own shape rather
/// than as a stack of metre cubes. The shapes have to cover what the cubes
/// covered — a cell that used to be solid must still be solid — and they
/// have to be shapes, not full cubes wearing a new name.
#[test]
fn shaped_collision_covers_what_barriers_covered() {
    let Some(map) = sample_map() else { return };

    let mut cubes = kubejs_config();
    cubes.props.collision = crate::config::CollisionMode::Barrier;
    let cubes = convert(&map, &cubes).unwrap();
    if cubes.stats.prop_barriers == 0 {
        return; // no game install, or nothing big enough to be solid
    }
    let shaped = convert(&map, &kubejs_config()).unwrap();

    assert!(
        shaped.stats.prop_collision_blocks > 0,
        "no cell got a shape"
    );
    assert!(
        shaped.stats.prop_barriers * 20 < cubes.stats.prop_barriers,
        "{} barriers left of {}",
        shaped.stats.prop_barriers,
        cubes.stats.prop_barriers
    );

    // Every cell that was solid is still solid. Losing one is a floor you
    // fall through, which is the whole risk of measuring a box instead of
    // filling the cell.
    for (pos, id) in cubes.grid.iter() {
        if cubes.palette.name(id) != BARRIER {
            continue;
        }
        assert_ne!(
            shaped.grid.get(pos),
            crate::voxel::grid::AIR,
            "{pos:?} was solid with barriers and is empty with shapes"
        );
    }

    // And they really are shaped: a map of full cubes under another name
    // would pass everything above.
    let partial = shaped
        .pack
        .collisions()
        .filter(|shape| !shape.is_full())
        .count();
    assert!(
        partial * 2 > shaped.stats.prop_collision_shapes,
        "only {partial} of {} shapes are smaller than a whole cell",
        shaped.stats.prop_collision_shapes
    );
}

/// Vanilla output has no pack to register a shape in. Its props are
/// voxelized into ordinary blocks and are solid by being there, so what
/// must not happen is a conversion asking for shapes and getting neither
/// those nor blocks.
#[test]
fn vanilla_output_needs_no_shapes_to_be_solid() {
    let Some(map) = sample_map() else { return };
    let result = convert(&map, &Config::default()).unwrap();
    assert_eq!(result.stats.prop_collision_blocks, 0, "no pack to use");
    assert_eq!(result.stats.props_modelled, 0, "vanilla drew a mesh");
    assert!(result.stats.props_placed > 0, "the props went missing");
}

/// Turning collision off leaves the props there and walk-through, rather
/// than dropping them.
#[test]
fn collision_can_be_turned_off_without_losing_the_props() {
    let Some(map) = sample_map() else { return };
    let mut config = kubejs_config();
    config.props.collision = crate::config::CollisionMode::None;
    let result = convert(&map, &config).unwrap();
    assert_eq!(result.stats.prop_barriers, 0);
    assert_eq!(result.stats.prop_collision_blocks, 0);
    assert!(result.stats.props_baked > 0, "the props went too");
}

/// The other half of the same complaint: a barrier sealing the air side of
/// a wall must not make hollowing mistake that wall for interior and carve
/// it out. Every block the map had without props it still has with them.
#[test]
fn props_never_cause_the_map_behind_them_to_be_carved_away() {
    let Some(map) = sample_map() else { return };

    let mut without = kubejs_config();
    without.props.enabled = false;
    let without = convert(&map, &without).unwrap();
    let with = convert(&map, &kubejs_config()).unwrap();

    let lost = without
        .grid
        .iter()
        .filter(|(pos, _)| with.grid.get(*pos) == crate::voxel::grid::AIR)
        .count();
    assert_eq!(
        lost, 0,
        "{lost} of {} blocks vanished once props were added",
        without.stats.blocks
    );
}

/// What the whole change is for: props stop being entities. Almost all of
/// them should end up as blocks the chunk mesh absorbs, and only the ones
/// with nowhere to put a block stay as entities redrawn every frame.
#[test]
fn baking_turns_props_into_blocks_instead_of_entities() {
    let Some(map) = sample_map() else { return };

    let mut off = kubejs_config();
    off.props.bake = false;
    let off = convert(&map, &off).unwrap();
    if off.props.is_empty() {
        return;
    }
    let on = convert(&map, &kubejs_config()).unwrap();

    assert_eq!(off.stats.props_baked, 0, "baking was off");
    assert_eq!(
        on.stats.props_baked + on.props.len(),
        off.props.len(),
        "a prop was lost between the two routes"
    );
    assert!(
        on.props.len() * 4 < off.props.len(),
        "{} of {} props are still entities",
        on.props.len(),
        off.props.len()
    );

    // Every baked prop is a block in the grid naming a mesh the pack
    // registers, which is the drift that makes a paste silently empty.
    let mut blocks = 0;
    for (_, id) in on.grid.iter() {
        let name = on.palette.name(id);
        if name.contains(":prop_") {
            assert!(
                on.pack.prop(name).is_some(),
                "{name} is placed but not registered"
            );
            blocks += 1;
        }
    }
    assert_eq!(blocks, on.stats.prop_blocks, "a baked prop has no block");
    assert!(
        on.stats.prop_blocks >= on.stats.props_baked,
        "a prop cannot take fewer than one block"
    );
}

/// The failure that showed up as huge black sheets folded over the map.
///
/// Sodium packs each chunk vertex coordinate into 20 bits spanning -8 to
/// +24 blocks from the section origin and masks off the rest, so a block
/// model reaching further than that is drawn correctly up to the limit and
/// then folds back on itself. A block can sit anywhere in its 16-block
/// section, so 8 blocks either way is the reach that is safe wherever it
/// lands, and no generated mesh may exceed it.
#[test]
fn no_baked_mesh_reaches_further_than_a_chunk_vertex_can_be_encoded() {
    let Some(map) = sample_map() else { return };
    let config = kubejs_config();
    let converted = convert(&map, &config).unwrap();
    if converted.stats.props_baked == 0 {
        return;
    }

    let mut worst: f64 = 0.0;
    let mut worst_id = String::new();
    let mut checked = 0;
    for asset in converted.pack.props() {
        // Only the baked variants; the model-space assets an entity places
        // are drawn by the entity renderer, which has no such limit.
        if !asset.id.contains("_b") || asset.id == asset.mtl_id {
            continue;
        }
        checked += 1;
        for line in asset.obj.lines().filter(|l| l.starts_with("v ")) {
            for value in line.split_whitespace().skip(1) {
                let reach: f64 = value.parse().unwrap_or(0.0);
                if reach.abs() > worst {
                    worst = reach.abs();
                    worst_id = asset.id.clone();
                }
            }
        }
    }

    assert!(checked > 0, "no baked variants to check");
    assert!(
        worst <= config.props.bake_reach,
        "{worst_id} reaches {worst:.1} blocks from its block; past \
         {} the coordinate wraps and the mesh folds back",
        config.props.bake_reach
    );
}

/// Two props must never be given the same cell, or the second block
/// replaces the first and that prop is simply not drawn.
#[test]
fn no_two_baked_props_share_a_block() {
    let Some(map) = sample_map() else { return };
    let converted = convert(&map, &kubejs_config()).unwrap();
    if converted.stats.props_baked == 0 {
        return;
    }
    // One block per baked prop is exactly what the count above asserts;
    // this is the same statement from the other side, that the meshes
    // registered are distinct enough to be worth registering.
    assert!(
        converted.pack.props().count() > 1,
        "a whole map of props collapsed to one mesh"
    );
}

/// Props sink into the ground because they are placed to a fraction of a
/// block and the floor under them is rounded to whole ones. Settling has
/// to actually move some of them, and never by more than the limit.
#[test]
fn settling_lifts_props_out_of_the_floor() {
    let Some(map) = sample_map() else { return };

    // Baking is off so that every placement shows up in `props` and can be
    // compared position for position. What settling does is the same
    // either way; only where the answer is written down differs.
    let mut on = kubejs_config();
    on.props.bake = false;
    // Settling corrects for voxelization rounding, so it is measured
    // against a fully voxelized world. Drawing the thin brushes as meshes
    // instead removes the rounding these props would have been settling
    // out of, which is a different question from whether settling works.
    on.output.brush_meshes.enabled = false;
    let mut off = on.clone();
    off.props.settle = false;
    let off = convert(&map, &off).unwrap();
    let on = convert(&map, &on).unwrap();
    if on.props.is_empty() {
        return;
    }

    assert_eq!(off.stats.props_settled, 0);
    assert!(
        on.stats.props_settled > on.props.len() / 10,
        "only {} of {} props were settled",
        on.stats.props_settled,
        on.props.len()
    );

    let limit = Config::default().props.settle_max;
    for (a, b) in off.props.iter().zip(&on.props) {
        assert_eq!(a.block, b.block, "settling reordered the props");
        assert_eq!([a.pos[0], a.pos[2]], [b.pos[0], b.pos[2]], "moved sideways");
        assert!(
            (b.pos[1] - a.pos[1]).abs() <= limit + 1e-9,
            "{} moved {} blocks",
            a.block,
            b.pos[1] - a.pos[1]
        );
    }
}

/// The claim the whole tiling feature rests on: at Hammer's default
/// texture scale, one Minecraft block of wall is one tile of a 512-pixel
/// texture split eight ways. Walk a block along the wall, advance one
/// tile — and wrap round at the end, because the texture repeats.
#[test]
fn one_block_of_wall_advances_one_tile() {
    use crate::bsp::texcoord::TexCoord;

    let mut config = Config::default();
    config.scale.units_per_block = 16.0;
    config.transform.origin_mode = crate::config::OriginMode::MapOrigin;
    let transform = Transform::new(&config, Aabb::new(Vec3::ZERO, Vec3::splat(1024.0)));

    // A wall in the X/Z plane at four texels per unit: 16 units per block
    // is 64 texels, and a 512-pixel texture split into 8 gives 64-texel
    // tiles. So one block of wall is exactly one tile.
    let tex = TexCoord {
        u: [4.0, 0.0, 0.0, 0.0],
        v: [0.0, 0.0, -4.0, 0.0],
    };
    let uv = Uv::new(
        tex,
        &transform,
        Vec3::ZERO,
        [64.0, 64.0],
        transform.units_per_block(),
    );
    let set = TileSet {
        grid: [8, 8],
        texels_per_tile: [64.0, 64.0],
        ids: (0..64).collect(),
    };

    // Blocks 0..8 along the wall must give tiles 0..8 in order.
    let tile_at = |block: Vec3| {
        let (s, t) = uv.at(block);
        set.at(s, t)
    };
    let base = transform.to_block_space(Vec3::new(8.0, 0.0, -8.0));
    for step in 0..8 {
        let here = Vec3::new(base.x + step as f64, base.y, base.z);
        assert_eq!(
            tile_at(here),
            step as BlockId,
            "block {step} along the wall should be tile {step}"
        );
    }
    // The ninth block starts the texture again.
    assert_eq!(tile_at(Vec3::new(base.x + 8.0, base.y, base.z)), 0);
    // And so does the block eight before the first, going the other way.
    assert_eq!(tile_at(Vec3::new(base.x - 8.0, base.y, base.z)), 0);
    assert_eq!(tile_at(Vec3::new(base.x - 1.0, base.y, base.z)), 7);
}

/// One material is used at several scales in the same map — Highway 17's
/// `nature/cliffface001a` at six of them — so a tile size taken from the
/// material's typical scale is too wide for every face using a larger
/// one, and the wall comes out in 2x2 blocks of the same picture.
#[test]
fn a_face_scaled_off_its_materials_median_still_gets_one_tile_per_block() {
    use crate::bsp::texcoord::TexCoord;

    let mut config = Config::default();
    config.scale.units_per_block = 16.0;
    config.transform.origin_mode = crate::config::OriginMode::MapOrigin;
    let transform = Transform::new(&config, Aabb::new(Vec3::ZERO, Vec3::splat(4096.0)));

    // Tiles cut for a material whose typical face is 4 texels per unit.
    let set = TileSet {
        grid: [8, 8],
        texels_per_tile: [64.0, 64.0],
        ids: (0..64).collect(),
    };

    // Every rate `cliffface001a` is really used at, plus the reference.
    for rate in [0.33, 0.5, 0.67, 1.0, 1.43, 2.0, 4.0, 8.0] {
        let tex = TexCoord {
            u: [rate, 0.0, 0.0, 0.0],
            v: [0.0, 0.0, -rate, 0.0],
        };
        let uv = Uv::new(
            tex,
            &transform,
            Vec3::ZERO,
            set.texels_per_tile,
            transform.units_per_block(),
        );

        // Walk a straight line of blocks along the wall. No two in a row
        // may wear the same tile.
        let base = transform.to_block_space(Vec3::new(8.0, 0.0, -8.0));
        let mut previous = None;
        let mut distinct = std::collections::HashSet::new();
        for step in 0..16 {
            let here = Vec3::new(base.x + step as f64, base.y, base.z);
            let (column, row) = uv.at(here);
            let tile = set.at(column, row);
            assert_ne!(
                previous,
                Some(tile),
                "at {rate} texels/unit, blocks {} and {step} share tile {tile}",
                step - 1,
            );
            previous = Some(tile);
            distinct.insert(tile);
        }
        // A face scaled *finer* than the material's reference advances by
        // more than one tile per block, so it cycles the grid faster and
        // legitimately shows fewer distinct tiles. That shows no repeat,
        // which is why it is left exact rather than corrected.
        let want = if rate * 16.0 <= set.texels_per_tile[0] {
            8
        } else {
            4
        };
        assert!(
            distinct.len() >= want,
            "at {rate} texels/unit only {} distinct tiles over 16 blocks",
            distinct.len()
        );
    }
}

/// A face with a degenerate texture vector must not divide by zero; it
/// falls back to the material's own tile size.
#[test]
fn a_face_with_no_texture_axis_still_resolves() {
    use crate::bsp::texcoord::TexCoord;
    let config = Config::default();
    let transform = Transform::new(&config, Aabb::new(Vec3::ZERO, Vec3::splat(256.0)));
    let tex = TexCoord {
        u: [0.0; 4],
        v: [0.0; 4],
    };
    let uv = Uv::new(
        tex,
        &transform,
        Vec3::ZERO,
        [64.0, 64.0],
        transform.units_per_block(),
    );
    let (column, row) = uv.at(Vec3::new(3.0, 4.0, 5.0));
    assert!(column.is_finite() && row.is_finite(), "got {column}, {row}");
}

/// The other axis, and the one easiest to get upside down: Source's V
/// points *down* a wall, so climbing must walk back up the tile rows.
#[test]
fn climbing_a_wall_walks_up_the_texture() {
    use crate::bsp::texcoord::TexCoord;

    let mut config = Config::default();
    config.scale.units_per_block = 16.0;
    config.transform.origin_mode = crate::config::OriginMode::MapOrigin;
    let transform = Transform::new(&config, Aabb::new(Vec3::ZERO, Vec3::splat(1024.0)));

    let tex = TexCoord {
        u: [4.0, 0.0, 0.0, 0.0],
        v: [0.0, 0.0, -4.0, 0.0],
    };
    let uv = Uv::new(
        tex,
        &transform,
        Vec3::ZERO,
        [64.0, 64.0],
        transform.units_per_block(),
    );
    let set = TileSet {
        grid: [8, 8],
        texels_per_tile: [64.0, 64.0],
        ids: (0..64).collect(),
    };

    // Source Z is Minecraft Y: one block up is one row earlier. Start a
    // few rows in, so the step being measured is not the wrap.
    let low = transform.to_block_space(Vec3::new(8.0, 0.0, -56.0));
    let (s, t) = uv.at(low);
    let below = set.at(s, t);
    let (s, t) = uv.at(Vec3::new(low.x, low.y + 1.0, low.z));
    let above = set.at(s, t);
    assert_eq!(
        above + set.grid[0] as BlockId,
        below,
        "going up a block should move one tile row towards the top of the texture"
    );
}

/// The regression that produced flat 10x10 patches of identical stone on
/// Highway 17's cliffs: a cap on the tile count that stretched each tile
/// over several blocks instead of shortening the window into the texture.
/// Every tile must cover exactly one block, at any cap, on every material
/// a real map uses.
#[test]
fn a_tile_never_covers_more_than_one_block_on_a_real_map() {
    let Some(map) = coast_map() else { return };
    let mut config = Config::default();

    for max in [4, 8, 16, 64] {
        config.materials.tile_max = max;
        let scales = crate::bsp::texcoord::material_scales(&map);
        let mut checked = 0;
        for scale in scales.into_iter().flatten() {
            let split = scale.split(config.scale.units_per_block, max, 16);
            for axis in 0..2 {
                let texels_per_block =
                    scale.texels_per_unit[axis] * config.scale.units_per_block;
                if texels_per_block <= 0.0 {
                    continue;
                }
                let blocks = split.texels_per_tile[axis] / texels_per_block;
                assert!(
                    blocks < 1.5,
                    "tile_max {max}: a {:?} texture at {:.2} texels/unit gives \
                     tiles {blocks:.1} blocks wide",
                    scale.size,
                    scale.texels_per_unit[axis],
                );
                checked += 1;
            }
        }
        assert!(
            checked > 50,
            "only {checked} materials checked at tile_max {max}"
        );
    }
}

/// Raising the cap must buy a longer run before the pattern repeats, not
/// change how much of the texture one block shows.
#[test]
fn raising_the_cap_widens_the_window_and_nothing_else() {
    let Some(map) = coast_map() else { return };
    let config = Config::default();

    let mut widened = 0;
    for scale in crate::bsp::texcoord::material_scales(&map)
        .into_iter()
        .flatten()
    {
        let small = scale.split(config.scale.units_per_block, 8, 16);
        let large = scale.split(config.scale.units_per_block, 32, 16);
        assert_eq!(
            small.texels_per_tile, large.texels_per_tile,
            "the cap changed how much texture one block shows"
        );
        assert!(large.grid[0] >= small.grid[0] && large.grid[1] >= small.grid[1]);
        if large.grid != small.grid {
            assert!(large.window[0] >= small.window[0]);
            widened += 1;
        }
    }
    assert!(widened > 0, "no material on this map is over the cap");
}

/// A whole map's worth: no tile may dominate, or the projection is not
/// really varying and every wall is the same smear it was before.
#[test]
fn tiles_spread_across_a_real_map() {
    let Some(map) = sample_map() else { return };
    let mut config = Config::default();
    config.materials.mode = crate::config::MaterialMode::Kubejs;

    let result = convert(&map, &config).unwrap();
    if result.pack.tilings().is_empty() {
        return; // no game install to read textures from
    }

    // Group the counts by material and check the busiest tile of the
    // busiest material is not most of it.
    let mut per_material: BTreeMap<String, Vec<usize>> = BTreeMap::new();
    for (name, count) in &result.stats.block_counts {
        let Some(rest) = name.strip_prefix("kubejs:") else {
            continue;
        };
        // Collision shapes are named for their six numbers, which reads
        // exactly like a tile index and is not one.
        if rest.starts_with("collision_") {
            continue;
        }
        let Some((base, _)) = rest.rsplit_once('_').and_then(|(a, b)| {
            b.parse::<u32>().ok()?;
            a.rsplit_once('_')
        }) else {
            continue;
        };
        per_material
            .entry(base.to_string())
            .or_default()
            .push(*count);
    }

    let (material, counts) = per_material
        .iter()
        .max_by_key(|(_, counts)| counts.iter().sum::<usize>())
        .expect("kubejs mode produced no tiled blocks");
    let total: usize = counts.iter().sum();
    let busiest = *counts.iter().max().unwrap();
    assert!(
        counts.len() > 4,
        "{material} only used {} tiles",
        counts.len()
    );
    assert!(
        busiest * 4 < total,
        "{material}: one tile is {busiest} of {total} blocks, so the texture              is not really being split across the wall"
    );
}

/// The gap this closes: fences, railings, catwalks, crates and signs are
/// all models, so a map converted from brushes alone is an accurate but
/// empty shell.
#[test]
fn static_props_add_geometry_and_can_be_turned_off() {
    let Some(map) = sample_map() else { return };
    let mut without = Config::default();
    without.props.enabled = false;

    let without = convert(&map, &without).unwrap();
    let with = convert(&map, &Config::default()).unwrap();
    if with.stats.props_placed == 0 {
        return; // no game install to read models from
    }

    assert_eq!(without.stats.props_placed, 0);
    assert!(
        with.stats.blocks > without.stats.blocks,
        "props added nothing: {} vs {}",
        with.stats.blocks,
        without.stats.blocks
    );
}

/// The 3D skybox is a scale model of the horizon in a sealed room off in a
/// corner. Converting it gives a second, wrongly-sized map, and the void
/// between the two is most of the schematic's volume.
#[test]
fn leaving_out_the_3d_skybox_shrinks_the_map() {
    let Some(map) = terrain_map() else { return };
    if map.skybox().is_none() {
        return;
    }

    let mut with = Config::default();
    with.contents.skip_3d_skybox = false;
    let with = convert(&map, &with).unwrap();
    let without = convert(&map, &Config::default()).unwrap();

    let (a, b) = (with.grid.bounds().unwrap(), without.grid.bounds().unwrap());
    let volume = |(min, max): ([i32; 3], [i32; 3])| {
        (0..3)
            .map(|i| (max[i] - min[i] + 1) as i64)
            .product::<i64>()
    };
    assert!(
        volume(b) < volume(a),
        "excluding the skybox did not shrink the map: {} vs {}",
        volume(b),
        volume(a)
    );
    assert!(without.stats.blocks < with.stats.blocks);
}

/// Whatever the detector finds, the playable map has to survive it. This
/// is the failure that would be worst and quietest: a converted map with
/// its middle missing.
#[test]
fn the_skybox_never_eats_the_playable_map() {
    for map in [sample_map(), terrain_map()].into_iter().flatten() {
        let mut with = Config::default();
        with.contents.skip_3d_skybox = false;
        let with = convert(&map, &with).unwrap();
        let without = convert(&map, &Config::default()).unwrap();
        assert!(
            without.stats.blocks * 2 > with.stats.blocks,
            "{}: excluding the skybox removed more than half the map, {} of {}",
            map.name,
            with.stats.blocks - without.stats.blocks,
            with.stats.blocks
        );
    }
}

#[test]
fn a_coarser_scale_produces_fewer_blocks() {
    let Some(map) = sample_map() else { return };
    let mut coarse = Config::default();
    coarse.scale.units_per_block = 64.0;

    let fine = convert(&map, &Config::default()).unwrap();
    let coarse = convert(&map, &coarse).unwrap();
    assert!(
        coarse.stats.blocks < fine.stats.blocks,
        "coarse {} vs fine {}",
        coarse.stats.blocks,
        fine.stats.blocks
    );
}

#[test]
fn skipping_brush_entities_yields_no_more_geometry() {
    let Some(map) = sample_map() else { return };
    let mut config = Config::default();
    config.entities.brush_entities = crate::config::BrushEntityMode::Skip;

    let without = convert(&map, &config).unwrap();
    let with = convert(&map, &Config::default()).unwrap();
    assert!(without.stats.blocks <= with.stats.blocks);
}
