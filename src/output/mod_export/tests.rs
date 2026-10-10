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
        bump: None,
        envmap: None,
        blend: false,
        blend_modulate: None,
        detail: None,
        selfillum: None,
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
            blend: None,
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
        look: crate::output::look::LookTable {
            hdr: false,
            post: Default::default(),
            lookups: Default::default(),
        },
        cubemaps: Vec::new(),
        details: BTreeMap::new(),
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
            blend: None,
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
        include_str!("../../../tests/fixtures/mod_export_fingerprint.txt").trim()
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
        bump: None,
        envmap: None,
        blend: false,
        blend_modulate: None,
        detail: None,
        selfillum: None,
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
    assert!(meta.contains(
        r#""logic_props":"maps/map/logic_props.json","look":"maps/map/look.s2look","diagnostics""#
    ));
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
        meta.contains(
            r#""movers":"maps/map/movers.json","look":"maps/map/look.s2look","diagnostics""#
        ),
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
