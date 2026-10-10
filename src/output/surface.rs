//! Versioned binary spatial table for the map's exact visible geometry.

use crate::bsp::texcoord::BlockTexCoord;
use crate::voxel::fragments::{Fragment, MAX_VERTICES, QUANTUM};
use crate::voxel::grid::IVec3;
use crate::voxel::surface::SourceProvenance;
use anyhow::{Result, ensure};
use std::collections::{BTreeMap, HashMap};

pub const MAGIC: [u8; 8] = *b"S2FACE\0\0";
pub const VERSION: u32 = 5;
/// The light-region ID of a fragment with no lightmap.
pub const NO_LIGHT: u32 = u32::MAX;

/// Flag bit: the owner cell holds an `src2mc:surface` block.
const OWNED: u8 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct MaterialId(pub u32);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    pub max_uv_regions: u32,
    pub max_sections: u32,
    pub max_faces: u32,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_uv_regions: crate::output::limits::MAX_UV_REGIONS_PER_MAP as u32,
            max_sections: crate::output::limits::MAX_SECTIONS_PER_MAP as u32,
            max_faces: crate::output::limits::MAX_FACES_PER_MAP as u32,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct EncodedFace {
    pub cell: IVec3,
    /// Offset from `cell` to the owner block, `None` when unowned.
    pub owner: Option<IVec3>,
    pub material: MaterialId,
    pub uv: BlockTexCoord,
    /// Where the fragment's lightmap is on the map's light pages; `None`
    /// when it has none.
    pub light: Option<LightRegion>,
    /// A blended displacement's blend, `a * x + b * y + c * z + d` in
    /// map-local block space; `None` elsewhere.
    pub blend: Option<[f64; 4]>,
    pub provenance: SourceProvenance,
    pub vertices: Vec<[u16; 3]>,
}

/// A fragment's lightmap on the map's light pages: the page, and the affine
/// map from a map-local block position to page coordinates, 0 to 1 across.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LightRegion {
    pub page: u32,
    pub st: BlockTexCoord,
    /// For a bump-mapped face, how far right of the flat lightmap, as a
    /// fraction of the page width, each of its three bump lightmaps starts:
    /// bump lightmap `k` (1 to 3) is at `s + k * bump`. Zero for a flat face.
    pub bump: f64,
}

impl EncodedFace {
    pub fn from_fragment(fragment: &Fragment, material: MaterialId) -> Self {
        Self {
            cell: fragment.cell,
            owner: fragment.owner,
            material,
            uv: fragment.source.uv,
            light: None,
            blend: fragment.source.blend,
            provenance: fragment.source.provenance,
            vertices: fragment.vertices.clone(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
struct UvKey([u64; 8]);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
struct LightKey(u32, UvKey, u64);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
struct BlendKey([u64; 4]);

fn blend_key(blend: [f64; 4]) -> Result<BlendKey> {
    let mut out = [0u64; 4];
    for (slot, value) in out.iter_mut().zip(blend) {
        ensure!(value.is_finite(), "non-finite blend region");
        *slot = (value + 0.0).to_bits();
    }
    Ok(BlendKey(out))
}

/// Encode fragments into sparse 16³ section buckets. Input order is
/// deliberately irrelevant to the output bytes.
pub fn encode(faces: impl IntoIterator<Item = EncodedFace>, limits: Limits) -> Result<Vec<u8>> {
    #[allow(clippy::type_complexity)]
    let mut buckets: BTreeMap<IVec3, Vec<(EncodedFace, UvKey, Option<LightKey>, Option<BlendKey>)>> = BTreeMap::new();
    let mut uv_keys = Vec::new();
    let mut light_keys = Vec::new();
    let mut blend_keys = Vec::new();
    let mut face_count = 0u32;

    for face in faces {
        ensure!(face_count < limits.max_faces, "surface face limit exceeded");
        let uv = uv_key(face.uv)?;
        let light = face
            .light
            .map(|region| {
                ensure!(
                    region.bump.is_finite() && region.bump >= 0.0,
                    "bump lightmap stride must be finite and not negative"
                );
                uv_key(region.st).map(|key| LightKey(region.page, key, (region.bump + 0.0).to_bits()))
            })
            .transpose()?;
        let blend = face.blend.map(blend_key).transpose()?;
        validate_vertices(&face.vertices)?;
        uv_keys.push(uv);
        light_keys.extend(light);
        blend_keys.extend(blend);
        buckets
            .entry(section_of(face.cell))
            .or_default()
            .push((face, uv, light, blend));
        face_count += 1;
    }
    ensure!(
        buckets.len() <= limits.max_sections as usize,
        "surface section limit exceeded"
    );

    uv_keys.sort_unstable();
    uv_keys.dedup();
    ensure!(
        uv_keys.len() <= limits.max_uv_regions as usize,
        "UV-region limit exceeded"
    );
    let uv_ids: HashMap<_, _> = uv_keys
        .iter()
        .copied()
        .enumerate()
        .map(|(index, key)| (key, index as u32))
        .collect();
    light_keys.sort_unstable();
    light_keys.dedup();
    ensure!(
        light_keys.len() <= limits.max_uv_regions as usize,
        "light-region limit exceeded"
    );
    let light_ids: HashMap<_, _> = light_keys
        .iter()
        .copied()
        .enumerate()
        .map(|(index, key)| (key, index as u32))
        .collect();

    blend_keys.sort_unstable();
    blend_keys.dedup();
    ensure!(
        blend_keys.len() <= limits.max_uv_regions as usize,
        "blend-region limit exceeded"
    );
    let blend_ids: HashMap<_, _> = blend_keys
        .iter()
        .copied()
        .enumerate()
        .map(|(index, key)| (key, index as u32))
        .collect();

    let mut out = Vec::new();
    out.extend_from_slice(&MAGIC);
    put_u32(&mut out, VERSION);
    put_u32(&mut out, uv_keys.len() as u32);
    put_u32(&mut out, light_keys.len() as u32);
    put_u32(&mut out, blend_keys.len() as u32);
    put_u32(&mut out, buckets.len() as u32);
    put_u32(&mut out, face_count);
    for uv in &uv_keys {
        for value in uv.0 {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    for LightKey(page, st, bump) in &light_keys {
        put_u32(&mut out, *page);
        for value in st.0 {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&bump.to_le_bytes());
    }
    for BlendKey(values) in &blend_keys {
        for value in values {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    for (section, mut records) in buckets {
        let mut keyed = Vec::with_capacity(records.len());
        for (face, uv, light, blend) in records.drain(..) {
            let (primary, secondary) = provenance_values(face.provenance)?;
            let key = (
                local_index(face.cell),
                provenance_tag(face.provenance),
                primary,
                secondary,
            );
            keyed.push((key, face, uv, light, blend));
        }
        keyed.sort_by_key(|(key, _, _, _, _)| *key);
        for pair in keyed.windows(2) {
            ensure!(
                pair[0].0 != pair[1].0,
                "two surface fragments share cell and provenance {:?}",
                pair[0].1.provenance
            );
        }
        for coordinate in section {
            put_i32(&mut out, coordinate);
        }
        put_u32(&mut out, keyed.len() as u32);
        for ((local, tag, primary, secondary), face, uv, light, blend) in keyed {
            put_u16(&mut out, local);
            out.push(flags(face.owner)?);
            out.push(tag);
            put_u32(&mut out, face.material.0);
            put_u32(&mut out, uv_ids[&uv]);
            put_u32(&mut out, light.map_or(NO_LIGHT, |key| light_ids[&key]));
            put_u32(&mut out, blend.map_or(NO_LIGHT, |key| blend_ids[&key]));
            put_u32(&mut out, primary);
            put_u32(&mut out, secondary);
            out.push(face.vertices.len() as u8);
            for vertex in &face.vertices {
                for coordinate in vertex {
                    put_u16(&mut out, *coordinate);
                }
            }
        }
    }
    Ok(out)
}

fn uv_key(uv: BlockTexCoord) -> Result<UvKey> {
    let values = [uv.u, uv.v].concat();
    let mut bits = [0; 8];
    for (index, value) in values.into_iter().enumerate() {
        ensure!(value.is_finite(), "non-finite UV transform");
        bits[index] = if value == 0.0 {
            0.0f64.to_bits()
        } else {
            value.to_bits()
        };
    }
    Ok(UvKey(bits))
}

pub(crate) fn section_of(cell: IVec3) -> IVec3 {
    [cell[0] >> 4, cell[1] >> 4, cell[2] >> 4]
}

fn local_index(cell: IVec3) -> u16 {
    ((cell[1] & 15) << 8 | (cell[2] & 15) << 4 | (cell[0] & 15)) as u16
}

/// Bit 0 owned, then each owner-offset axis plus one in two bits.
fn flags(owner: Option<IVec3>) -> Result<u8> {
    let offset = owner.unwrap_or([0, 0, 0]);
    let mut out = if owner.is_some() { OWNED } else { 0 };
    for (axis, value) in offset.into_iter().enumerate() {
        ensure!(
            (-1..=1).contains(&value),
            "owner offset {offset:?} is not a neighbour"
        );
        out |= ((value + 1) as u8) << (1 + 2 * axis);
    }
    Ok(out)
}

fn validate_vertices(vertices: &[[u16; 3]]) -> Result<()> {
    ensure!(
        (3..=MAX_VERTICES).contains(&vertices.len()),
        "surface fragment has {} vertices",
        vertices.len()
    );
    ensure!(
        vertices.iter().flatten().all(|&c| f64::from(c) <= QUANTUM),
        "surface fragment vertex is outside its cell"
    );
    Ok(())
}

fn provenance_tag(provenance: SourceProvenance) -> u8 {
    match provenance {
        SourceProvenance::Brush { .. } => 0,
        SourceProvenance::Displacement { .. } => 1,
        SourceProvenance::Face { .. } => 2,
    }
}

fn provenance_values(provenance: SourceProvenance) -> Result<(u32, u32)> {
    let (a, b) = match provenance {
        SourceProvenance::Brush { brush, side } => (brush, side),
        SourceProvenance::Displacement {
            displacement,
            triangle,
        } => (displacement, triangle),
        SourceProvenance::Face { face, piece } => (face, piece),
    };
    Ok((u32::try_from(a)?, u32::try_from(b)?))
}

fn put_u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn face(cell: IVec3, uv_offset: f64) -> EncodedFace {
        EncodedFace {
            cell,
            owner: Some([0, 0, 0]),
            material: MaterialId(7),
            uv: BlockTexCoord {
                u: [1.0, 0.0, 0.0, uv_offset],
                v: [0.0, 0.0, 1.0, 0.0],
            },
            light: None,
            blend: None,
            provenance: SourceProvenance::Face { face: 12, piece: 0 },
            vertices: vec![
                [0, 4096, 0],
                [0, 4096, 4096],
                [4096, 4096, 4096],
                [4096, 4096, 0],
            ],
        }
    }

    #[test]
    fn bytes_are_independent_of_input_order() {
        let a = face([-1, 16, 31], 2.0);
        let b = face([16, -1, 0], 1.0);
        assert_eq!(
            encode([a.clone(), b.clone()], Limits::default()).unwrap(),
            encode([b, a], Limits::default()).unwrap()
        );
    }

    #[test]
    fn equal_uvs_share_one_region_and_negative_zero_is_canonical() {
        let a = face([0, 0, 0], -0.0);
        let b = face([1, 0, 0], 0.0);
        let bytes = encode([a, b], Limits::default()).unwrap();
        assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 0);
    }

    #[test]
    fn a_record_is_its_fixed_part_then_its_vertices() {
        let bytes = encode([face([1, 2, 3], 0.0)], Limits::default()).unwrap();
        // Header 32, one UV region 64, section coordinates 12 and count 4.
        let record = &bytes[32 + 64 + 16..];
        assert_eq!(
            u16::from_le_bytes([record[0], record[1]]),
            2 << 8 | 3 << 4 | 1
        );
        assert_eq!(record[2], OWNED | 0b010101 << 1);
        assert_eq!(record[3], 2);
        assert_eq!(
            u32::from_le_bytes(record[12..16].try_into().unwrap()),
            NO_LIGHT
        );
        assert_eq!(
            u32::from_le_bytes(record[16..20].try_into().unwrap()),
            NO_LIGHT
        );
        assert_eq!(record[28], 4);
        assert_eq!(record.len(), 29 + 4 * 6);
        assert_eq!(u16::from_le_bytes([record[31], record[32]]), 4096);
    }

    #[test]
    fn rejects_nonfinite_uv_and_bad_polygons() {
        let invalid_uv = face([0, 0, 0], f64::NAN);
        assert!(encode([invalid_uv], Limits::default()).is_err());
        let mut outside = face([0, 0, 0], 0.0);
        outside.vertices[0][0] = 4097;
        assert!(encode([outside], Limits::default()).is_err());
        let mut line = face([0, 0, 0], 0.0);
        line.vertices.truncate(2);
        assert!(encode([line], Limits::default()).is_err());
    }

    #[test]
    fn owner_offsets_pack_per_axis_and_unowned_is_centred() {
        assert_eq!(flags(None).unwrap(), 0b0010_1010);
        assert_eq!(flags(Some([0, -1, 0])).unwrap(), 0b0010_0011);
        assert_eq!(flags(Some([1, 0, -1])).unwrap(), 0b0000_1101);
        assert!(flags(Some([0, 2, 0])).is_err());
    }

    #[test]
    fn light_regions_are_a_table_of_their_own() {
        let mut lit = face([0, 0, 0], 0.0);
        lit.light = Some(LightRegion {
            page: 2,
            st: BlockTexCoord {
                u: [0.5, 0.0, 0.0, 0.25],
                v: [0.0, 0.0, 0.5, 0.75],
            },
            bump: 0.125,
        });
        let mut same = lit.clone();
        same.cell = [1, 0, 0];
        let bytes = encode([lit, same, face([2, 0, 0], 0.0)], Limits::default()).unwrap();
        assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 1);
        let region = &bytes[32 + 64..32 + 64 + 76];
        assert_eq!(u32::from_le_bytes(region[0..4].try_into().unwrap()), 2);
        assert_eq!(f64::from_le_bytes(region[4..12].try_into().unwrap()), 0.5);
        assert_eq!(f64::from_le_bytes(region[68..76].try_into().unwrap()), 0.125);
        let first = &bytes[32 + 64 + 76 + 16..];
        assert_eq!(u32::from_le_bytes(first[12..16].try_into().unwrap()), 0);
    }

    #[test]
    fn blend_regions_are_a_table_of_their_own() {
        let mut blended = face([0, 0, 0], 0.0);
        blended.blend = Some([0.25, 0.0, -0.5, 1.0]);
        let bytes = encode([blended, face([1, 0, 0], 0.0)], Limits::default()).unwrap();
        assert_eq!(u32::from_le_bytes(bytes[20..24].try_into().unwrap()), 1);
        let region = &bytes[32 + 64..32 + 64 + 32];
        assert_eq!(f64::from_le_bytes(region[0..8].try_into().unwrap()), 0.25);
        assert_eq!(f64::from_le_bytes(region[16..24].try_into().unwrap()), -0.5);
        let first = &bytes[32 + 64 + 32 + 16..];
        assert_eq!(u32::from_le_bytes(first[16..20].try_into().unwrap()), 0);
        let second = &first[29 + 4 * 6..];
        assert_eq!(u32::from_le_bytes(second[16..20].try_into().unwrap()), NO_LIGHT);
    }

    #[test]
    fn rejects_two_fragments_with_one_key() {
        let a = face([0, 0, 0], 0.0);
        let mut b = face([0, 0, 0], 1.0);
        b.material = MaterialId(8);
        assert!(encode([a, b], Limits::default()).is_err());
    }

    #[test]
    fn limits_are_checked_before_output() {
        let limits = Limits {
            max_faces: 1,
            ..Limits::default()
        };
        assert!(encode([face([0, 0, 0], 0.0), face([1, 0, 0], 0.0)], limits).is_err());
    }
}
