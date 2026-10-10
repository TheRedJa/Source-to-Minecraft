//! The cubemaps a map's materials reflect (format.md section 25).

use crate::source::cubemap::Cubemap;
use anyhow::{Result, ensure};

pub const MAGIC: [u8; 8] = *b"S2CUBE\0\0";
pub const VERSION: u32 = 1;
/// The largest face side written.
pub const MAX_SIZE: u32 = 512;

/// Magic, version, count, then per cubemap its face side and six faces in
/// Direct3D order, row by row from the top, linear RGB as half floats.
pub fn encode(cubemaps: &[Cubemap]) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&u32::try_from(cubemaps.len())?.to_le_bytes());
    for cube in cubemaps {
        ensure!(
            cube.size > 0 && cube.size <= MAX_SIZE,
            "cubemap face side {} out of range",
            cube.size
        );
        out.extend_from_slice(&cube.size.to_le_bytes());
        for face in &cube.faces {
            ensure!(
                face.len() == (cube.size * cube.size) as usize,
                "cubemap face has the wrong texel count"
            );
            for texel in face {
                for value in texel {
                    let value = if value.is_finite() { value.clamp(0.0, 65504.0) } else { 0.0 };
                    out.extend_from_slice(&half::f16::from_f32(value).to_le_bytes());
                }
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_each_face_as_half_floats() {
        let cube = Cubemap {
            size: 1,
            faces: std::array::from_fn(|i| vec![[i as f32, 0.5, f32::NAN]]),
        };
        let bytes = encode(&[cube]).unwrap();
        assert_eq!(&bytes[..8], b"S2CUBE\0\0");
        assert_eq!(bytes.len(), 16 + 4 + 6 * 6);
        let at = |i: usize| half::f16::from_le_bytes([bytes[i], bytes[i + 1]]).to_f32();
        // Face 2's red, then its green; NaN is written as 0.
        assert_eq!(at(20 + 12), 2.0);
        assert_eq!(at(20 + 14), 0.5);
        assert_eq!(at(20 + 16), 0.0);
    }
}
