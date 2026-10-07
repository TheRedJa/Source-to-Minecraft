//! Exact visible world geometry, cut into the cells that own it.
//!
//! The block grid is where the map can be edited, not where it is drawn. Every
//! visible Source face is kept as the polygon the map compiler wrote, and is cut
//! along the integer block planes into one convex piece per cell. Each piece
//! belongs to the cell *behind* it — the solid side of the face — so breaking
//! that cell's block takes exactly the geometry that was inside it with it, and
//! nothing is ever snapped to the grid to make it fit.
//!
//! Positions are written in 1/[`QUANTUM`] of a block relative to the owning
//! cell. A vertex on a cell boundary is exactly 0 or [`QUANTUM`] in each cell
//! that shares it, and the other two coordinates are measured from the same
//! cell origin on both sides, so neighbouring pieces meet edge to edge with no
//! crack for the renderer to show.

use crate::geom::Vec3;
use crate::voxel::grid::{IVec3, VoxelGrid};
use crate::voxel::surface::FaceSource;

/// Subdivisions of a block per axis in a stored vertex coordinate.
pub const QUANTUM: f64 = 4096.0;
/// The most vertices one stored piece may have.
pub const MAX_VERTICES: usize = 64;
/// Cutting a convex polygon by a cell's six planes adds at most one vertex per
/// plane, so anything this size or smaller always fits [`MAX_VERTICES`].
const MAX_INPUT_VERTICES: usize = MAX_VERTICES - 6;
/// Positions within this of a block plane are on it. Block coordinates of real
/// map geometry are multiples of 1/32; this only absorbs arithmetic noise.
const EPSILON: f64 = 1.0e-7;

/// One planar convex polygon of visible world geometry, in block space.
#[derive(Debug, Clone)]
pub struct Polygon {
    pub source: FaceSource,
    /// Unit normal of the visible side.
    pub normal: Vec3,
    pub points: Vec<Vec3>,
}

/// The part of one [`Polygon`] that lies in one cell.
#[derive(Debug, Clone, PartialEq)]
pub struct Fragment {
    /// The cell behind the fragment, which owns it.
    pub cell: IVec3,
    /// The block this fragment belongs to, as an offset from `cell`: zero when
    /// `cell` holds a block, one step further behind when the face sits just
    /// off the grid in front of one. An owned fragment is drawn only while
    /// that block is there. `None` has no block to belong to and is always
    /// drawn.
    pub owner: Option<IVec3>,
    pub source: FaceSource,
    /// Unit normal of the visible side, in block space.
    pub normal: Vec3,
    /// Counter-clockwise seen from the front, relative to `cell`.
    pub vertices: Vec<[u16; 3]>,
}

impl Polygon {
    /// Winds `points` counter-clockwise about `normal`, which is what the
    /// renderer takes for the front.
    pub fn new(source: FaceSource, normal: Vec3, mut points: Vec<Vec3>) -> Polygon {
        if newell(&points).dot(normal) < 0.0 {
            points.reverse();
        }
        Polygon {
            source,
            normal: normal.normalized(),
            points,
        }
    }

    /// Splits a polygon with too many vertices to store into convex fans that
    /// each fit, in order. Nearly every Source face already fits and comes
    /// back whole.
    pub fn pieces(&self) -> Vec<Vec<Vec3>> {
        if self.points.len() <= MAX_INPUT_VERTICES {
            return vec![self.points.clone()];
        }
        let mut pieces = Vec::new();
        let mut start = 1;
        while start + 1 < self.points.len() {
            let end = (start + MAX_INPUT_VERTICES - 2).min(self.points.len() - 1);
            let mut piece = Vec::with_capacity(end - start + 2);
            piece.push(self.points[0]);
            piece.extend_from_slice(&self.points[start..=end]);
            pieces.push(piece);
            start = end;
        }
        pieces
    }
}

/// Cuts `polygon` into the cells it passes through.
pub fn fragments(polygon: &Polygon, grid: &VoxelGrid) -> Vec<Fragment> {
    let normal = polygon.normal;
    let mut out = Vec::new();
    for (sx, strip) in cut(polygon.points.clone(), 0, normal) {
        for (sy, row) in cut(strip, 1, normal) {
            for (sz, piece) in cut(row, 2, normal) {
                let cell = [sx, sy, sz];
                let Some(vertices) = quantize(&piece, cell, normal) else {
                    continue;
                };
                out.push(Fragment {
                    cell,
                    owner: owner(grid, cell, normal),
                    source: polygon.source,
                    normal,
                    vertices,
                });
            }
        }
    }
    out
}

/// The block a fragment in `cell` belongs to, as an offset from `cell`.
///
/// Its own cell when that holds a block. Otherwise the cell behind it along
/// the axis the face points most along: a floor a quarter of a block above
/// the block it rests on lies in the air cell above that block, and breaking
/// the block has to take the floor with it.
fn owner(grid: &VoxelGrid, cell: IVec3, normal: Vec3) -> Option<IVec3> {
    if grid.is_solid(cell) {
        return Some([0, 0, 0]);
    }
    let axis = normal.major_axis();
    let mut offset = [0, 0, 0];
    offset[axis] = if normal.axis(axis) > 0.0 { -1 } else { 1 };
    let behind = [
        cell[0] + offset[0],
        cell[1] + offset[1],
        cell[2] + offset[2],
    ];
    grid.is_solid(behind).then_some(offset)
}

/// Splits `points` along every integer plane across `axis`, returning each
/// piece with the cell index it belongs to on that axis.
fn cut(points: Vec<Vec3>, axis: usize, normal: Vec3) -> Vec<(i32, Vec<Vec3>)> {
    let (min, max) = extent(&points, axis);
    let first = (min + EPSILON).floor() as i64 + 1;
    let last = (max - EPSILON).ceil() as i64 - 1;
    let mut out = Vec::new();
    let mut rest = points;
    for plane in first..=last {
        let (below, above) = split(&rest, axis, plane as f64);
        if below.len() >= 3 {
            out.push((cell_index(&below, axis, normal), below));
        }
        rest = above;
    }
    if rest.len() >= 3 {
        out.push((cell_index(&rest, axis, normal), rest));
    }
    out
}

/// The cell a piece belongs to along `axis`. A piece lying in a block plane is
/// shared by the cells on both sides of it and goes to the one behind it.
fn cell_index(points: &[Vec3], axis: usize, normal: Vec3) -> i32 {
    let (min, max) = extent(points, axis);
    let middle = (min + max) * 0.5;
    let plane = middle.round();
    if max - min < EPSILON && (middle - plane).abs() < EPSILON {
        return if normal.axis(axis) > 0.0 {
            plane as i32 - 1
        } else {
            plane as i32
        };
    }
    middle.floor() as i32
}

/// Sutherland–Hodgman against one axis plane: the part at or below `value`,
/// and the part at or above it. A vertex on the plane goes to both.
fn split(points: &[Vec3], axis: usize, value: f64) -> (Vec<Vec3>, Vec<Vec3>) {
    let side = |p: Vec3| {
        let d = p.axis(axis) - value;
        if d.abs() <= EPSILON {
            0
        } else if d < 0.0 {
            -1
        } else {
            1
        }
    };
    let mut below = Vec::with_capacity(points.len() + 1);
    let mut above = Vec::with_capacity(points.len() + 1);
    for (index, &p) in points.iter().enumerate() {
        let q = points[(index + 1) % points.len()];
        let (sp, sq) = (side(p), side(q));
        if sp <= 0 {
            below.push(p);
        }
        if sp >= 0 {
            above.push(p);
        }
        if sp * sq < 0 {
            let dp = p.axis(axis) - value;
            let dq = q.axis(axis) - value;
            let crossing = with_axis(p + (q - p) * (dp / (dp - dq)), axis, value);
            below.push(crossing);
            above.push(crossing);
        }
    }
    (below, above)
}

/// Stores a piece relative to its cell, or `None` if nothing drawable is left
/// of it once it is on the stored grid.
fn quantize(points: &[Vec3], cell: IVec3, normal: Vec3) -> Option<Vec<[u16; 3]>> {
    let mut out: Vec<[u16; 3]> = Vec::with_capacity(points.len());
    for p in points {
        let local = std::array::from_fn(|axis| {
            let offset = (p.axis(axis) - cell[axis] as f64) * QUANTUM;
            offset.round().clamp(0.0, QUANTUM) as u16
        });
        if out.last() != Some(&local) {
            out.push(local);
        }
    }
    while out.len() > 1 && out.first() == out.last() {
        out.pop();
    }
    if out.len() < 3 || out.len() > MAX_VERTICES {
        return None;
    }
    // Newell's normal on the stored integers is twice the stored area. A sliver
    // under half a unit of that is nothing on screen, and one that turned
    // round on the way to integers would be drawn from behind.
    let stored: Vec<Vec3> = out
        .iter()
        .map(|v| Vec3::new(v[0] as f64, v[1] as f64, v[2] as f64))
        .collect();
    let area = newell(&stored);
    (area.length() >= 1.0 && area.dot(normal) > 0.0).then_some(out)
}

fn newell(points: &[Vec3]) -> Vec3 {
    let mut n = Vec3::ZERO;
    for (index, &p) in points.iter().enumerate() {
        let q = points[(index + 1) % points.len()];
        n = n + Vec3::new(
            (p.y - q.y) * (p.z + q.z),
            (p.z - q.z) * (p.x + q.x),
            (p.x - q.x) * (p.y + q.y),
        );
    }
    n
}

fn extent(points: &[Vec3], axis: usize) -> (f64, f64) {
    points
        .iter()
        .fold((f64::INFINITY, f64::NEG_INFINITY), |(lo, hi), p| {
            (lo.min(p.axis(axis)), hi.max(p.axis(axis)))
        })
}

fn with_axis(mut v: Vec3, axis: usize, value: f64) -> Vec3 {
    match axis {
        0 => v.x = value,
        1 => v.y = value,
        _ => v.z = value,
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bsp::texcoord::BlockTexCoord;
    use crate::voxel::surface::SourceProvenance;

    fn source() -> FaceSource {
        FaceSource {
            provenance: SourceProvenance::Face { face: 0, piece: 0 },
            material: 0,
            uv: BlockTexCoord {
                u: [1.0, 0.0, 0.0, 0.0],
                v: [0.0, 0.0, 1.0, 0.0],
            },
            light: None,
        }
    }

    fn square(y: f64, normal_y: f64, size: f64) -> Polygon {
        Polygon::new(
            source(),
            Vec3::new(0.0, normal_y, 0.0),
            vec![
                Vec3::new(0.0, y, 0.0),
                Vec3::new(size, y, 0.0),
                Vec3::new(size, y, size),
                Vec3::new(0.0, y, size),
            ],
        )
    }

    fn solid(cells: &[IVec3]) -> VoxelGrid {
        let mut grid = VoxelGrid::new();
        for &cell in cells {
            grid.set(cell, 1);
        }
        grid
    }

    #[test]
    fn a_floor_on_a_block_plane_belongs_to_the_cell_below_it() {
        let pieces = fragments(&square(2.0, 1.0, 1.0), &solid(&[[0, 1, 0]]));
        assert_eq!(pieces.len(), 1);
        assert_eq!(pieces[0].cell, [0, 1, 0]);
        assert_eq!(pieces[0].owner, Some([0, 0, 0]));
        assert!(pieces[0].vertices.iter().all(|v| v[1] == 4096));
    }

    #[test]
    fn a_ceiling_on_a_block_plane_belongs_to_the_cell_above_it() {
        let pieces = fragments(&square(2.0, -1.0, 1.0), &VoxelGrid::new());
        assert_eq!(pieces.len(), 1);
        assert_eq!(pieces[0].cell, [0, 2, 0]);
        assert_eq!(pieces[0].owner, None);
        assert!(pieces[0].vertices.iter().all(|v| v[1] == 0));
    }

    #[test]
    fn an_off_grid_floor_stays_where_it_is() {
        // A floor a quarter of a block above the plane: no snapping.
        let pieces = fragments(&square(2.25, 1.0, 1.0), &VoxelGrid::new());
        assert_eq!(pieces.len(), 1);
        assert_eq!(pieces[0].cell, [0, 2, 0]);
        assert!(pieces[0].vertices.iter().all(|v| v[1] == 1024));
    }

    #[test]
    fn an_off_grid_floor_belongs_to_the_block_it_rests_on() {
        let pieces = fragments(&square(2.25, 1.0, 1.0), &solid(&[[0, 1, 0]]));
        assert_eq!(pieces[0].cell, [0, 2, 0]);
        assert_eq!(pieces[0].owner, Some([0, -1, 0]));
        // A ceiling just below a block belongs to the block above it.
        let pieces = fragments(&square(1.75, -1.0, 1.0), &solid(&[[0, 2, 0]]));
        assert_eq!(pieces[0].cell, [0, 1, 0]);
        assert_eq!(pieces[0].owner, Some([0, 1, 0]));
    }

    #[test]
    fn a_large_face_is_cut_into_one_piece_per_cell_that_share_edges() {
        let pieces = fragments(&square(0.5, 1.0, 3.0), &VoxelGrid::new());
        assert_eq!(pieces.len(), 9);
        let mut cells: Vec<IVec3> = pieces.iter().map(|p| p.cell).collect();
        cells.sort();
        cells.dedup();
        assert_eq!(cells.len(), 9);
        for piece in &pieces {
            assert_eq!(piece.vertices.len(), 4);
            for v in &piece.vertices {
                assert!(v[0] == 0 || v[0] == 4096);
                assert!(v[2] == 0 || v[2] == 4096);
            }
        }
    }

    #[test]
    fn a_slope_is_cut_where_it_crosses_block_planes() {
        // A ramp rising one block over two, 1 wide, from y = 0.5: it crosses
        // y = 1 exactly where it crosses x = 1, so it lies in two cells.
        let ramp = Polygon::new(
            source(),
            Vec3::new(-1.0, 2.0, 0.0).normalized(),
            vec![
                Vec3::new(0.0, 0.5, 0.0),
                Vec3::new(0.0, 0.5, 1.0),
                Vec3::new(2.0, 1.5, 1.0),
                Vec3::new(2.0, 1.5, 0.0),
            ],
        );
        let pieces = fragments(&ramp, &VoxelGrid::new());
        let mut cells: Vec<IVec3> = pieces.iter().map(|p| p.cell).collect();
        cells.sort();
        assert_eq!(cells, vec![[0, 0, 0], [1, 1, 0]]);
        for piece in &pieces {
            let n = newell(
                &piece
                    .vertices
                    .iter()
                    .map(|v| Vec3::new(v[0] as f64, v[1] as f64, v[2] as f64))
                    .collect::<Vec<_>>(),
            );
            assert!(
                n.dot(ramp.normal) > 0.0,
                "piece in {:?} faces away",
                piece.cell
            );
        }
    }

    #[test]
    fn a_slope_crossing_planes_apart_lands_in_every_cell_it_passes() {
        // From y = 0.25 to 1.25 over two blocks: y = 1 is crossed at x = 1.5.
        let ramp = Polygon::new(
            source(),
            Vec3::new(-1.0, 2.0, 0.0).normalized(),
            vec![
                Vec3::new(0.0, 0.25, 0.0),
                Vec3::new(0.0, 0.25, 1.0),
                Vec3::new(2.0, 1.25, 1.0),
                Vec3::new(2.0, 1.25, 0.0),
            ],
        );
        let mut cells: Vec<IVec3> = fragments(&ramp, &VoxelGrid::new())
            .iter()
            .map(|p| p.cell)
            .collect();
        cells.sort();
        assert_eq!(cells, vec![[0, 0, 0], [1, 0, 0], [1, 1, 0]]);
    }

    #[test]
    fn winding_follows_the_normal_whatever_order_the_points_came_in() {
        let mut down = square(1.0, -1.0, 1.0);
        let up = square(1.0, 1.0, 1.0);
        assert!(newell(&down.points).dot(down.normal) > 0.0);
        assert!(newell(&up.points).dot(up.normal) > 0.0);
        down.points.reverse();
        let rewound = Polygon::new(source(), down.normal, down.points);
        assert!(newell(&rewound.points).dot(rewound.normal) > 0.0);
    }

    #[test]
    fn a_face_with_too_many_vertices_is_fanned_into_pieces_that_fit() {
        let points: Vec<Vec3> = (0..200)
            .map(|i| {
                let a = i as f64 / 200.0 * std::f64::consts::TAU;
                Vec3::new(0.5 + 0.4 * a.cos(), 0.5, 0.5 - 0.4 * a.sin())
            })
            .collect();
        let polygon = Polygon::new(source(), Vec3::new(0.0, 1.0, 0.0), points);
        let pieces = polygon.pieces();
        assert!(pieces.len() > 1);
        assert!(pieces.iter().all(|p| p.len() <= MAX_INPUT_VERTICES));
        let covered: usize = pieces.iter().map(|p| p.len() - 2).sum();
        assert_eq!(covered, 198, "every fan triangle is in exactly one piece");
    }

    #[test]
    fn slivers_that_vanish_on_the_stored_grid_are_dropped() {
        let sliver = Polygon::new(
            source(),
            Vec3::new(0.0, 1.0, 0.0),
            vec![
                Vec3::new(0.0, 0.5, 0.0),
                Vec3::new(1.0e-6, 0.5, 0.0),
                Vec3::new(1.0e-6, 0.5, 1.0e-6),
            ],
        );
        assert!(fragments(&sliver, &VoxelGrid::new()).is_empty());
    }
}
