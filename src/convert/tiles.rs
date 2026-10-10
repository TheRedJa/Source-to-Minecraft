//! Texture tiling: which tile of a material's texture each block shows.

use super::*;

/// A material's texture split across several blocks, and the blocks it was
/// split into.
///
/// The point of the whole exercise: a Source wall texture covers metres of
/// surface, so squeezing it onto one block face throws away everything that
/// made it read as brick or panelling. With the pieces registered separately,
/// each voxel can take the one that is really in front of it.
pub(super) struct TileSet {
    pub(super) grid: [u32; 2],
    /// How many texels wide and tall one tile is.
    pub(super) texels_per_tile: [f64; 2],
    /// Blocks in row-major order, as the pack registered them.
    pub(super) ids: Vec<BlockId>,
}

impl TileSet {
    /// The tile at a position already measured in tiles. See [`Uv`].
    pub(super) fn at(&self, column: f64, row: f64) -> BlockId {
        let index = |value: f64, axis: usize| {
            // A texture repeats across a wall, so a coordinate off the end of
            // it wraps rather than clamping.
            (value.floor() as i64).rem_euclid(self.grid[axis] as i64) as usize
        };
        let (column, row) = (index(column, 0), index(row, 1));
        self.ids[row * self.grid[0] as usize + column]
    }

    /// The tile at a normalized texture coordinate, as a model stores it.
    pub(super) fn at_uv(&self, uv: [f64; 2]) -> BlockId {
        self.at(uv[0] * self.grid[0] as f64, uv[1] * self.grid[1] as f64)
    }
}

/// Tile coordinates as a function of block-space position: how many tiles
/// along the surface's own texture axes a point lies.
///
/// Both the texture projection and the block transform are affine, so their
/// composition is too and can be reduced to two dot products — which matters,
/// because this is evaluated once per voxel.
///
/// The division by tile size happens here rather than in [`TileSet`] because
/// **the tile size is a property of the face, not of the material**. One
/// material is used at several scales in the same map: `nature/cliffface001a`
/// appears in `d2_coast_07` at six different rates, from a third of a texel
/// per unit to two. A tile size taken from the material's typical scale is
/// then too wide for every face using a larger one, and the wall comes out in
/// 2x2 blocks of the same picture.
#[derive(Clone, Copy)]
pub(super) struct Uv {
    pub(super) s: (Vec3, f64),
    pub(super) t: (Vec3, f64),
}

impl Uv {
    /// `origin` is the brush entity's own origin: its geometry is stored
    /// relative to it and so are its texture vectors, so it has to come back
    /// off before the projection is applied.
    ///
    /// `texels_per_tile` is what the material's tiles were actually cut at.
    /// A face wanting *fewer* texels per block than that would repeat a tile
    /// across several blocks, so on those faces the tile is resized to the
    /// block instead; the texture then covers `grid` blocks rather than its
    /// true span, which is the compromise a single shared set of tiles forces.
    /// Faces wanting more are left exact — they advance by more than one tile
    /// per block, which shows no repeat and so needs no correction.
    pub(super) fn new(
        tex: crate::bsp::texcoord::TexCoord,
        transform: &Transform,
        origin: Vec3,
        texels_per_tile: [f64; 2],
        units_per_block: f64,
    ) -> Uv {
        let source = |p: Vec3| transform.to_source_space(p) - origin;
        let at = Vec3::ZERO;
        let (s0, t0) = (tex.s(source(at)), tex.t(source(at)));
        let axis = |i: usize| {
            let mut e = Vec3::ZERO;
            match i {
                0 => e.x = 1.0,
                1 => e.y = 1.0,
                _ => e.z = 1.0,
            }
            (tex.s(source(e)) - s0, tex.t(source(e)) - t0)
        };
        let (sx, tx) = axis(0);
        let (sy, ty) = axis(1);
        let (sz, tz) = axis(2);

        // How many texels of this face's own projection one block covers. A
        // degenerate texture vector leaves the material's own size in place
        // rather than dividing by zero.
        let per_block = tex.texels_per_unit();
        let divisor: [f64; 2] = std::array::from_fn(|axis| {
            let face = per_block[axis] * units_per_block;
            let tile = texels_per_tile[axis];
            if face > 0.0 && face.is_finite() {
                face.min(tile)
            } else {
                tile
            }
            .max(f64::MIN_POSITIVE)
        });

        Uv {
            s: (Vec3::new(sx, sy, sz) / divisor[0], s0 / divisor[0]),
            t: (Vec3::new(tx, ty, tz) / divisor[1], t0 / divisor[1]),
        }
    }

    pub(super) fn at(&self, p: Vec3) -> (f64, f64) {
        (self.s.0.dot(p) + self.s.1, self.t.0.dot(p) + self.t.1)
    }
}

/// Work out, once, which materials have a split texture and what blocks it was
/// split into.
///
/// Only materials the palette actually resolves to their generated block are
/// included: a rule naming `iron_bars` for a grate outranks the texture, and
/// tiling a block the map will never place would be nonsense.
pub(super) fn tile_sets(
    map: &Map,
    materials: &[crate::bsp::Material],
    resolver: &Resolver,
    pack: &crate::output::kubejs::Pack,
    palette: &Mutex<Palette>,
) -> Vec<Option<TileSet>> {
    if pack.tilings().is_empty() {
        return Vec::new();
    }
    let _ = map;

    materials
        .iter()
        .enumerate()
        .map(|(index, material)| {
            // Read back exactly how the texture was cut rather than working
            // it out again. Recomputing it is how the cut and the projection
            // drifted apart twice, and with the cap now chosen from a budget
            // the config alone no longer determines it.
            let split = pack.split(&material.name)?;
            let (grid, texels_per_tile) = (split.grid, split.texels_per_tile);

            // The resolver has the last word: a named rule beats the texture.
            let assigned = resolver.block_for_material(index)?;
            if assigned != pack.tile_id(&material.name, 0, 0)? {
                return None;
            }

            let mut ids = Vec::with_capacity((grid[0] * grid[1]) as usize);
            for row in 0..grid[1] {
                for column in 0..grid[0] {
                    let id = pack.tile_id(&material.name, column, row)?;
                    ids.push(palette.lock().unwrap().intern(&id));
                }
            }
            Some(TileSet {
                grid,
                texels_per_tile,
                ids,
            })
        })
        .collect()
}

/// Interpolate a per-corner value to where `p` falls on the triangle.
///
/// Barycentric coordinates, which also project `p` onto the triangle's plane,
/// so a voxel centre slightly off the surface still lands somewhere sensible.
/// A degenerate triangle falls back to the first corner rather than dividing
/// by zero.
pub(super) fn interpolate(tri: &crate::voxel::mesh::Triangle, corners: &[[f64; 2]; 3], p: Vec3) -> [f64; 2] {
    let (v0, v1, v2) = (tri.b - tri.a, tri.c - tri.a, p - tri.a);
    let (d00, d01, d11) = (v0.dot(v0), v0.dot(v1), v1.dot(v1));
    let denom = d00 * d11 - d01 * d01;
    if denom.abs() < 1e-12 {
        return corners[0];
    }
    let (d20, d21) = (v2.dot(v0), v2.dot(v1));
    let b = (d11 * d20 - d01 * d21) / denom;
    let c = (d00 * d21 - d01 * d20) / denom;
    let a = 1.0 - b - c;
    std::array::from_fn(|axis| a * corners[0][axis] + b * corners[1][axis] + c * corners[2][axis])
}

/// The nominal size of the texture a face's projection is expressed in. UVs are
/// texel counts, and one repeat of the sprite is one texture width, so this is
/// what turns them into the normalized coordinates the runtime expects.
pub(super) fn texture_size(map: &Map, info: &vbsp::TextureInfo) -> Option<[f64; 2]> {
    let data = map
        .bsp
        .textures_data
        .get(usize::try_from(info.texture_data_index).ok()?)?;
    let (width, height) = (f64::from(data.width), f64::from(data.height));
    (width > 0.0 && height > 0.0).then_some([width, height])
}
