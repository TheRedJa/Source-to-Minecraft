//! The world lights the engine lights models with (format.md section 27).

use crate::bsp::worldlights::WorldLight;
use crate::voxel::transform::Transform;
use anyhow::{Result, ensure};

pub const MAGIC: [u8; 8] = *b"S2WLIT\0\0";
pub const VERSION: u32 = 1;
/// Bytes per light: type, flags, style (u16), then 16 floats.
pub const RECORD_BYTES: usize = 4 + 16 * 4;

/// Magic, version, count, then one record per light. Positions, directions,
/// radius and attenuation are in map-local Minecraft block space, like the
/// ambient samples of section 22.
pub fn encode(lights: &[WorldLight], transform: &Transform) -> Result<Vec<u8>> {
    use crate::geom::Vec3;
    // vrad treats any nonzero style as switched, so a negative one is skipped, not made always on.
    let lights: Vec<&WorldLight> = lights.iter().filter(|light| light.style >= 0).collect();
    let b = transform.units_per_block() as f32;
    let mut out = Vec::with_capacity(16 + lights.len() * RECORD_BYTES);
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&u32::try_from(lights.len())?.to_le_bytes());
    for light in lights.iter().copied() {
        let [x, y, z] = light.origin;
        let origin = transform.to_block_space(Vec3::new(x as f64, y as f64, z as f64));
        let [x, y, z] = light.normal;
        let normal = Vec3::new(x as f64, y as f64, z as f64);
        let direction = if normal.length() > 1e-6 {
            transform.transform_direction(normal)
        } else {
            Vec3::ZERO
        };
        out.push(light.kind);
        out.push((light.flags & 0xff) as u8);
        out.extend_from_slice(&(light.style.min(0xffff) as u16).to_le_bytes());
        let values = [
            origin.x as f32,
            origin.y as f32,
            origin.z as f32,
            light.intensity[0],
            light.intensity[1],
            light.intensity[2],
            direction.x as f32,
            direction.y as f32,
            direction.z as f32,
            light.stopdot,
            light.stopdot2,
            light.exponent,
            light.radius / b,
            light.constant_attn,
            light.linear_attn * b,
            light.quadratic_attn * b * b,
        ];
        for value in values {
            ensure!(value.is_finite(), "non-finite world light value");
            out.extend_from_slice(&(if value == 0.0 { 0.0 } else { value }).to_le_bytes());
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_one_record_per_light_in_block_units() {
        let transform = Transform::new(&crate::config::Config::default(), crate::geom::Aabb { min: crate::geom::Vec3::ZERO, max: crate::geom::Vec3::ZERO });
        let light = WorldLight {
            origin: [320.0, 0.0, 64.0],
            intensity: [1.0, 2.0, 3.0],
            normal: [0.0, 0.0, -1.0],
            kind: 2,
            style: 33,
            flags: 3,
            stopdot: 0.9,
            stopdot2: 0.5,
            exponent: 1.0,
            radius: 320.0,
            constant_attn: 0.0,
            linear_attn: 0.0,
            quadratic_attn: 1.0,
        };
        let bytes = encode(&[light], &transform).unwrap();
        assert_eq!(bytes.len(), 16 + RECORD_BYTES);
        assert_eq!(&bytes[..8], &MAGIC);
        assert_eq!(&bytes[16..20], &[2, 3, 33, 0]);
        let f = |i: usize| f32::from_le_bytes(bytes[20 + i * 4..24 + i * 4].try_into().unwrap());
        let upb = transform.units_per_block() as f32;
        let origin = transform.to_block_space(crate::geom::Vec3::new(320.0, 0.0, 64.0));
        assert!((f(0) - origin.x as f32).abs() < 1e-5);
        assert!((f(1) - origin.y as f32).abs() < 1e-5);
        assert!((f(7) + 1.0).abs() < 1e-5, "Source down is Minecraft down");
        assert!((f(12) - 320.0 / upb).abs() < 1e-5);
        assert!((f(15) - upb * upb).abs() < 1e-3);
    }
}
