//! The world lights vrad compiled from the map's light entities, `LUMP_WORLDLIGHTS`
//! and `LUMP_WORLDLIGHTS_HDR` (`dworldlight_t`).
//!
//! The engine lights models with these: a model's ambient cube plus up to four
//! local lights. The HDR lump is read when it is not empty, as the lighting lumps
//! are (see [`super::lighting`]).

use super::lumps;
use anyhow::{Result, ensure};

const LUMP_WORLDLIGHTS: usize = 15;
const LUMP_WORLDLIGHTS_HDR: usize = 54;
/// `dworldlight_t` of lump version 0.
const STRIDE_V0: usize = 88;
/// Lump version 1 (Alien Swarm onwards) inserts `shadow_cast_offset[3]` after the normal.
const STRIDE_V1: usize = 100;

pub const TYPE_SURFACE: u8 = 0;
pub const TYPE_POINT: u8 = 1;
pub const TYPE_SPOT: u8 = 2;
pub const TYPE_SKYLIGHT: u8 = 3;
pub const TYPE_QUAKE: u8 = 4;
pub const TYPE_SKY_AMBIENT: u8 = 5;

/// `DWL_FLAGS_INAMBIENTCUBE`: the light is already part of the ambient cubes.
pub const FLAG_IN_AMBIENT_CUBE: u32 = 0x1;
/// `DWL_FLAGS_CASTENTITYSHADOWS`.
pub const FLAG_CAST_ENTITY_SHADOWS: u32 = 0x2;

/// One light in Source space and units, as the lump stores it, with the
/// engine's load-time fixups applied.
#[derive(Debug, Clone, PartialEq)]
pub struct WorldLight {
    pub origin: [f32; 3],
    /// Linear RGB, unclamped, pre-scaled by vrad.
    pub intensity: [f32; 3],
    /// A spotlight's direction (light towards target); a skylight's direction
    /// of travel.
    pub normal: [f32; 3],
    pub kind: u8,
    pub style: i32,
    pub flags: u32,
    pub stopdot: f32,
    pub stopdot2: f32,
    pub exponent: f32,
    /// Distance beyond which the light is ignored, Source units. Never 0 after
    /// reading: a stored 0 is replaced by the distance at which the light falls
    /// below the engine's minimum value (2000 when it never does).
    pub radius: f32,
    pub constant_attn: f32,
    pub linear_attn: f32,
    pub quadratic_attn: f32,
}

/// The map's world lights, HDR when it has them.
pub fn read(data: &[u8]) -> Result<Vec<WorldLight>> {
    let hdr_entry = lumps::lump_entry(data, LUMP_WORLDLIGHTS_HDR)?;
    let hdr = !data.get(hdr_entry.range()).unwrap_or(&[]).is_empty();
    let entry = if hdr { hdr_entry } else { lumps::lump_entry(data, LUMP_WORLDLIGHTS)? };
    let bytes = data.get(entry.range()).unwrap_or(&[]);
    parse(bytes, entry.version, hdr)
}

/// Parses a lump of the given version.
pub fn parse(bytes: &[u8], version: i32, hdr: bool) -> Result<Vec<WorldLight>> {
    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    let stride = if version >= 1 { STRIDE_V1 } else { STRIDE_V0 };
    ensure!(
        bytes.len().is_multiple_of(stride),
        "world light lump of {} bytes is not a multiple of version {version}'s {stride}",
        bytes.len()
    );
    let min_value = if hdr { 0.015 } else { 0.03 };
    Ok(bytes
        .chunks_exact(stride)
        .map(|record| {
            let f = |at: usize| f32::from_le_bytes(record[at..at + 4].try_into().unwrap());
            let i = |at: usize| i32::from_le_bytes(record[at..at + 4].try_into().unwrap());
            // The version 1 shadow cast offset sits between the normal and the cluster.
            let skip = stride - STRIDE_V0;
            let at = |offset: usize| if offset >= 36 { offset + skip } else { offset };
            let mut light = WorldLight {
                origin: [f(0), f(4), f(8)],
                intensity: [f(12), f(16), f(20)],
                normal: [f(24), f(28), f(32)],
                kind: u8::try_from(i(at(40))).unwrap_or(u8::MAX),
                style: i(at(44)),
                stopdot: f(at(48)),
                stopdot2: f(at(52)),
                exponent: f(at(56)),
                radius: f(at(60)),
                constant_attn: f(at(64)),
                linear_attn: f(at(68)),
                quadratic_attn: f(at(72)),
                flags: i(at(76)) as u32,
            };
            fix_up(&mut light, min_value);
            light
        })
        .collect())
}

/// The engine's (and noclip.website's) fixups for old data and for a zero radius.
fn fix_up(light: &mut WorldLight, min_value: f32) {
    let (c, l, q) = (light.constant_attn, light.linear_attn, light.quadratic_attn);
    if q == 0.0 && l == 0.0 && c == 0.0 && matches!(light.kind, TYPE_POINT | TYPE_SPOT) {
        light.quadratic_attn = 1.0;
    }
    if light.exponent == 0.0 && light.kind == TYPE_POINT {
        light.exponent = 1.0;
    }
    if light.radius == 0.0 {
        let (c, l, q) = (light.constant_attn, light.linear_attn, light.quadratic_attn);
        let strength = light.intensity.iter().map(|v| v * v).sum::<f32>().sqrt();
        light.radius = if q == 0.0 && l == 0.0 {
            2000.0
        } else if q == 0.0 {
            (strength / min_value - c) / l
        } else {
            let rad = l * l - 4.0 * q * (c - strength / min_value);
            if rad > 0.0 { (-l + rad.sqrt()) / (2.0 * q) } else { 2000.0 }
        };
        if !light.radius.is_finite() || light.radius < 0.0 {
            light.radius = 2000.0;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(version1: bool, radius: f32, attn: [f32; 3]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut f = |v: f32| out.extend_from_slice(&v.to_le_bytes());
        for v in [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 0.0, 0.0, -1.0] {
            f(v);
        }
        if version1 {
            for v in [7.0, 8.0, 9.0] {
                f(v);
            }
        }
        let ints = |out: &mut Vec<u8>, v: i32| out.extend_from_slice(&v.to_le_bytes());
        ints(&mut out, 12); // cluster
        ints(&mut out, 2); // type: spot
        ints(&mut out, 33); // style
        for v in [0.9f32, 0.5, 2.0, radius, attn[0], attn[1], attn[2]] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        ints(&mut out, 3); // flags
        ints(&mut out, -1); // texinfo
        ints(&mut out, -1); // owner
        out
    }

    fn check(lights: &[WorldLight]) {
        assert_eq!(lights.len(), 1);
        let l = &lights[0];
        assert_eq!(l.origin, [1.0, 2.0, 3.0]);
        assert_eq!(l.intensity, [4.0, 5.0, 6.0]);
        assert_eq!(l.normal, [0.0, 0.0, -1.0]);
        assert_eq!((l.kind, l.style, l.flags), (TYPE_SPOT, 33, 3));
        assert_eq!((l.stopdot, l.stopdot2, l.exponent), (0.9, 0.5, 2.0));
        assert_eq!((l.radius, l.constant_attn, l.linear_attn, l.quadratic_attn), (300.0, 0.0, 0.5, 0.25));
    }

    #[test]
    fn reads_the_88_byte_layout() {
        let bytes = record(false, 300.0, [0.0, 0.5, 0.25]);
        assert_eq!(bytes.len(), 88);
        check(&parse(&bytes, 0, true).unwrap());
    }

    #[test]
    fn reads_the_100_byte_layout() {
        let bytes = record(true, 300.0, [0.0, 0.5, 0.25]);
        assert_eq!(bytes.len(), 100);
        check(&parse(&bytes, 1, true).unwrap());
    }

    #[test]
    fn a_ragged_lump_is_an_error() {
        assert!(parse(&[0; 90], 0, false).is_err());
        assert!(parse(&[], 1, false).unwrap().is_empty());
    }

    #[test]
    fn a_zero_radius_is_solved_from_the_attenuation() {
        // |I| = 15 (3-4-... scaled), q = 1: radius solves q d^2 = |I| / min.
        let mut bytes = record(false, 0.0, [0.0, 0.0, 1.0]);
        bytes[12..24].copy_from_slice(&[0.0f32, 0.0, 15.0].iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>());
        let l = &parse(&bytes, 0, true).unwrap()[0];
        assert!((l.radius - (15.0f32 / 0.015).sqrt()).abs() < 1e-2, "{}", l.radius);
        // No falloff at all: the fixed fallback, and all-zero attenuation becomes quadratic.
        let l = &parse(&record(false, 0.0, [1.0, 0.0, 0.0]), 0, false).unwrap()[0];
        assert_eq!(l.radius, 2000.0);
        let l = &parse(&record(false, 50.0, [0.0, 0.0, 0.0]), 0, false).unwrap()[0];
        assert_eq!(l.quadratic_attn, 1.0);
    }
}
