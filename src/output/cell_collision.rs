//! Versioned table of the collision shapes of a map's cells.
//!
//! A map block collides as a full cube unless this table names another shape
//! for its cell, and a carrier block collides as nothing unless it does. Shapes
//! are written once and referred to by index, since most of a map's partial
//! cells are the same few slabs and wall slices. Cells are grouped in the same
//! 16³ sections the surface table uses.

use crate::voxel::collision::{Box16, STEPS};
use crate::voxel::grid::IVec3;
use anyhow::{Result, ensure};
use std::collections::BTreeMap;

pub const MAGIC: [u8; 8] = *b"S2COLL\0\0";
pub const VERSION: u32 = 1;

/// Encode per-cell shapes. Input order does not reach the output bytes.
pub fn encode(shapes: &BTreeMap<IVec3, Vec<Box16>>) -> Result<Vec<u8>> {
    let mut ids: BTreeMap<&[Box16], u32> = BTreeMap::new();
    for boxes in shapes.values() {
        ensure!(
            boxes.len() <= u16::MAX as usize,
            "collision shape has too many boxes"
        );
        for b in boxes {
            for axis in 0..3 {
                ensure!(
                    b[axis] < b[axis + 3]
                        && i32::from(b[axis]) >= -STEPS
                        && i32::from(b[axis + 3]) <= 2 * STEPS,
                    "collision box {b:?} is empty or reaches past a neighbouring cell"
                );
            }
        }
        ids.entry(boxes.as_slice()).or_insert(0);
    }
    for (next, id) in ids.values_mut().enumerate() {
        *id = next as u32;
    }

    let mut sections: BTreeMap<IVec3, Vec<(u16, u32)>> = BTreeMap::new();
    for (cell, boxes) in shapes {
        sections
            .entry(crate::output::surface::section_of(*cell))
            .or_default()
            .push((local_index(*cell), ids[boxes.as_slice()]));
    }
    ensure!(
        sections.len() as u64 <= crate::output::limits::MAX_SECTIONS_PER_MAP,
        "collision section limit exceeded"
    );

    let mut out = Vec::new();
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&(ids.len() as u32).to_le_bytes());
    for boxes in ids.keys() {
        out.extend_from_slice(&(boxes.len() as u16).to_le_bytes());
        for b in *boxes {
            out.extend(b.iter().map(|v| *v as u8));
        }
    }
    out.extend_from_slice(&(sections.len() as u32).to_le_bytes());
    for (section, mut cells) in sections {
        cells.sort_unstable();
        for coordinate in section {
            out.extend_from_slice(&coordinate.to_le_bytes());
        }
        out.extend_from_slice(&(cells.len() as u16).to_le_bytes());
        for (local, id) in cells {
            out.extend_from_slice(&local.to_le_bytes());
            out.extend_from_slice(&id.to_le_bytes());
        }
    }
    Ok(out)
}

/// A cell's index within its section: X low, then Z, then Y, as the surface
/// table packs it.
fn local_index(cell: IVec3) -> u16 {
    ((cell[1] & 15) << 8 | (cell[2] & 15) << 4 | (cell[0] & 15)) as u16
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u32_at(bytes: &[u8], at: usize) -> u32 {
        u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
    }

    #[test]
    fn an_empty_table_is_a_header_and_two_zero_counts() {
        let bytes = encode(&BTreeMap::new()).unwrap();
        assert_eq!(&bytes[0..8], &MAGIC);
        assert_eq!(u32_at(&bytes, 8), VERSION);
        assert_eq!(u32_at(&bytes, 12), 0);
        assert_eq!(u32_at(&bytes, 16), 0);
        assert_eq!(bytes.len(), 20);
    }

    #[test]
    fn a_shared_shape_is_written_once() {
        let slab = vec![[0, 0, 0, 16, 8, 16]];
        let shapes = BTreeMap::from([
            ([0, 0, 0], slab.clone()),
            ([5, 0, 0], slab.clone()),
            ([40, -3, 2], Vec::new()),
        ]);
        let bytes = encode(&shapes).unwrap();
        assert_eq!(u32_at(&bytes, 12), 2, "the slab and the empty shape");
        // Empty shape sorts first: count 0; then the slab: count 1 and 6 bytes.
        assert_eq!(&bytes[16..18], &[0, 0]);
        assert_eq!(&bytes[18..20], &[1, 0]);
        assert_eq!(&bytes[20..26], &[0, 0, 0, 16, 8, 16]);
        assert_eq!(u32_at(&bytes, 26), 2, "two sections");
        // First section [0, 0, 0]: both slab cells.
        assert_eq!(&bytes[30..42], &[0u8; 12]);
        assert_eq!(&bytes[42..44], &[2, 0]);
        assert_eq!(&bytes[44..46], &[0, 0]);
        assert_eq!(u32_at(&bytes, 46), 1);
        assert_eq!(&bytes[50..52], &[5, 0]);
        assert_eq!(u32_at(&bytes, 52), 1);
    }

    #[test]
    fn negative_and_reaching_boxes_round_trip_as_signed_bytes() {
        let shapes = BTreeMap::from([([0, 0, 0], vec![[0, -16, 0, 16, 32, 16]])]);
        let bytes = encode(&shapes).unwrap();
        assert_eq!(bytes[18..24], [0, 0xF0, 0, 16, 32, 16]);
    }

    #[test]
    fn a_box_past_the_neighbouring_cell_is_refused() {
        let shapes = BTreeMap::from([([0, 0, 0], vec![[0, 0, 0, 16, 33, 16]])]);
        assert!(encode(&shapes).is_err());
        let shapes = BTreeMap::from([([0, 0, 0], vec![[4, 0, 0, 4, 16, 16]])]);
        assert!(encode(&shapes).is_err());
    }
}
