//! What each cell of the map is solid as, finer than a whole block.
//!
//! The exact surfaces draw the map where Source put it, but a block collides as
//! a whole cube, so in a hallway the player bumped into walls up to a block
//! before the drawn wall and stood on floors that sat part-way up their block.
//! This works out the real solid volume inside every cell, in sixteenths of a
//! block, from the same brushes and terrain the surfaces come from.
//!
//! Volume is kept per cell, the way the surfaces are, and a piece of it
//! belongs to a block the same way a fragment does:
//!
//! - A map block collides as the solid volume inside its own cell. Most are
//!   buried in a wall and full, and need no entry at all. One the voxelizer
//!   rounded out into a room collides as the little of it that is really
//!   there, or as nothing.
//! - Volume in a cell that holds no block — a floor 8 units up into the air
//!   cell above its block — hangs off the neighbouring block it rests against,
//!   as a shape reaching one cell out. Breaking that block takes the floor's
//!   collision with it, just as it takes the drawn floor.
//! - Volume with nothing to rest against, and every thin brush, which is drawn
//!   whatever blocks stand around it, is carried by a block of its own: a
//!   carrier, placed in that cell and holding only the shape.
//!
//! Rounding is outward, never to nearest: a sixteenth of a block of slack is
//! invisible where a floor you fall through is not. Separate pieces in one
//! cell stay separate boxes, so a gap between two bars stays open.

use crate::geom::{Plane, Vec3};
use crate::voxel::brush::BlockSolid;
use crate::voxel::grid::{IVec3, VoxelGrid};
use rayon::prelude::*;
use std::collections::{BTreeMap, BTreeSet, HashMap};

/// Subdivisions of a block per axis.
pub const STEPS: i32 = 16;
/// Positions within this of a boundary are on it. Map geometry sits on 1/32
/// of a block at the coarsest, so this only absorbs arithmetic noise.
const EPSILON: f64 = 1.0e-6;

/// One box of a collision shape, `[x1, y1, z1, x2, y2, z2]` in sixteenths of
/// the cell that carries it. A box hanging into a neighbouring cell reaches
/// below 0 or past 16, never further than one cell.
pub type Box16 = [i8; 6];

/// The solid sixteenths of one cell: bit `x + 16 * (z + 16 * y)`.
#[derive(Clone, PartialEq, Eq)]
pub struct SubCells([u64; 64]);

impl SubCells {
    pub fn empty() -> SubCells {
        SubCells([0; 64])
    }

    pub fn full() -> SubCells {
        SubCells([u64::MAX; 64])
    }

    fn index(x: i32, y: i32, z: i32) -> usize {
        (x + STEPS * (z + STEPS * y)) as usize
    }

    pub fn get(&self, x: i32, y: i32, z: i32) -> bool {
        let i = Self::index(x, y, z);
        self.0[i >> 6] & (1 << (i & 63)) != 0
    }

    pub fn set(&mut self, x: i32, y: i32, z: i32) {
        let i = Self::index(x, y, z);
        self.0[i >> 6] |= 1 << (i & 63);
    }

    /// The sixteen X bits of one row.
    fn row(&self, y: i32, z: i32) -> u16 {
        let i = Self::index(0, y, z);
        (self.0[i >> 6] >> (i & 63)) as u16
    }

    fn clear_row(&mut self, y: i32, z: i32, mask: u16) {
        let i = Self::index(0, y, z);
        self.0[i >> 6] &= !((mask as u64) << (i & 63));
    }

    /// Set a box, in sixteenths, half-open.
    fn fill(&mut self, min: [i32; 3], max: [i32; 3]) {
        if min[0] >= max[0] {
            return;
        }
        let mask = (((1u32 << (max[0] - min[0])) - 1) << min[0]) as u64;
        for y in min[1]..max[1] {
            for z in min[2]..max[2] {
                let i = Self::index(0, y, z);
                self.0[i >> 6] |= mask << (i & 63);
            }
        }
    }

    pub fn is_empty(&self) -> bool {
        self.0.iter().all(|word| *word == 0)
    }

    pub fn is_full(&self) -> bool {
        self.0.iter().all(|word| *word == u64::MAX)
    }

    pub fn union(&mut self, other: &SubCells) {
        for (a, b) in self.0.iter_mut().zip(other.0.iter()) {
            *a |= b;
        }
    }

    pub fn count(&self) -> u32 {
        self.0.iter().map(|word| word.count_ones()).sum()
    }

    /// How many sixteenths of the face toward `direction` are solid in both
    /// `self` and, on its facing side, `other` — the cell one step that way.
    fn contact(&self, other: &SubCells, direction: IVec3) -> u32 {
        let axis = (0..3).find(|&a| direction[a] != 0).unwrap_or(0);
        let (mine, theirs) = if direction[axis] > 0 {
            (STEPS - 1, 0)
        } else {
            (0, STEPS - 1)
        };
        let mut touching = 0;
        for a in 0..STEPS {
            for b in 0..STEPS {
                let at = |layer: i32| match axis {
                    0 => [layer, a, b],
                    1 => [a, layer, b],
                    _ => [a, b, layer],
                };
                let [x, y, z] = at(mine);
                let [ox, oy, oz] = at(theirs);
                if self.get(x, y, z) && other.get(ox, oy, oz) {
                    touching += 1;
                }
            }
        }
        touching
    }

    /// The solid sixteenths as few boxes as a greedy sweep finds: each box
    /// grows along X, then Z, then Y while every sixteenth it would take is
    /// still solid and unclaimed. Never merges across empty space.
    pub fn boxes(&self) -> Vec<Box16> {
        let mut left = self.clone();
        let mut out = Vec::new();
        for y in 0..STEPS {
            for z in 0..STEPS {
                loop {
                    let row = left.row(y, z);
                    if row == 0 {
                        break;
                    }
                    let x0 = row.trailing_zeros() as i32;
                    let run = (row >> x0).trailing_ones() as i32;
                    let mask = ((((1u32 << run) - 1) << x0) & 0xFFFF) as u16;
                    let mut z1 = z + 1;
                    while z1 < STEPS && left.row(y, z1) & mask == mask {
                        z1 += 1;
                    }
                    let mut y1 = y + 1;
                    while y1 < STEPS && (z..z1).all(|k| left.row(y1, k) & mask == mask) {
                        y1 += 1;
                    }
                    for yy in y..y1 {
                        for zz in z..z1 {
                            left.clear_row(yy, zz, mask);
                        }
                    }
                    out.push([
                        x0 as i8,
                        y as i8,
                        z as i8,
                        (x0 + run) as i8,
                        y1 as i8,
                        z1 as i8,
                    ]);
                }
            }
        }
        out
    }
}

impl std::fmt::Debug for SubCells {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "SubCells({} of 4096)", self.count())
    }
}

enum Overlap {
    Outside,
    Inside,
    Partial,
}

/// Where a box lies against a convex solid. `Partial` is only ever a maybe:
/// a box beside a bevelled edge can pass every plane test and still miss the
/// solid, and is then counted solid, which is the outward rounding wanted.
fn classify(solid: &BlockSolid, min: Vec3, max: Vec3) -> Overlap {
    if solid.bounds.max.x <= min.x + EPSILON
        || solid.bounds.max.y <= min.y + EPSILON
        || solid.bounds.max.z <= min.z + EPSILON
        || solid.bounds.min.x >= max.x - EPSILON
        || solid.bounds.min.y >= max.y - EPSILON
        || solid.bounds.min.z >= max.z - EPSILON
    {
        return Overlap::Outside;
    }
    let mut inside = true;
    for plane in &solid.planes {
        let (near, far) = extremes(plane, min, max);
        // Wholly on or in front of one plane: no volume shared. On it counts
        // as outside, or a floor on a sixteenth line would claim the
        // sixteenth above it too.
        if near >= -EPSILON {
            return Overlap::Outside;
        }
        if far > EPSILON {
            inside = false;
        }
    }
    if inside {
        Overlap::Inside
    } else {
        Overlap::Partial
    }
}

/// The smallest and largest signed distance of a box's corners to a plane.
fn extremes(plane: &Plane, min: Vec3, max: Vec3) -> (f64, f64) {
    let n = plane.normal;
    let mut near = -plane.dist;
    let mut far = -plane.dist;
    for axis in 0..3 {
        let (lo, hi) = (min.axis(axis), max.axis(axis));
        let c = n.axis(axis);
        if c >= 0.0 {
            near += c * lo;
            far += c * hi;
        } else {
            near += c * hi;
            far += c * lo;
        }
    }
    (near, far)
}

/// The sixteenths of `cell` inside any of `solids`, found by splitting the
/// cell in eight until each part is wholly in, wholly out, or a sixteenth.
pub fn solid_volume(cell: IVec3, solids: &[&BlockSolid]) -> SubCells {
    let mut out = SubCells::empty();
    octree(cell, [0, 0, 0], STEPS, solids, &mut out);
    out
}

fn octree(cell: IVec3, min: [i32; 3], size: i32, solids: &[&BlockSolid], out: &mut SubCells) {
    let corner = |offset: i32| {
        Vec3::new(
            f64::from(cell[0]) + f64::from(min[0] + offset) / f64::from(STEPS),
            f64::from(cell[1]) + f64::from(min[1] + offset) / f64::from(STEPS),
            f64::from(cell[2]) + f64::from(min[2] + offset) / f64::from(STEPS),
        )
    };
    let (lo, hi) = (corner(0), corner(size));
    let mut partial: Vec<&BlockSolid> = Vec::new();
    for solid in solids {
        match classify(solid, lo, hi) {
            Overlap::Inside => {
                out.fill(min, [min[0] + size, min[1] + size, min[2] + size]);
                return;
            }
            Overlap::Partial => partial.push(solid),
            Overlap::Outside => {}
        }
    }
    if partial.is_empty() {
        return;
    }
    if size == 1 {
        out.set(min[0], min[1], min[2]);
        return;
    }
    let half = size / 2;
    for dy in [0, half] {
        for dz in [0, half] {
            for dx in [0, half] {
                octree(
                    cell,
                    [min[0] + dx, min[1] + dy, min[2] + dz],
                    half,
                    &partial,
                    out,
                );
            }
        }
    }
}

/// One displacement: a surface with solid material behind it.
pub struct Terrain {
    /// Triangles in block space.
    pub triangles: Vec<[Vec3; 3]>,
    /// Direction into the solid side, in block space.
    pub inward: Vec3,
}

/// The sixteenths a displacement makes solid, per cell.
///
/// Terrain is a height field along its inward axis. Every sixteenth-wide
/// column across the surface is solid where the surface passes through it,
/// and one sixteenth further in. Source terrain has no thickness of its own:
/// the blocks the grid backs it with are only there to be seen from below,
/// and following them put a block and a half of collision under an upstairs
/// floor into the ceiling of the room beneath it.
pub fn terrain_volume(terrain: &Terrain) -> HashMap<IVec3, SubCells> {
    let axis = terrain.inward.major_axis();
    let down = terrain.inward.axis(axis) < 0.0;
    let (u, v) = match axis {
        0 => (1, 2),
        1 => (0, 2),
        _ => (0, 1),
    };
    let scale = f64::from(STEPS);
    // Surface extent along the axis, per column, in blocks.
    let mut columns: HashMap<(i64, i64), (f64, f64)> = HashMap::new();
    for triangle in &terrain.triangles {
        let (mut umin, mut umax, mut vmin, mut vmax) = (f64::MAX, f64::MIN, f64::MAX, f64::MIN);
        for p in triangle {
            umin = umin.min(p.axis(u));
            umax = umax.max(p.axis(u));
            vmin = vmin.min(p.axis(v));
            vmax = vmax.max(p.axis(v));
        }
        if !(umin.is_finite() && umax.is_finite() && vmin.is_finite() && vmax.is_finite()) {
            continue;
        }
        let (iu0, iu1) = (
            (umin * scale + EPSILON).floor() as i64,
            (umax * scale - EPSILON).ceil() as i64,
        );
        let (iv0, iv1) = (
            (vmin * scale + EPSILON).floor() as i64,
            (vmax * scale - EPSILON).ceil() as i64,
        );
        for iu in iu0..iu1.max(iu0 + 1) {
            for iv in iv0..iv1.max(iv0 + 1) {
                let rect = [
                    iu as f64 / scale,
                    (iu + 1) as f64 / scale,
                    iv as f64 / scale,
                    (iv + 1) as f64 / scale,
                ];
                if let Some((lo, hi)) = column_extent(triangle, u, v, axis, rect) {
                    let entry = columns.entry((iu, iv)).or_insert((lo, hi));
                    entry.0 = entry.0.min(lo);
                    entry.1 = entry.1.max(hi);
                }
            }
        }
    }

    let mut out: HashMap<IVec3, SubCells> = HashMap::new();
    let skin = 1.0 / scale;
    for ((iu, iv), (lo, hi)) in columns {
        let (from, to) = if down {
            (lo - skin, hi)
        } else {
            (lo, hi + skin)
        };
        let first = (from * scale + EPSILON).floor() as i64;
        let last = (to * scale - EPSILON).ceil() as i64;
        let mut at = first;
        while at < last {
            let block = at.div_euclid(i64::from(STEPS));
            let start = at.rem_euclid(i64::from(STEPS));
            let end = (last - block * i64::from(STEPS)).min(i64::from(STEPS));
            let mut cell = [0i32; 3];
            cell[axis] = block as i32;
            cell[u] = iu.div_euclid(i64::from(STEPS)) as i32;
            cell[v] = iv.div_euclid(i64::from(STEPS)) as i32;
            let mut min = [0i32; 3];
            let mut max = [0i32; 3];
            min[axis] = start as i32;
            max[axis] = end as i32;
            min[u] = iu.rem_euclid(i64::from(STEPS)) as i32;
            max[u] = min[u] + 1;
            min[v] = iv.rem_euclid(i64::from(STEPS)) as i32;
            max[v] = min[v] + 1;
            out.entry(cell)
                .or_insert_with(SubCells::empty)
                .fill(min, max);
            at = block * i64::from(STEPS) + end;
        }
    }
    out
}

/// The range along `axis` of the part of a triangle over one column.
fn column_extent(
    triangle: &[Vec3; 3],
    u: usize,
    v: usize,
    axis: usize,
    rect: [f64; 4],
) -> Option<(f64, f64)> {
    let mut poly: Vec<[f64; 3]> = triangle
        .iter()
        .map(|p| [p.axis(u), p.axis(v), p.axis(axis)])
        .collect();
    // Keep the side of each column wall facing into the column.
    let walls: [(usize, f64, f64); 4] = [
        (0, rect[0], 1.0),
        (0, rect[1], -1.0),
        (1, rect[2], 1.0),
        (1, rect[3], -1.0),
    ];
    for (component, at, sign) in walls {
        let mut next = Vec::with_capacity(poly.len() + 1);
        for i in 0..poly.len() {
            let a = poly[i];
            let b = poly[(i + 1) % poly.len()];
            let da = (a[component] - at) * sign;
            let db = (b[component] - at) * sign;
            if da >= -EPSILON {
                next.push(a);
            }
            if (da >= -EPSILON) != (db >= -EPSILON) {
                let t = da / (da - db);
                next.push([
                    a[0] + (b[0] - a[0]) * t,
                    a[1] + (b[1] - a[1]) * t,
                    a[2] + (b[2] - a[2]) * t,
                ]);
            }
        }
        poly = next;
        if poly.is_empty() {
            return None;
        }
    }
    let lo = poly.iter().map(|p| p[2]).fold(f64::MAX, f64::min);
    let hi = poly.iter().map(|p| p[2]).fold(f64::MIN, f64::max);
    Some((lo, hi))
}

/// The six face directions, floor first: a floor sliver resting on the block
/// below is the most common piece there is, and ties go to it.
const DIRECTIONS: [IVec3; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [-1, 0, 0],
    [1, 0, 0],
    [0, 0, -1],
    [0, 0, 1],
];

/// Everything the collision of a map is built from.
pub struct Sources<'a> {
    /// The map's blocks.
    pub grid: &'a VoxelGrid,
    /// Cells hollowing emptied: sealed inside the map's mass, where nothing
    /// can ever collide, so they get no carriers.
    pub interior: &'a (dyn Fn(IVec3) -> bool + Sync),
    /// Voxelized brushes that became blocks.
    pub solids: Vec<&'a BlockSolid>,
    /// Brushes too thin to voxelize, drawn whatever blocks surround them.
    pub thin: Vec<&'a BlockSolid>,
    pub terrain: Vec<Terrain>,
}

/// The collision of a whole map.
#[derive(Debug, Default, Clone)]
pub struct CellCollision {
    /// The shape of every map block that is not a full cube, and of every
    /// carrier. Boxes in sixteenths of the cell; empty for a block with no
    /// volume in it at all.
    pub shapes: BTreeMap<IVec3, Vec<Box16>>,
    /// Cells that need a carrier block for their shape.
    pub carriers: BTreeSet<IVec3>,
    /// Pieces that hang off a neighbouring block.
    pub attached: usize,
}

/// One piece of solid volume in a cell with no block.
struct Piece {
    bits: SubCells,
    /// Whether a neighbouring block may carry it. Thin brushes are drawn
    /// whatever surrounds them, so their collision must not go with a block.
    attachable: bool,
}

pub fn compute(sources: &Sources) -> CellCollision {
    // Which solids touch each cell.
    let mut touching: HashMap<IVec3, (Vec<u32>, Vec<u32>)> = HashMap::new();
    let mut add = |solid: &BlockSolid, index: u32, thin: bool| {
        if solid.bounds.is_empty() {
            return;
        }
        let lo = |v: f64| (v + EPSILON).floor() as i32;
        let hi = |v: f64| (v - EPSILON).floor() as i32;
        for x in lo(solid.bounds.min.x)..=hi(solid.bounds.max.x) {
            for y in lo(solid.bounds.min.y)..=hi(solid.bounds.max.y) {
                for z in lo(solid.bounds.min.z)..=hi(solid.bounds.max.z) {
                    let cell = [x, y, z];
                    // The sealed inside of a thick wall is most of what a big
                    // brush spans, and nothing there can be walked into.
                    if !sources.grid.is_solid(cell) && (sources.interior)(cell) {
                        continue;
                    }
                    let entry = touching.entry(cell).or_default();
                    if thin {
                        entry.1.push(index);
                    } else {
                        entry.0.push(index);
                    }
                }
            }
        }
    };
    for (index, solid) in sources.solids.iter().enumerate() {
        add(solid, index as u32, false);
    }
    for (index, solid) in sources.thin.iter().enumerate() {
        add(solid, index as u32, true);
    }
    let terrain: Vec<HashMap<IVec3, SubCells>> =
        sources.terrain.par_iter().map(terrain_volume).collect();
    let mut terrain_cells: HashMap<IVec3, Vec<u32>> = HashMap::new();
    for (index, cells) in terrain.iter().enumerate() {
        for cell in cells.keys() {
            terrain_cells.entry(*cell).or_default().push(index as u32);
        }
    }
    let mut cells: BTreeSet<IVec3> = touching.keys().copied().collect();
    cells.extend(terrain_cells.keys().copied());
    // A block no brush or terrain reaches is backing the grid put behind a
    // displacement to be seen from below; it has no volume of its own.
    cells.extend(sources.grid.iter().map(|(cell, _)| cell));
    let cells: Vec<IVec3> = cells.into_iter().collect();

    enum Outcome {
        Block(Box<SubCells>),
        Air(Vec<Piece>),
    }
    let empty = (Vec::new(), Vec::new());
    let outcomes: Vec<(IVec3, Outcome)> = cells
        .par_iter()
        .filter_map(|&cell| {
            let (solids, thin) = touching.get(&cell).unwrap_or(&empty);
            let terrains = terrain_cells.get(&cell).map(Vec::as_slice).unwrap_or(&[]);
            if sources.grid.is_solid(cell) {
                let all: Vec<&BlockSolid> = solids
                    .iter()
                    .map(|&i| sources.solids[i as usize])
                    .chain(thin.iter().map(|&i| sources.thin[i as usize]))
                    .collect();
                let mut bits = solid_volume(cell, &all);
                for &t in terrains {
                    bits.union(&terrain[t as usize][&cell]);
                }
                return (!bits.is_full()).then(|| (cell, Outcome::Block(Box::new(bits))));
            }
            if (sources.interior)(cell) {
                return None;
            }
            let mut pieces = Vec::new();
            for &i in solids {
                let bits = solid_volume(cell, &[sources.solids[i as usize]]);
                if !bits.is_empty() {
                    pieces.push(Piece {
                        bits,
                        attachable: true,
                    });
                }
            }
            for &i in thin {
                let bits = solid_volume(cell, &[sources.thin[i as usize]]);
                if !bits.is_empty() {
                    pieces.push(Piece {
                        bits,
                        attachable: false,
                    });
                }
            }
            for &t in terrains {
                pieces.push(Piece {
                    bits: terrain[t as usize][&cell].clone(),
                    attachable: true,
                });
            }
            (!pieces.is_empty()).then_some((cell, Outcome::Air(pieces)))
        })
        .collect();

    // Every map block's own volume, for the contact test. A block absent from
    // here is a full cube.
    let mut own: HashMap<IVec3, SubCells> = HashMap::new();
    let mut air: Vec<(IVec3, Vec<Piece>)> = Vec::new();
    for (cell, outcome) in outcomes {
        match outcome {
            Outcome::Block(bits) => {
                own.insert(cell, *bits);
            }
            Outcome::Air(pieces) => air.push((cell, pieces)),
        }
    }
    let full = SubCells::full();
    let volume_of = |cell: IVec3| -> Option<&SubCells> {
        sources
            .grid
            .is_solid(cell)
            .then(|| own.get(&cell).unwrap_or(&full))
    };

    // Pieces hanging off a block, by the block and the side they hang from.
    let mut hanging: BTreeMap<(IVec3, usize), SubCells> = BTreeMap::new();
    let mut carried: BTreeMap<IVec3, SubCells> = BTreeMap::new();
    let mut attached = 0;
    for (cell, pieces) in &air {
        for piece in pieces {
            let mut best: Option<(u32, usize)> = None;
            if piece.attachable {
                for (d, direction) in DIRECTIONS.iter().enumerate() {
                    let neighbour = [
                        cell[0] + direction[0],
                        cell[1] + direction[1],
                        cell[2] + direction[2],
                    ];
                    let Some(volume) = volume_of(neighbour) else {
                        continue;
                    };
                    let touching = piece.bits.contact(volume, *direction);
                    if touching > 0 && best.is_none_or(|(score, _)| touching > score) {
                        best = Some((touching, d));
                    }
                }
            }
            match best {
                Some((_, d)) => {
                    let direction = DIRECTIONS[d];
                    let owner = [
                        cell[0] + direction[0],
                        cell[1] + direction[1],
                        cell[2] + direction[2],
                    ];
                    // Keyed by the side of the owner the piece hangs from,
                    // which is the opposite of the way the owner lies.
                    hanging
                        .entry((owner, opposite(d)))
                        .or_insert_with(SubCells::empty)
                        .union(&piece.bits);
                    attached += 1;
                }
                None => carried
                    .entry(*cell)
                    .or_insert_with(SubCells::empty)
                    .union(&piece.bits),
            }
        }
    }

    let mut shapes: BTreeMap<IVec3, Vec<Box16>> = BTreeMap::new();
    for (cell, bits) in &own {
        shapes.insert(*cell, bits.boxes());
    }
    for ((owner, d), bits) in &hanging {
        let direction = DIRECTIONS[*d];
        let shape = shapes.entry(*owner).or_insert_with(|| {
            // A full block gains an entry once something hangs off it.
            if own.contains_key(owner) {
                Vec::new()
            } else {
                full.boxes()
            }
        });
        for mut b in bits.boxes() {
            for axis in 0..3 {
                let shift = (direction[axis] * STEPS) as i8;
                b[axis] += shift;
                b[axis + 3] += shift;
            }
            shape.push(b);
        }
    }
    let carriers: BTreeSet<IVec3> = carried.keys().copied().collect();
    for (cell, bits) in carried {
        shapes.insert(cell, bits.boxes());
    }
    CellCollision {
        shapes,
        carriers,
        attached,
    }
}

/// The sixteenths a prop's surface passes through, per cell. `triangles` are
/// in block space.
///
/// A model is a surface, not a solid: its collision is the shell it draws, a
/// sixteenth thick, which is solid to walk into from any side and keeps a
/// railing's gaps and a catwalk's grating open where the mesh has them.
pub fn shell_volume(triangles: &[[Vec3; 3]]) -> HashMap<IVec3, SubCells> {
    // A big prop is thousands of triangles; chunks of them fill cells in
    // parallel and the cells are unioned, which is order-independent.
    triangles
        .par_chunks(256)
        .map(shell_volume_serial)
        .reduce(HashMap::new, |mut a, b| {
            if a.len() < b.len() {
                return merge_cells(b, a);
            }
            merge_cells(std::mem::take(&mut a), b)
        })
}

fn merge_cells(
    mut into: HashMap<IVec3, SubCells>,
    from: HashMap<IVec3, SubCells>,
) -> HashMap<IVec3, SubCells> {
    for (cell, bits) in from {
        into.entry(cell)
            .or_insert_with(SubCells::empty)
            .union(&bits);
    }
    into
}

fn shell_volume_serial(triangles: &[[Vec3; 3]]) -> HashMap<IVec3, SubCells> {
    let scale = f64::from(STEPS);
    let mut out: HashMap<IVec3, SubCells> = HashMap::new();
    for [a, b, c] in triangles {
        let fine = crate::voxel::mesh::Triangle::new(*a * scale, *b * scale, *c * scale);
        if fine.is_degenerate() {
            continue;
        }
        let bounds = fine.bounds();
        // An edge exactly on a sixteenth line only touches the sixteenth
        // beyond it; claiming that one too spilled every prop aligned to the
        // grid into the neighbouring cells.
        let lo: [i32; 3] = std::array::from_fn(|i| (bounds.min.axis(i) + EPSILON).floor() as i32);
        let hi: [i32; 3] =
            std::array::from_fn(|i| ((bounds.max.axis(i) - EPSILON).floor() as i32).max(lo[i]));
        crate::voxel::mesh::plane_candidates(&fine, lo, hi, |[x, y, z]| {
            if !crate::voxel::mesh::triangle_overlaps_voxel(&fine, [x, y, z]) {
                return;
            }
            let cell = [
                x.div_euclid(STEPS),
                y.div_euclid(STEPS),
                z.div_euclid(STEPS),
            ];
            out.entry(cell).or_insert_with(SubCells::empty).set(
                x.rem_euclid(STEPS),
                y.rem_euclid(STEPS),
                z.rem_euclid(STEPS),
            );
        });
    }
    out
}

/// The sixteenths a block-space box covers, rounded outward, per cell.
pub fn box_volume(min: Vec3, max: Vec3) -> HashMap<IVec3, SubCells> {
    let scale = f64::from(STEPS);
    let lo: [i64; 3] = std::array::from_fn(|a| (min.axis(a) * scale + EPSILON).floor() as i64);
    let hi: [i64; 3] = std::array::from_fn(|a| (max.axis(a) * scale - EPSILON).ceil() as i64);
    let mut out: HashMap<IVec3, SubCells> = HashMap::new();
    if (0..3).any(|a| hi[a] <= lo[a]) {
        return out;
    }
    let step = i64::from(STEPS);
    for cx in lo[0].div_euclid(step)..=(hi[0] - 1).div_euclid(step) {
        for cy in lo[1].div_euclid(step)..=(hi[1] - 1).div_euclid(step) {
            for cz in lo[2].div_euclid(step)..=(hi[2] - 1).div_euclid(step) {
                let cell = [cx, cy, cz];
                let from: [i32; 3] =
                    std::array::from_fn(|a| (lo[a] - cell[a] * step).clamp(0, step) as i32);
                let to: [i32; 3] =
                    std::array::from_fn(|a| (hi[a] - cell[a] * step).clamp(0, step) as i32);
                out.entry([cx as i32, cy as i32, cz as i32])
                    .or_insert_with(SubCells::empty)
                    .fill(from, to);
            }
        }
    }
    out
}

/// Add props' volume to a map's collision.
///
/// A prop's piece in a map block's cell joins that block's shape; a block that
/// is already a full cube needs nothing. A piece in a cell with no block gets
/// a carrier there, or joins the carrier already there.
pub fn add_props(
    collision: &mut CellCollision,
    grid: &VoxelGrid,
    cells: HashMap<IVec3, SubCells>,
) -> usize {
    let mut added = 0;
    for (cell, bits) in cells {
        if bits.is_empty() {
            continue;
        }
        if grid.is_solid(cell) && !collision.shapes.contains_key(&cell) {
            continue;
        }
        if !grid.is_solid(cell) {
            collision.carriers.insert(cell);
        }
        let shape = collision.shapes.entry(cell).or_default();
        shape.extend(bits.boxes());
        shape.sort_unstable();
        shape.dedup();
        added += 1;
    }
    added
}

fn opposite(d: usize) -> usize {
    d ^ 1
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::Aabb;

    fn cuboid(min: [f64; 3], max: [f64; 3]) -> BlockSolid {
        let (lo, hi) = (
            Vec3::new(min[0], min[1], min[2]),
            Vec3::new(max[0], max[1], max[2]),
        );
        BlockSolid {
            planes: crate::geom::box_planes(lo, hi).to_vec(),
            bounds: Aabb::new(lo, hi),
            side_of_plane: (0..6).collect(),
        }
    }

    #[test]
    fn a_floor_part_way_up_its_cell_is_one_box_to_its_top() {
        let floor = cuboid([-4.0, -3.0, -4.0], [4.0, 0.25, 4.0]);
        let bits = solid_volume([0, 0, 0], &[&floor]);
        assert_eq!(bits.boxes(), vec![[0, 0, 0, 16, 4, 16]]);
    }

    #[test]
    fn a_face_on_a_sixteenth_line_claims_nothing_beyond_it() {
        let floor = cuboid([0.0, -1.0, 0.0], [1.0, 0.5, 1.0]);
        let bits = solid_volume([0, 0, 0], &[&floor]);
        assert_eq!(bits.count(), 16 * 16 * 8);
        assert!(solid_volume([0, 1, 0], &[&floor]).is_empty());
    }

    #[test]
    fn off_grid_faces_round_outward() {
        // 1.03 blocks: just past the first sixteenth of the cell above.
        let floor = cuboid([0.0, 0.0, 0.0], [1.0, 1.03, 1.0]);
        let bits = solid_volume([0, 1, 0], &[&floor]);
        assert_eq!(bits.boxes(), vec![[0, 0, 0, 16, 1, 16]]);
    }

    #[test]
    fn two_bars_keep_the_gap_between_them() {
        let a = cuboid([0.0, 0.0, 0.0], [0.25, 1.0, 1.0]);
        let b = cuboid([0.75, 0.0, 0.0], [1.0, 1.0, 1.0]);
        let boxes = solid_volume([0, 0, 0], &[&a, &b]).boxes();
        assert_eq!(boxes, vec![[0, 0, 0, 4, 16, 16], [12, 0, 0, 16, 16, 16]]);
    }

    #[test]
    fn a_sloped_brush_steps_and_stays_outside_its_plane() {
        // A 45 degree ramp: solid below y = x.
        let mut ramp = cuboid([0.0, 0.0, 0.0], [1.0, 1.0, 1.0]);
        let n = Vec3::new(-1.0, 1.0, 0.0).normalized();
        ramp.planes.push(Plane::new(n, 0.0));
        ramp.side_of_plane.push(6);
        let bits = solid_volume([0, 0, 0], &[&ramp]);
        for x in 0..16 {
            for y in 0..16 {
                // A sixteenth wholly above the plane must stay open.
                assert_eq!(bits.get(x, y, 0), y <= x, "x={x} y={y}");
            }
        }
    }

    #[test]
    fn boxes_cover_exactly_the_solid_sixteenths() {
        let mut bits = SubCells::empty();
        for (x, y, z) in [(0, 0, 0), (1, 0, 0), (5, 7, 3), (15, 15, 15), (0, 1, 0)] {
            bits.set(x, y, z);
        }
        let mut back = SubCells::empty();
        for b in bits.boxes() {
            back.fill(
                [b[0] as i32, b[1] as i32, b[2] as i32],
                [b[3] as i32, b[4] as i32, b[5] as i32],
            );
        }
        assert_eq!(back, bits);
        assert_eq!(SubCells::full().boxes(), vec![[0, 0, 0, 16, 16, 16]]);
    }

    #[test]
    fn terrain_is_a_skin_under_its_surface_and_ignores_the_backing() {
        let terrain = Terrain {
            triangles: vec![
                [
                    Vec3::new(0.0, 5.25, 0.0),
                    Vec3::new(1.0, 5.25, 0.0),
                    Vec3::new(1.0, 5.25, 1.0),
                ],
                [
                    Vec3::new(0.0, 5.25, 0.0),
                    Vec3::new(1.0, 5.25, 1.0),
                    Vec3::new(0.0, 5.25, 1.0),
                ],
            ],
            inward: Vec3::new(0.0, -1.0, 0.0),
        };
        let cells = terrain_volume(&terrain);
        assert_eq!(cells[&[0, 5, 0]].boxes(), vec![[0, 3, 0, 16, 4, 16]]);
        assert_eq!(cells.len(), 1);
    }

    fn grid(cells: &[IVec3]) -> VoxelGrid {
        let mut grid = VoxelGrid::new();
        for cell in cells {
            grid.set(*cell, 1);
        }
        grid
    }

    #[test]
    fn a_floor_above_its_block_hangs_off_that_block() {
        let floor = cuboid([0.0, -1.0, 0.0], [1.0, 1.25, 1.0]);
        let grid = grid(&[[0, 0, 0], [0, -1, 0]]);
        let none = |_: IVec3| false;
        let collision = compute(&Sources {
            grid: &grid,
            interior: &none,
            solids: vec![&floor],
            thin: vec![],
            terrain: vec![],
        });
        assert!(collision.carriers.is_empty());
        assert_eq!(collision.attached, 1);
        assert_eq!(
            collision.shapes[&[0, 0, 0]],
            vec![[0, 0, 0, 16, 16, 16], [0, 16, 0, 16, 20, 16]]
        );
        assert!(!collision.shapes.contains_key(&[0, -1, 0]));
    }

    #[test]
    fn a_block_the_voxelizer_rounded_into_a_room_collides_as_what_is_there() {
        // The brush fills 0.6 of the cell, so the cell became a block.
        let wall = cuboid([-3.0, 0.0, 0.0], [0.6, 1.0, 1.0]);
        let grid = grid(&[[-3, 0, 0], [-2, 0, 0], [-1, 0, 0], [0, 0, 0]]);
        let none = |_: IVec3| false;
        let collision = compute(&Sources {
            grid: &grid,
            interior: &none,
            solids: vec![&wall],
            thin: vec![],
            terrain: vec![],
        });
        assert_eq!(collision.shapes[&[0, 0, 0]], vec![[0, 0, 0, 10, 16, 16]]);
    }

    #[test]
    fn a_thin_plate_gets_a_carrier_even_beside_a_block() {
        let plate = cuboid([0.0, 1.0, 0.0], [1.0, 1.125, 1.0]);
        let grid = grid(&[[0, 0, 0]]);
        let none = |_: IVec3| false;
        let collision = compute(&Sources {
            grid: &grid,
            interior: &none,
            solids: vec![],
            thin: vec![&plate],
            terrain: vec![],
        });
        assert_eq!(collision.carriers, BTreeSet::from([[0, 1, 0]]));
        assert_eq!(collision.shapes[&[0, 1, 0]], vec![[0, 0, 0, 16, 2, 16]]);
        // The plate is not the block's: the block holds no volume of its own.
        assert_eq!(collision.shapes[&[0, 0, 0]], Vec::<Box16>::new());
    }

    #[test]
    fn a_block_nothing_reaches_collides_as_nothing() {
        let grid = grid(&[[0, 0, 0]]);
        let none = |_: IVec3| false;
        let collision = compute(&Sources {
            grid: &grid,
            interior: &none,
            solids: vec![],
            thin: vec![],
            terrain: vec![],
        });
        assert_eq!(collision.shapes[&[0, 0, 0]], Vec::<Box16>::new());
    }

    #[test]
    fn a_prop_shell_is_a_sixteenth_where_its_surface_is() {
        // A 1x1 floor plate at y = 0.5 inside cell 0.
        let cells = shell_volume(&[
            [
                Vec3::new(0.0, 0.5, 0.0),
                Vec3::new(1.0, 0.5, 0.0),
                Vec3::new(1.0, 0.5, 1.0),
            ],
            [
                Vec3::new(0.0, 0.5, 0.0),
                Vec3::new(1.0, 0.5, 1.0),
                Vec3::new(0.0, 0.5, 1.0),
            ],
        ]);
        assert_eq!(cells.len(), 1);
        assert_eq!(cells[&[0, 0, 0]].boxes(), vec![[0, 8, 0, 16, 9, 16]]);
    }

    #[test]
    fn a_box_splits_across_the_cells_it_spans() {
        let cells = box_volume(Vec3::new(0.5, 0.0, 0.0), Vec3::new(1.25, 0.5, 1.0));
        assert_eq!(cells[&[0, 0, 0]].boxes(), vec![[8, 0, 0, 16, 8, 16]]);
        assert_eq!(cells[&[1, 0, 0]].boxes(), vec![[0, 0, 0, 4, 8, 16]]);
        assert_eq!(cells.len(), 2);
    }

    #[test]
    fn props_join_partial_blocks_and_get_carriers_in_air() {
        let mut grid = VoxelGrid::new();
        grid.set([0, 0, 0], 1);
        grid.set([1, 0, 0], 1);
        let mut collision = CellCollision::default();
        collision
            .shapes
            .insert([1, 0, 0], vec![[0, 0, 0, 16, 4, 16]]);
        let cells = box_volume(Vec3::new(0.0, 0.0, 0.0), Vec3::new(3.0, 1.0, 1.0));
        add_props(&mut collision, &grid, cells);
        // The full block needs nothing; the slab block gains the prop.
        assert!(!collision.shapes.contains_key(&[0, 0, 0]));
        assert!(collision.shapes[&[1, 0, 0]].contains(&[0, 0, 0, 16, 16, 16]));
        assert_eq!(collision.carriers, BTreeSet::from([[2, 0, 0]]));
    }

    #[test]
    fn hollowed_interior_gets_nothing() {
        let mass = cuboid([0.0, 0.0, 0.0], [3.0, 3.0, 3.0]);
        let shell: Vec<IVec3> = (0..27)
            .map(|i| [i % 3, i / 3 % 3, i / 9])
            .filter(|c| *c != [1, 1, 1])
            .collect();
        let grid = grid(&shell);
        let interior = |c: IVec3| c == [1, 1, 1];
        let collision = compute(&Sources {
            grid: &grid,
            interior: &interior,
            solids: vec![&mass],
            thin: vec![],
            terrain: vec![],
        });
        assert!(collision.shapes.is_empty());
        assert!(collision.carriers.is_empty());
    }
}
