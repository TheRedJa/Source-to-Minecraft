//! The conversion pipeline: BSP in, voxel grid out.

use crate::bsp::{Map, Solid};
use crate::config::{Config, FillMode};
use crate::geom::{Aabb, Vec3};
use crate::palette::{Decision, Resolver};
use crate::voxel::brush::BlockSolid;
use crate::voxel::grid::{AIR, BlockId, IVec3, Palette, VoxelGrid};
use crate::voxel::shell;
use crate::voxel::transform::Transform;
use rayon::prelude::*;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Mutex;

mod entities;
mod exact;
mod tiles;
mod voxelize;
#[cfg(test)]
mod tests;

pub(crate) use entities::to_block_solid;
use entities::*;
use exact::*;
use tiles::*;
use voxelize::*;

/// What a prop drawn as its own mesh leaves behind to stand on: solid, and
/// invisible, so the mesh in front of it is all you see.
const BARRIER: &str = "minecraft:barrier";

pub struct Conversion {
    pub grid: VoxelGrid,
    pub palette: Palette,
    pub transform: Transform,
    /// Canonical material and UV truth for every visible world face.
    pub surfaces: Vec<crate::voxel::surface::VisibleFaceRecord>,
    pub stats: Stats,
    /// Brush entities pulled out into their own grids, for classnames
    /// configured as `separate`.
    pub separate: Vec<SeparateEntity>,
    /// Generated blocks carrying the map's own textures, when
    /// `[materials] mode = "kubejs"`.
    pub pack: crate::output::kubejs::Pack,
    /// Props drawn as their real mesh, as the display entities that place
    /// them. Empty unless `[props] models` is on.
    pub props: Vec<crate::output::display::Placement>,
    /// Every voxelized brush, kept in continuous block space so a prop's
    /// overlap with the grid can be tested against the map's real geometry
    /// instead of only the grid's rounded approximation of it.
    pub solids: Vec<BlockSolid>,
    /// Cell to solids index for `solids`, so a prop touching one cell does
    /// not have to scan every brush in the map.
    pub solid_index: BTreeMap<IVec3, Vec<u32>>,
    /// Brushes too thin to voxelize honestly, kept as real geometry instead.
    pub brush_meshes: Vec<BrushMesh>,
    /// Cells those brushes would have filled, had they been voxelized. No
    /// block is written for them; they exist so the runtime can make the
    /// lighting behave as if the geometry were there.
    pub occluders: BTreeSet<IVec3>,
    /// The map's exact visible geometry, one piece per owning cell. Only
    /// filled when `output.exact_surfaces` asks for it.
    pub fragments: Vec<crate::voxel::fragments::Fragment>,
    /// What every cell is solid as, finer than a block. Only computed along
    /// with `fragments`.
    pub collision: Option<crate::voxel::collision::CellCollision>,
    /// The `separate` brush entities run through the same exact pipeline as
    /// the world, each on its own, for the mod to move. Only computed along
    /// with `fragments`.
    pub movers: Vec<MoverGeometry>,
}

/// One moving brush entity's exact geometry, in map-local cells at the pose
/// the map spawns it in.
///
/// Built exactly as the world is, but from the entity's own brushes alone, so
/// a fragment's owner is one of the mover's blocks and its collision hangs off
/// them: the door carries everything it is made of when it swings, and leaves
/// nothing of itself behind in the doorway.
#[derive(Debug, Clone)]
pub struct MoverGeometry {
    /// Index into the map's entity list.
    pub entity: usize,
    pub classname: String,
    pub targetname: Option<String>,
    /// The `*N` brush model the entity uses.
    pub model: usize,
    /// The mover's own blocks. Empty for a mover built only of brushes too thin
    /// to voxelize, such as a door panel; its fragments are then all unowned
    /// and its collision is all carriers.
    pub grid: VoxelGrid,
    /// Exact visible faces, cut per cell against `grid`.
    pub fragments: Vec<crate::voxel::fragments::Fragment>,
    /// What the mover's cells are solid as, against `grid`.
    pub collision: crate::voxel::collision::CellCollision,
    /// Whether its only collision is of brushes no side of which is drawn:
    /// a use volume such as INFRA's `infra_button`, or an invisible door.
    /// Such an entity becomes a mover only when props ride it.
    pub only_unseen: bool,
    /// Drawn faces no brush of the mover could be found behind.
    pub faces_unmatched: usize,
}

/// One brush drawn as its own geometry rather than as blocks.
///
/// Everything is already in map-local block space, with the triangles expressed
/// relative to `origin` so the pair can be handed to the same runtime path that
/// draws a prop: an origin to place it at, and a mesh in model space.
#[derive(Debug, Clone)]
pub struct BrushMesh {
    pub brush_index: usize,
    /// Where the mesh is placed, in map-local block coordinates.
    pub origin: Vec3,
    /// World-space bounds in block coordinates, for anchoring and visibility.
    pub bounds: Aabb,
    pub parts: Vec<BrushMeshPart>,
}

/// The triangles of one brush wearing one material.
#[derive(Debug, Clone)]
pub struct BrushMeshPart {
    /// Authored material path, matching [`crate::bsp::Material::name`].
    pub material: String,
    /// Model space, in blocks, relative to the mesh's origin.
    pub triangles: Vec<[Vec3; 3]>,
    /// Face normal per triangle. Brush sides are flat, so one normal covers a
    /// whole triangle rather than each of its corners.
    pub normals: Vec<Vec3>,
    /// Corner UVs, normalized to the texture's own `0..1`. Source projections
    /// tile, so these routinely fall outside that range; the runtime repeats
    /// the sprite to cover them, the same as it does for a prop.
    pub uvs: Vec<[[f64; 2]; 3]>,
    /// How many blocks one repeat of the sprite covers, per UV axis. A Source
    /// projection can compress one axis far harder than the other, so the atlas
    /// is told about each separately rather than being handed the worse of the
    /// two and under-resolving the other.
    pub blocks_per_repeat: [f64; 2],
}

/// One brush entity converted on its own.
///
/// Doors, platforms and trains move, so their geometry belongs where the world
/// is not: pasted into the world it would seal the doorway it is supposed to
/// open. Kept apart, it is a schematic you can place wherever the mechanism you
/// build for it needs it.
#[derive(Debug, Clone)]
pub struct SeparateEntity {
    /// Index into the map's entity list.
    pub entity: usize,
    pub classname: String,
    pub targetname: Option<String>,
    /// The `*N` brush model the entity uses.
    pub model: usize,
    pub grid: VoxelGrid,
}

impl SeparateEntity {
    /// A filename stem unique within one map.
    pub fn name(&self) -> String {
        let label = self
            .targetname
            .as_deref()
            .filter(|name| !name.is_empty())
            .unwrap_or("unnamed");
        let sanitized: String = label
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '_' || c == '-' {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        // The entity index keeps two doors with the same targetname apart.
        format!("{}_{}_{}", self.classname, sanitized, self.entity)
    }
}

#[derive(Debug, Default, Clone)]
pub struct Stats {
    pub solids_voxelized: usize,
    pub solids_skipped: usize,
    pub displacements_voxelized: usize,
    pub displacements_skipped: usize,
    /// Materials that resolved to a generated textured block.
    pub textures_resolved: usize,
    /// Tiles per axis the block budget allowed.
    pub tile_cap: u32,
    /// Static props voxelized into the world.
    pub props_placed: usize,
    /// Props skipped: model missing, too small, or matched by a skip rule.
    pub props_skipped: usize,
    /// Props drawn as their real mesh instead of being voxelized.
    pub props_modelled: usize,
    /// Distinct meshes generated for them.
    pub prop_models: usize,
    /// Triangles across those distinct meshes, counted once per mesh.
    pub prop_triangles: usize,
    /// Props moved vertically to stand on the floor rather than in it.
    pub props_settled: usize,
    /// Props drawn as a block with their rotation baked in, which the chunk
    /// mesh absorbs, rather than as an entity redrawn every frame.
    pub props_baked: usize,
    /// Blocks those props needed. More than `props_baked` when a prop reached
    /// too far to be drawn from one block and had to be split across several.
    pub prop_blocks: usize,
    /// Invisible barrier cubes placed to make the big ones solid.
    pub prop_barriers: usize,
    /// Cells given a generated block shaped like the mesh passing through them,
    /// instead of a barrier cube.
    pub prop_collision_blocks: usize,
    /// Distinct shapes those cells needed, which is what the pack registers.
    pub prop_collision_shapes: usize,
    /// Sixteenths of a block the shapes were rounded to. 1 unless there were
    /// more distinct shapes than `collision_max_shapes` allowed, in which case
    /// they were rounded outward more coarsely until they fit.
    pub prop_collision_step: i64,
    /// Voxels emitted as a slab or stair instead of a full cube.
    pub shapes_fitted: usize,
    /// Brush and terrain blocks before hollowing removed the interiors.
    ///
    /// The world only: props are added after hollowing, deliberately, so they
    /// cannot seal a wall's air side and have that wall taken for interior.
    pub blocks_before_hollow: usize,
    /// Canonical material/UV records for visible brush and terrain faces.
    pub visible_faces: usize,
    /// Drawn faces no converted brush could be found behind. They are kept,
    /// on their material alone.
    pub exact_faces_unmatched: usize,
    pub blocks: usize,
    /// Voxel count per block type, for sourcing materials.
    pub block_counts: BTreeMap<String, usize>,
}

/// Slop allowed when testing points against brush planes, in blocks. Looser
/// than the voxelizer's, because a corner shared by several sides has to be
/// recognised as lying on each of them for the face polygons to close.
const MESH_PLANE_EPSILON: f64 = 1e-5;

/// Slack on the mesh cut-off, in blocks. A brush authored exactly at the
/// cut-off must land on the mesh side of it whichever way the scaling rounds.
const MESH_THICKNESS_SLACK: f64 = 1e-6;

/// Split off the brushes that will be drawn as geometry instead of voxelized.
///
/// Returns the brushes still bound for the voxel grid, and the meshes built for
/// the rest. A thin brush with nothing visible on it is dropped outright, which
/// is the same answer voxelizing would have reached: every side vetoed means no
/// blocks.
/// Slack, in Source units, when deciding a drawn face lies on a brush side
/// and inside the brush. Face vertices are written from the same planes the
/// brush is, so real matches agree far closer than this.
const FACE_MATCH_SLACK: f64 = 0.5;
/// Edge of the buckets brushes are filed under to find a face's brush, in
/// Source units.
const FACE_MATCH_BUCKET: f64 = 256.0;

pub fn convert(map: &Map, config: &Config) -> anyhow::Result<Conversion> {
    // Bounds of what will be kept, which is not the same as worldspawn's own
    // box once the 3D skybox room is left out of it.
    let skybox = map.skybox().filter(|_| config.contents.skip_3d_skybox);
    let transform = Transform::new(config, map.converted_bounds(config.contents.skip_3d_skybox));

    // Reading the game's own content is best-effort: without it there are no
    // generated textures and no props, and the palette works exactly as it
    // did before, from rules and the compiler's average colour.
    let assets = crate::source::extract::extract(map, config);
    let materials = assets.materials(map);
    let texture_ids = assets.pack.ids();
    let resolver = Resolver::with_textures(config, &materials, &texture_ids)?;

    crate::timing::mark("convert: read assets");
    let entity_models = entity_models(map, config, &transform);

    // Gather every brush first so the voxelization itself parallelizes cleanly.
    let models = models_to_convert(map, config, &entity_models);
    let model_solids: Vec<Solid> = models.iter().flat_map(|&model| map.solids(model)).collect();
    // Every brush the world is made of, thin and skybox ones included: a drawn
    // face is traced back to its brush to learn whether the brush is converted.
    let exact_solids = config.output.exact_surfaces.then(|| model_solids.clone());
    let origins = model_origins(&entity_models);
    // Brush-entity brushes are stored relative to the entity's origin, so the
    // skybox room is tested against where they really are. Tested where they
    // are stored, an entity near Source's origin fell inside an INFRA skybox
    // room and silently lost its brushes.
    let solids: Vec<Solid> = model_solids
        .into_iter()
        .filter(|solid| {
            let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
            let placed = Aabb::new(solid.bounds.min + origin, solid.bounds.max + origin);
            !skybox.is_some_and(|room| room.contains(&placed))
        })
        .collect();

    // The palette is shared and rarely written to after the first few brushes.
    let palette = Mutex::new(Palette::new());
    let skipped = std::sync::atomic::AtomicUsize::new(0);

    // The world's invisible walls and player clips: in Source they block the
    // player, here they only collide (user, 2026-10-04: "whats closest to
    // source engine"). Brush entities are left to their own pipelines: a
    // trigger's brushes are invisible and solid too, and never collide. They
    // are cut to the box the rest of the map fills when collision is built:
    // clips far out in the void would otherwise widen sp_a1_wakeup by 161
    // blocks of nothing a player can reach.
    let unseen_solids: Vec<BlockSolid> = solids
        .iter()
        .filter(|solid| solid.model == 0 && collides_unseen(solid))
        .map(|solid| to_block_solid(solid, &transform, Vec3::ZERO))
        .collect();

    // Brushes thinner than the cut-off never reach the voxel grid: filling
    // every cell they touch is what turns a 4-unit plate into a 32-unit wall.
    let (solids, brush_meshes, occluders, thin_solids) =
        split_brush_meshes(map, config, &transform, &origins, solids);

    crate::timing::mark("convert: gather and split brushes");
    // Continuous brush geometry, retained so a prop's overlap with the grid
    // can be told apart from overlap that was already in the source map.
    let block_solids: Vec<BlockSolid> = solids
        .iter()
        .map(|solid| {
            let origin = origins.get(&solid.model).copied().unwrap_or(Vec3::ZERO);
            to_block_solid(solid, &transform, origin)
        })
        .collect();
    let mut solid_index: BTreeMap<IVec3, Vec<u32>> = BTreeMap::new();
    for (index, solid) in block_solids.iter().enumerate() {
        if solid.bounds.is_empty() {
            continue;
        }
        let min = [
            solid.bounds.min.x.floor() as i32,
            solid.bounds.min.y.floor() as i32,
            solid.bounds.min.z.floor() as i32,
        ];
        let max = [
            (solid.bounds.max.x - 1.0e-6).floor() as i32,
            (solid.bounds.max.y - 1.0e-6).floor() as i32,
            (solid.bounds.max.z - 1.0e-6).floor() as i32,
        ];
        for x in min[0]..=max[0] {
            for y in min[1]..=max[1] {
                for z in min[2]..=max[2] {
                    solid_index.entry([x, y, z]).or_default().push(index as u32);
                }
            }
        }
    }

    crate::timing::mark("convert: brush index");
    let tiles = tile_sets(map, &materials, &resolver, &assets.pack, &palette);
    let (grid, mut masks, mut face_candidates) = voxelize_solids(
        &solids, map, config, &resolver, &transform, &origins, &tiles, &palette, &skipped,
    );

    crate::timing::mark("convert: voxelize brushes");
    // Brush entities configured as `separate` get their own grid each, so a
    // door is a schematic you can place where your mechanism needs it rather
    // than a slab sealing the doorway it should open.
    let separate: Vec<SeparateEntity> = entity_models
        .iter()
        .filter(|e| e.mode == crate::config::BrushEntityMode::Separate)
        .filter_map(|entity| {
            let solids = map.solids(entity.model);
            if solids.is_empty() {
                return None;
            }
            // Counted apart: these brushes are not in `solids`, and adding
            // their skips to its count underflowed the voxelized total.
            let separate_skipped = std::sync::atomic::AtomicUsize::new(0);
            let (grid, _, _) = voxelize_solids(
                &solids,
                map,
                config,
                &resolver,
                &transform,
                &origins,
                &tiles,
                &palette,
                &separate_skipped,
            );
            (grid.count() > 0).then(|| SeparateEntity {
                entity: entity.entity,
                classname: entity.classname.clone(),
                targetname: entity.targetname.clone(),
                model: entity.model,
                grid,
            })
        })
        .collect();

    // Movers: the same separate entities, through the exact pipeline the world
    // takes below, each against its own grid so it owns its own faces.
    let movers: Vec<MoverGeometry> = if config.output.exact_surfaces {
        entity_models
            .iter()
            .filter(|e| e.mode == crate::config::BrushEntityMode::Separate)
            .map(|entity| {
                mover_geometry(
                    entity, map, config, &resolver, &transform, &origins, &tiles, &palette, skybox,
                )
            })
            .collect()
    } else {
        Vec::new()
    };

    // Displacements: Source's terrain, and the reason a converted outdoor map
    // used to be a floating shell of buildings over nothing.
    let surfaces = if config.displacement.enabled {
        map.displacement_surfaces()
    } else {
        Vec::new()
    };
    let mut grid = grid;
    let (terrain, displacements_skipped) =
        voxelize_displacements(&surfaces, config, &resolver, &transform, &tiles, &palette);
    if !surfaces.is_empty() {
        let (terrain, terrain_masks, terrain_faces) = terrain;
        grid.merge(terrain);
        masks.merge(terrain_masks);
        face_candidates.merge(terrain_faces);
    }

    crate::timing::mark("convert: separate entities and displacements");
    // How far each modelled prop has to move to meet the floor. Measured here,
    // against the world as brushes and terrain left it and before any prop has
    // been added to it, so props cannot end up standing on each other.
    let settle: Vec<f64> = assets
        .placements
        .iter()
        .map(|placement| {
            if !config.props.settle {
                return 0.0;
            }
            crate::voxel::settle::offset(
                &grid,
                transform.transform_bounds(placement.bounds),
                config.props.settle_max,
            )
        })
        .collect();
    let settled = settle.iter().filter(|shift| **shift != 0.0).count();

    // What the props are solid as, kept apart from the world's own blocks
    // until the very end. Merging it in now would let hollowing see a wall
    // whose air side is sealed by a collision block as interior and carve it
    // away.
    //
    // Shaped collision measures the mesh per cell instead of filling the cell:
    // where the barrier shell put a metre cube, this puts a box the size of
    // what actually passes through. Which cells is the same question either
    // way, and the same exact test answers it.
    let shaped_collision: Vec<(usize, std::collections::HashMap<IVec3, Aabb>)> =
        if shaped_collision_wanted(config) {
            assets
                .placements
                .par_iter()
                .enumerate()
                .zip(&settle)
                .filter(|((_, placement), _)| !placement.collision.is_empty())
                .map(|((index, placement), shift)| {
                    let lift = Vec3::new(0.0, 0.0, shift * transform.units_per_block());
                    let triangles: Vec<[Vec3; 3]> = placement
                        .collision
                        .iter()
                        .map(|tri| tri.map(|v| transform.to_block_space(v + lift)))
                        .collect();
                    (index, crate::output::collision::cells(triangles.iter()))
                })
                .collect()
        } else {
            Vec::new()
        };

    let collision = assets
        .placements
        .par_iter()
        .zip(&settle)
        .filter(|_| barriers_wanted(config))
        .filter(|(placement, _)| !placement.collision.is_empty())
        .fold(VoxelGrid::new, |mut grid, (placement, shift)| {
            let barrier = palette.lock().unwrap().intern(BARRIER);
            // The mesh moved, so what you can stand on moves with it.
            let lift = Vec3::new(0.0, 0.0, shift * transform.units_per_block());
            let surface = crate::source::extract::PropSurface {
                triangles: placement
                    .collision
                    .iter()
                    .map(|tri| tri.map(|v| v + lift))
                    .collect(),
                uvs: Vec::new(),
                material: 0,
            };
            grid.merge(voxelize_prop(
                &surface,
                &transform,
                config.props.solidify,
                barrier,
                None,
            ));
            grid
        })
        .reduce(VoxelGrid::new, |mut a, b| {
            a.merge(b);
            a
        });

    // Static props: everything a map puts *in* its rooms. Fences, railings,
    // catwalks, crates, signs and lamps are all models, none of which is in
    // any brush lump, which is why a map converted from brushes alone is an
    // accurate but empty shell.
    let voxelized = if assets.props.is_empty() {
        VoxelGrid::new()
    } else {
        assets
            .props
            .par_iter()
            .fold(VoxelGrid::new, |mut grid, surface| {
                let block = resolver
                    .block_for_material(surface.material)
                    .map(|name| palette.lock().unwrap().intern(name));
                if let Some(block) = block {
                    grid.merge(voxelize_prop(
                        surface,
                        &transform,
                        config.props.solidify,
                        block,
                        tiles.get(surface.material).and_then(Option::as_ref),
                    ));
                }
                grid
            })
            .reduce(VoxelGrid::new, |mut a, b| {
                a.merge(b);
                a
            })
    };

    // Hollowing removes what nothing can see, and it decides that by asking
    // whether a block touches air. Props are put in *after* it, not before,
    // because a prop standing against a wall seals that wall's air side and
    // hollowing then takes the wall for interior and carves it out — which is
    // how a crate in a corridor turns into a hole through the corridor.
    // Props have no interior of their own to lose: they are already surfaces.
    let blocks_before_hollow = grid.count();
    let mut shapes_fitted = 0;
    // Kept when collision is wanted: a cell hollowing emptied is sealed inside
    // the map's mass and needs no collision of its own.
    let mut before_hollow = None;
    let mut grid = match config.fill.mode {
        FillMode::Solid => grid,
        FillMode::Hollow => {
            let hollowed = shell::hollow(
                &grid,
                config.fill.shell_thickness,
                config.fill.shell_neighborhood,
            );
            if config.output.exact_surfaces {
                before_hollow = Some(grid);
            }
            hollowed
        }
    };
    grid.merge(voxelized);
    let grid = grid;
    crate::timing::mark("convert: settle, collision shells, props, hollowing");

    // Shapes are fitted last, after hollowing: the mask grid outlives the
    // brushes precisely so this can happen here, on the voxels that survived.
    let mut palette = palette.into_inner().unwrap();
    let grid = if config.shapes.enabled && !masks.is_empty() {
        let (fitted, count) = fit_shapes(&grid, &masks, &mut palette, &assets.pack);
        shapes_fitted = count;
        fitted
    } else {
        grid
    };
    let visible_surfaces = face_candidates.visible(&grid, &masks, config.shapes.enabled);

    crate::timing::mark("convert: shapes and visible faces");
    // Props drawn as blocks rather than as entities. A prop's block may only
    // take a cell that is air, since taking one of the map's own would punch a
    // hole in whatever the prop stands against, and taking another prop's
    // would delete that prop.
    //
    // Before the barriers, not after, because a big prop's barriers fill the
    // shell its own geometry occupies — which is exactly where the blocks
    // drawing that geometry want to sit. Letting the barriers go first left
    // large props with nowhere to put their pieces and sent them back to being
    // entities. The barrier pass gives way instead: it skips whatever is
    // already there, so a prop block costs one voxel of collision out of a
    // shell that runs to thousands.
    let mut grid = grid;
    let mut assets = assets;
    // Held apart from `assets` for the loop, which reads the placements it is
    // registering meshes for.
    let mut pack = std::mem::take(&mut assets.pack);
    let baked = if config.props.bake {
        bake_props(config, &transform, &assets, &settle, &grid, &mut pack)
    } else {
        Vec::new()
    };
    assets.pack = pack;

    for (_, cell, block) in &baked {
        let id = palette.intern(block);
        grid.set(*cell, id);
    }

    let is_baked: std::collections::HashSet<usize> =
        baked.iter().map(|(index, _, _)| *index).collect();

    // Collision last, and only where there is nothing already. It is
    // invisible, so overwriting a wall with it opens a hole you can see
    // straight through; and coming after hollowing means it cannot make the
    // map's own blocks look like interior worth removing.
    let mut barriers = 0;
    for (pos, block) in collision.iter() {
        if grid.get(pos) == AIR {
            grid.set(pos, block);
            barriers += 1;
        }
    }

    // Shaped collision, for the props that were baked into blocks. A prop that
    // stayed a display entity keeps the barrier shell: it has no block of its
    // own, and cubes are what it always had.
    let mut collision_blocks = 0;
    let mut collision_shapes = 0;
    let mut collision_step = 1;
    if !shaped_collision.is_empty() {
        let mut wanted: std::collections::HashMap<IVec3, Aabb> = std::collections::HashMap::new();
        let mut fallback: std::collections::HashSet<IVec3> = std::collections::HashSet::new();
        for (index, cells) in &shaped_collision {
            for (cell, bounds) in cells {
                if is_baked.contains(index) {
                    // Two props sharing a cell share its block, so the box has
                    // to cover both. Whichever reaches further decides.
                    let entry = wanted.entry(*cell).or_insert_with(Aabb::empty);
                    entry.extend(bounds.min);
                    entry.extend(bounds.max);
                } else {
                    fallback.insert(*cell);
                }
            }
        }

        let (shapes, step) =
            crate::output::collision::quantize(&wanted, config.props.collision_max_shapes);
        collision_step = step;
        let mut distinct: std::collections::HashSet<crate::output::collision::Shape> =
            std::collections::HashSet::new();
        for (cell, shape) in shapes {
            if grid.get(cell) != AIR {
                continue;
            }
            let id = assets.pack.insert_collision(shape);
            let block = palette.intern(&id);
            grid.set(cell, block);
            distinct.insert(shape);
            collision_blocks += 1;
        }
        collision_shapes = distinct.len();

        let barrier = palette.intern(BARRIER);
        for cell in fallback {
            if grid.get(cell) == AIR {
                grid.set(cell, barrier);
                barriers += 1;
            }
        }
    }
    let grid = grid;
    // Props, not blocks: one that had to be split is still one prop.
    let props_baked = is_baked.len();
    let prop_blocks = baked.len();

    let palette = palette;
    let mut block_counts: BTreeMap<String, usize> = BTreeMap::new();
    for (_, id) in grid.iter() {
        *block_counts
            .entry(palette.name(id).to_string())
            .or_default() += 1;
    }

    let skipped = skipped.into_inner();

    // The props that could not be baked into a block: too large for it, or
    // with no free cell to stand the block in. These keep the old route — one
    // display entity each, carrying the map's own rotation — which costs frame
    // rate but is never wrong, and there are few of them.
    let props: Vec<crate::output::display::Placement> = assets
        .placements
        .iter()
        .zip(&settle)
        .enumerate()
        .filter(|(index, _)| !is_baked.contains(index))
        .map(|(_, (placement, shift))| {
            let origin = transform.to_block_space(placement.prop.origin);
            crate::output::display::Placement {
                block: placement.block.clone(),
                pos: [origin.x, origin.y + shift, origin.z],
                rotation: crate::output::display::rotation(&placement.prop, &transform),
                scale: placement.prop.scale,
                width: placement.width,
                height: placement.height,
                view_range: config.props.view_range,
                full_bright: config.props.full_bright,
                tag: crate::output::display::tag_for(&map.name),
            }
        })
        .collect();

    let mut exact_faces_unmatched = 0;
    let fragments = match &exact_solids {
        Some(all_solids) => {
            let (polygons, unmatched) = exact_polygons(
                map, config, &resolver, &transform, &origins, &models, all_solids, skybox,
                &surfaces,
            );
            exact_faces_unmatched = unmatched;
            polygons
                .par_iter()
                .flat_map_iter(|polygon| crate::voxel::fragments::fragments(polygon, &grid))
                .collect()
        }
        None => Vec::new(),
    };
    crate::timing::mark("convert: bake, barriers, exact surface fragments");
    let collision = config.output.exact_surfaces.then(|| {
        let converted: Vec<&BlockSolid> = solids
            .iter()
            .zip(&block_solids)
            .filter(|(solid, _)| makes_blocks(solid, map, config, &resolver))
            .map(|(_, block)| block)
            .collect();
        let terrain = surfaces
            .iter()
            .filter(|surface| {
                surface
                    .material
                    .and_then(|m| resolver.block_for_material(m))
                    .is_some()
            })
            .map(|surface| crate::voxel::collision::Terrain {
                triangles: surface
                    .triangles
                    .iter()
                    .map(|tri| {
                        [
                            transform.to_block_space(tri.a),
                            transform.to_block_space(tri.b),
                            transform.to_block_space(tri.c),
                        ]
                    })
                    .collect(),
                inward: transform.transform_direction(-surface.normal),
            })
            .collect();
        let interior = |cell: IVec3| {
            before_hollow
                .as_ref()
                .is_some_and(|before| before.is_solid(cell) && !grid.is_solid(cell))
        };
        let unseen_clipped: Vec<BlockSolid> = match grid.bounds() {
            Some((min, max)) => unseen_solids
                .iter()
                .filter_map(|solid| clipped_to_cells(solid, min, max))
                .collect(),
            None => Vec::new(),
        };
        crate::voxel::collision::compute(&crate::voxel::collision::Sources {
            grid: &grid,
            interior: &interior,
            solids: converted,
            thin: thin_solids
                .iter()
                .filter(|(flags, _)| resolver.decide(*flags) != Decision::Skip)
                .map(|(_, block)| block)
                .chain(&unseen_clipped)
                .collect(),
            terrain,
        })
    });
    crate::timing::mark("convert: collision table");
    drop(before_hollow);
    Ok(Conversion {
        stats: Stats {
            solids_voxelized: solids.len() - skipped,
            solids_skipped: skipped,
            displacements_voxelized: surfaces.len() - displacements_skipped,
            displacements_skipped,
            textures_resolved: assets.stats.resolved,
            tile_cap: assets.stats.tile_cap,
            props_placed: assets.stats.props_placed,
            props_skipped: assets.stats.props_skipped,
            props_modelled: assets.stats.props_modelled,
            prop_models: assets.stats.prop_models,
            prop_triangles: assets.prop_meshes.iter().map(|mesh| mesh.triangles).sum(),
            props_settled: settled,
            props_baked,
            prop_blocks,
            prop_barriers: barriers,
            prop_collision_blocks: collision_blocks,
            prop_collision_shapes: collision_shapes,
            prop_collision_step: collision_step,
            shapes_fitted,
            blocks_before_hollow,
            visible_faces: visible_surfaces.len(),
            exact_faces_unmatched,
            blocks: grid.count(),
            block_counts,
        },
        grid,
        palette,
        transform,
        surfaces: visible_surfaces,
        separate,
        props,
        pack: assets.pack,
        solids: block_solids,
        solid_index,
        brush_meshes,
        occluders,
        fragments,
        collision,
        movers,
    })
}

/// Bounds of the map in block space, for reporting.
pub fn block_bounds(map: &Map, transform: &Transform) -> Aabb {
    let bounds = map.converted_bounds(true);
    if bounds.is_empty() {
        return Aabb::new(Vec3::ZERO, Vec3::ZERO);
    }
    transform.transform_bounds(bounds)
}

/// The lightmap of BSP face `face_index` and the affine map from a block-space
/// position to its luxels: the texinfo's lightmap vectors, as vrad projects
/// the face's points, less the face's lightmap mins, plus half a luxel so the
/// first luxel's centre is 0.5. Positions are of the brush where it was
/// compiled, so a brush entity's `origin` is taken off as for its texture.
fn face_light(
    map: &crate::bsp::Map,
    face_index: usize,
    info: &vbsp::TextureInfo,
    transform: &Transform,
    origin: Vec3,
) -> Option<crate::voxel::surface::FaceLight> {
    let light = map.light.face(face_index)?;
    map.light.raw_lightmap(face_index)?;
    let mut luxel = crate::bsp::texcoord::TexCoord {
        u: info.light_map_scale.map(f64::from),
        v: info.light_map_transform.map(f64::from),
    }
    .in_block_space(transform, origin);
    luxel.u[3] += 0.5 - f64::from(light.mins[0]);
    luxel.v[3] += 0.5 - f64::from(light.mins[1]);
    Some(crate::voxel::surface::FaceLight {
        face: face_index,
        luxel,
    })
}
