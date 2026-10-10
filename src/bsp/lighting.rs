//! Source's baked light: the lightmaps vrad writes for brush faces and
//! displacements, and the per-vertex light it writes for static props.
//!
//! HDR maps (all of INFRA's and Portal 2's) carry only HDR light: the LDR
//! lighting lump is empty, and the face records that point into the HDR one
//! are a second face lump, `LUMP_FACES_HDR`. A luxel is `ColorRGBExp32`, three
//! bytes and a signed exponent, `value * 2^exponent / 255` in linear light. A
//! face's lightmap is `(size + 1)` luxels each way, one block per light style;
//! the first style is the static light.

use crate::bsp::lumps;
use anyhow::Result;

const LUMP_LIGHTING: usize = 8;
const LUMP_FACES: usize = 7;
const LUMP_LIGHTING_HDR: usize = 53;
const LUMP_FACES_HDR: usize = 58;
const LUMP_TEXINFO: usize = 6;
/// `texinfo_t`: two 4x2 float vector pairs, flags, texdata.
const TEXINFO_BYTES: usize = 72;
/// `SURF_BUMPLIGHT`: vrad wrote three more lightmaps per style, one per bump basis direction.
const SURF_BUMPLIGHT: i32 = 0x800;
const FACE_BYTES: usize = 56;

/// Where one face's lightmap is, as its face record says.
#[derive(Debug, Clone, Copy)]
pub struct FaceLight {
    pub offset: i32,
    pub mins: [i32; 2],
    pub size: [i32; 2],
    pub styles: [u8; 4],
    /// The face's texinfo has `SURF_BUMPLIGHT`: each style holds four lightmaps,
    /// the flat one and one per bump basis direction.
    pub bumped: bool,
}

/// One face's static lightmap, row by row, linear light.
#[derive(Debug, Clone)]
pub struct Lightmap {
    pub width: usize,
    pub height: usize,
    pub luxels: Vec<[f32; 3]>,
}

#[derive(Debug, Clone, Default)]
pub struct BakedLight {
    /// Whether this is the HDR light, which is what Source draws when the map
    /// has it.
    pub hdr: bool,
    faces: Vec<FaceLight>,
    data: Vec<u8>,
}

impl BakedLight {
    /// Reads the face light records and the lighting lump, HDR when present.
    pub fn read(data: &[u8]) -> Result<BakedLight> {
        let lump = |index: usize| -> Result<&[u8]> {
            let entry = lumps::lump_entry(data, index)?;
            Ok(data.get(entry.range()).unwrap_or(&[]))
        };
        let hdr_light = lump(LUMP_LIGHTING_HDR)?;
        let hdr = !hdr_light.is_empty();
        let (light, faces) = if hdr {
            let hdr_faces = lump(LUMP_FACES_HDR)?;
            // An HDR map without its own face lump points the LDR face records
            // at the HDR light, as the engine falls back.
            (
                hdr_light,
                if hdr_faces.is_empty() {
                    lump(LUMP_FACES)?
                } else {
                    hdr_faces
                },
            )
        } else {
            (lump(LUMP_LIGHTING)?, lump(LUMP_FACES)?)
        };
        let texinfo = lump(LUMP_TEXINFO)?;
        let texinfo_flags = |index: i16| -> i32 {
            usize::try_from(index)
                .ok()
                .and_then(|i| texinfo.get(i * TEXINFO_BYTES + 64..i * TEXINFO_BYTES + 68))
                .map_or(0, |b| i32::from_le_bytes(b.try_into().unwrap()))
        };
        let faces = faces
            .as_chunks::<FACE_BYTES>()
            .0
            .iter()
            .map(|face| {
                let i32_at = |at: usize| i32::from_le_bytes(face[at..at + 4].try_into().unwrap());
                let texinfo_index = i16::from_le_bytes(face[10..12].try_into().unwrap());
                FaceLight {
                    bumped: texinfo_flags(texinfo_index) & SURF_BUMPLIGHT != 0,
                    styles: face[16..20].try_into().unwrap(),
                    offset: i32_at(20),
                    mins: [i32_at(28), i32_at(32)],
                    size: [i32_at(36), i32_at(40)],
                }
            })
            .collect();
        Ok(BakedLight {
            hdr,
            faces,
            data: light.to_vec(),
        })
    }

    pub fn face(&self, face: usize) -> Option<FaceLight> {
        self.faces.get(face).copied()
    }

    /// Face `face`'s static lightmap; `None` when it has none. A bump-mapped
    /// face stores four lightmaps per style, the first of them the one a flat
    /// surface draws, which is the one read.
    pub fn lightmap(&self, face: usize) -> Option<Lightmap> {
        let light = self.face(face)?;
        if light.offset < 0 || light.styles[0] == 255 {
            return None;
        }
        let width = usize::try_from(light.size[0]).ok()? + 1;
        let height = usize::try_from(light.size[1]).ok()? + 1;
        // Faces carry at most 32 luxels of 16 units per axis in practice; a
        // corrupt size must not drive a huge allocation.
        if width > 1024 || height > 1024 {
            return None;
        }
        let start = usize::try_from(light.offset).ok()?;
        let count = width * height;
        let bytes = self.data.get(start..start + count * 4)?;
        let luxels = bytes
            .as_chunks::<4>()
            .0
            .iter()
            .map(|&luxel| decode(luxel))
            .collect();
        Some(Lightmap {
            width,
            height,
            luxels,
        })
    }
}

/// A face's static lightmap as vrad stored it: `ColorRGBExp32` luxels, row
/// by row, `width * height` of them.
#[derive(Debug, Clone, Copy)]
pub struct RawLightmap<'a> {
    pub width: usize,
    pub height: usize,
    pub luxels: &'a [u8],
}

impl BakedLight {
    /// Face `face`'s static lightmap without decoding it; `None` when it has
    /// none. As [`BakedLight::lightmap`], the flat one of a bump-mapped face.
    pub fn raw_lightmap(&self, face: usize) -> Option<RawLightmap<'_>> {
        let light = self.face(face)?;
        if light.offset < 0 || light.styles[0] == 255 {
            return None;
        }
        let width = usize::try_from(light.size[0]).ok()? + 1;
        let height = usize::try_from(light.size[1]).ok()? + 1;
        if width > 1024 || height > 1024 {
            return None;
        }
        let start = usize::try_from(light.offset).ok()?;
        let luxels = self.data.get(start..start + width * height * 4)?;
        Some(RawLightmap {
            width,
            height,
            luxels,
        })
    }

    /// The three bump lightmaps of a bump-mapped face's first style, which
    /// follow its flat one (`R_BuildLightMap` steps by four maps per style);
    /// `None` for a flat face, or when the lump is too short for them.
    pub fn raw_bump_lightmaps(&self, face: usize) -> Option<[&[u8]; 3]> {
        let light = self.face(face)?;
        if !light.bumped {
            return None;
        }
        let flat = self.raw_lightmap(face)?;
        let size = flat.width * flat.height * 4;
        let start = usize::try_from(light.offset).ok()? + size;
        let all = self.data.get(start..start + 3 * size)?;
        Some([&all[..size], &all[size..2 * size], &all[2 * size..]])
    }
}

const LUMP_LEAF_AMBIENT_INDEX_HDR: usize = 51;
const LUMP_LEAF_AMBIENT_INDEX: usize = 52;
const LUMP_LEAF_AMBIENT_LIGHTING_HDR: usize = 55;
const LUMP_LEAF_AMBIENT_LIGHTING: usize = 56;
/// `dleafambientlighting_t`: a `CompressedLightCube` and the sample's place
/// in its leaf's box, three bytes and a pad.
const AMBIENT_SAMPLE_BYTES: usize = 28;
const CUBE_BYTES: usize = 24;

/// One of vrad's ambient light samples: the light arriving from each of the
/// six axis directions, +X, -X, +Y, -Y, +Z, -Z in Source axes, linear.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AmbientSample {
    /// Source units.
    pub position: [f64; 3],
    pub cube: [[f32; 3]; 6],
}

/// Every leaf's ambient samples, in BSP leaf order.
#[derive(Debug, Clone, Default)]
pub struct Ambient {
    /// Per leaf, its first sample and how many it has.
    pub leaves: Vec<(u32, u32)>,
    pub samples: Vec<AmbientSample>,
}

impl Ambient {
    /// Reads the samples as noclip.website's `BSPFile.ts` does, for the three
    /// layouts Source has used: per-leaf sample lists in the leaf ambient
    /// lumps, one cube per leaf in the ambient lighting lump alone, and the
    /// cube inside a version-0 leaf. The HDR lumps when the map lights in HDR.
    /// Each lone cube is placed at its leaf's centre.
    pub fn read(data: &[u8], leaves: &[super::rawleaves::RawLeaf], hdr: bool) -> Result<Ambient> {
        let lump = |index: usize| -> Result<&[u8]> {
            let entry = lumps::lump_entry(data, index)?;
            Ok(data.get(entry.range()).unwrap_or(&[]))
        };
        let (index_lump, lighting_lump) = if hdr {
            (LUMP_LEAF_AMBIENT_INDEX_HDR, LUMP_LEAF_AMBIENT_LIGHTING_HDR)
        } else {
            (LUMP_LEAF_AMBIENT_INDEX, LUMP_LEAF_AMBIENT_LIGHTING)
        };
        let mut index = lump(index_lump)?;
        let mut lighting = lump(lighting_lump)?;
        if lighting.is_empty() && hdr {
            index = lump(LUMP_LEAF_AMBIENT_INDEX)?;
            lighting = lump(LUMP_LEAF_AMBIENT_LIGHTING)?;
        }
        let centre = |leaf: &super::rawleaves::RawLeaf| {
            std::array::from_fn(|axis| {
                (f64::from(leaf.mins[axis]) + f64::from(leaf.maxs[axis])) * 0.5
            })
        };
        let mut ambient = Ambient::default();
        if !index.is_empty() && !lighting.is_empty() {
            let samples = lighting.as_chunks::<AMBIENT_SAMPLE_BYTES>().0;
            for (leaf_index, leaf) in leaves.iter().enumerate() {
                let Some(entry) = index.get(leaf_index * 4..leaf_index * 4 + 4) else {
                    ambient.leaves.push((0, 0));
                    continue;
                };
                let count = usize::from(u16::from_le_bytes([entry[0], entry[1]]));
                let first = usize::from(u16::from_le_bytes([entry[2], entry[3]]));
                let start = ambient.samples.len() as u32;
                for sample in samples.iter().skip(first).take(count) {
                    let position = std::array::from_fn(|axis| {
                        let fraction = f64::from(sample[CUBE_BYTES + axis]) / 255.0;
                        let (min, max) = (f64::from(leaf.mins[axis]), f64::from(leaf.maxs[axis]));
                        min + (max - min) * fraction
                    });
                    ambient.samples.push(AmbientSample {
                        position,
                        cube: decode_cube(&sample[..CUBE_BYTES]),
                    });
                }
                ambient
                    .leaves
                    .push((start, ambient.samples.len() as u32 - start));
            }
        } else if !lighting.is_empty() {
            let cubes = lighting.as_chunks::<CUBE_BYTES>().0;
            for (leaf_index, leaf) in leaves.iter().enumerate() {
                let start = ambient.samples.len() as u32;
                if let Some(cube) = cubes.get(leaf_index) {
                    ambient.samples.push(AmbientSample {
                        position: centre(leaf),
                        cube: decode_cube(cube),
                    });
                }
                ambient
                    .leaves
                    .push((start, ambient.samples.len() as u32 - start));
            }
        } else {
            for leaf in leaves {
                let start = ambient.samples.len() as u32;
                if let Some(cube) = leaf.cube {
                    ambient.samples.push(AmbientSample {
                        position: centre(leaf),
                        cube: decode_cube(cube.as_flattened()),
                    });
                }
                ambient
                    .leaves
                    .push((start, ambient.samples.len() as u32 - start));
            }
        }
        Ok(ambient)
    }
}

/// A `CompressedLightCube`. Its colours carry a factor of 255 a lightmap's do
/// not, so they decode without the division (noclip.website: "Game seems to
/// accidentally include an extra factor of 255.0").
fn decode_cube(bytes: &[u8]) -> [[f32; 3]; 6] {
    std::array::from_fn(|side| {
        let c = &bytes[side * 4..side * 4 + 4];
        let scale = 2f32.powi(i32::from(c[3] as i8));
        [
            f32::from(c[0]) * scale,
            f32::from(c[1]) * scale,
            f32::from(c[2]) * scale,
        ]
    })
}

/// `ColorRGBExp32` to linear light.
pub fn decode(luxel: [u8; 4]) -> [f32; 3] {
    let scale = 2f32.powi(i32::from(luxel[3] as i8)) / 255.0;
    [
        f32::from(luxel[0]) * scale,
        f32::from(luxel[1]) * scale,
        f32::from(luxel[2]) * scale,
    ]
}

/// A static prop's per-vertex light, `sp_<index>.vhv` (`sp_hdr_` for HDR), as
/// vrad writes it: per mesh of each LOD that has vertices, one colour per
/// hardware vertex, in the order the `.vtx` strip groups list them.
#[derive(Debug, Clone)]
pub struct VertexLight {
    /// LOD 0's meshes' colours, concatenated in mesh order, linear light.
    pub colors: Vec<[f32; 3]>,
}

impl VertexLight {
    /// Parses a `.vhv`; `None` for any other layout than one colour a vertex.
    pub fn parse(data: &[u8]) -> Option<VertexLight> {
        // BGRA, gamma space, half range: Source's shader takes `(color * 2)`
        // to linear.
        let linear = |value: u8| (f32::from(value) / 255.0 * 2.0).powf(2.2);
        Some(VertexLight {
            colors: Self::parse_raw(data)?
                .into_iter()
                .map(|[r, g, b]| [linear(r), linear(g), linear(b)])
                .collect(),
        })
    }

    /// LOD 0's colours as stored, RGB bytes in gamma space and half range.
    pub fn parse_raw(data: &[u8]) -> Option<Vec<[u8; 3]>> {
        let u32_at = |at: usize| -> Option<u32> {
            Some(u32::from_le_bytes(data.get(at..at + 4)?.try_into().ok()?))
        };
        if u32_at(0)? != 2 {
            return None;
        }
        // Colour only (vertex flag 4), four bytes a vertex.
        const COLOR: u32 = 0x4;
        if u32_at(8)? != COLOR || u32_at(12)? != 4 {
            return None;
        }
        let total = usize::try_from(u32_at(16)?).ok()?;
        let meshes = usize::try_from(u32_at(20)?).ok()?;
        if total > 4_000_000 || meshes > 65_536 {
            return None;
        }
        let mut colors = Vec::with_capacity(total);
        let mut read = 0;
        for mesh in 0..meshes {
            let header = 0x28 + mesh * 0x1C;
            let lod = u32_at(header)?;
            let count = usize::try_from(u32_at(header + 4)?).ok()?;
            let offset = usize::try_from(u32_at(header + 8)?).ok()?;
            let bytes = data.get(offset..offset + count * 4)?;
            read += count;
            if lod != 0 {
                continue;
            }
            colors.extend(
                bytes
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .map(|bgra| [bgra[2], bgra[1], bgra[0]]),
            );
        }
        (read == total).then_some(colors)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn luxels_decode_with_a_signed_exponent() {
        assert_eq!(decode([255, 0, 0, 0]), [1.0, 0.0, 0.0]);
        assert_eq!(decode([255, 255, 255, 1]), [2.0, 2.0, 2.0]);
        let dim = decode([255, 0, 0, 0xFF]);
        assert!((dim[0] - 0.5).abs() < 1e-6);
    }

    #[test]
    fn a_vhv_reads_bgra_as_linear_light() {
        let mut data = vec![0u8; 0x28 + 0x1C];
        data[0..4].copy_from_slice(&2u32.to_le_bytes());
        data[8..12].copy_from_slice(&4u32.to_le_bytes());
        data[12..16].copy_from_slice(&4u32.to_le_bytes());
        data[16..20].copy_from_slice(&1u32.to_le_bytes());
        data[20..24].copy_from_slice(&1u32.to_le_bytes());
        let offset = data.len() as u32;
        data[0x28 + 4..0x28 + 8].copy_from_slice(&1u32.to_le_bytes());
        data[0x28 + 8..0x28 + 12].copy_from_slice(&offset.to_le_bytes());
        data.extend_from_slice(&[0, 0, 128, 255]);
        let light = VertexLight::parse(&data).unwrap();
        assert_eq!(light.colors.len(), 1);
        assert!((light.colors[0][0] - (128.0f32 / 255.0 * 2.0).powf(2.2)).abs() < 1e-5);
        assert_eq!(light.colors[0][2], 0.0);
    }
}
