//! Whether the sun reaches a point (format.md section 28).
//!
//! vrad lights nothing by the sky light (`emit_skylight`) unless a ray from the
//! point toward the sun reaches a sky surface first; `TestLine_DoesHitSky` in
//! `utils/vrad/trace.cpp`. Its ray environment holds the world model's brushes
//! whose contents intersect `MASK_OPAQUE` (solid, moveable, opaque), minus the
//! sides flagged `SURF_SKY`, plus every `SURF_SKY` face as a sky triangle. A
//! hit on a sky triangle does not occlude; any other hit does. Clipping the
//! ray against whole brushes and asking whether the side it enters is a sky
//! side gives the same answer: a sky brush's sky sides are where its sky
//! triangles are. Static props are not in vrad's sun test.

use crate::bsp::worldlights::{TYPE_SKYLIGHT, WorldLight};
use crate::geom::{Aabb, Vec3};
use crate::voxel::transform::Transform;
use anyhow::{Result, ensure};

pub const MAGIC: [u8; 8] = *b"S2SUN\0\0\0";
pub const VERSION: u32 = 1;
/// Bytes per prop record: three floats, the visibility, three padding bytes.
pub const PROP_RECORD_BYTES: usize = 16;

/// `CONTENTS_SOLID | CONTENTS_MOVEABLE | CONTENTS_OPAQUE`.
const MASK_OPAQUE: u32 = 0x1 | 0x4000 | 0x80;
/// `SURF_SKY`, which is what vrad tests; `SURF_SKY2D` (0x2) comes with it on
/// the stock sky materials.
const SURF_SKY: u32 = 0x4;
const SURF_SKY2D: u32 = 0x2;
/// How far the sun ray goes, in Source units.
const RAY_LENGTH: f64 = 65536.0;
/// A start point inside a brush is moved up this far in the worst case, one
/// unit at a time.
const NUDGE_LIMIT: u32 = 16;

struct TraceBrush {
    bounds: Aabb,
    /// Outward normal, distance, and whether the side is a sky side.
    sides: Vec<(Vec3, f64, bool)>,
}

/// The opaque world brushes and the direction to the sun.
pub struct SunTracer {
    brushes: Vec<TraceBrush>,
    /// Unit vector from the lit point toward the sun; `None` without a skylight.
    toward_sun: Option<Vec3>,
}

enum Hit {
    Miss,
    Inside,
    Enter { t: f64, sky: bool },
}

impl SunTracer {
    pub fn new(map: &crate::bsp::Map) -> SunTracer {
        let toward_sun = map
            .world_lights
            .iter()
            .find(|light| light.kind == TYPE_SKYLIGHT)
            .and_then(sun_direction);
        let brushes = if toward_sun.is_some() {
            map.solids(0)
                .into_iter()
                .filter(|solid| solid.flags.bits() & MASK_OPAQUE != 0)
                .map(|solid| TraceBrush {
                    bounds: solid.bounds,
                    sides: solid
                        .sides
                        .iter()
                        .map(|side| {
                            (
                                side.plane.normal,
                                side.plane.dist,
                                side.texture_flags.bits() & (SURF_SKY | SURF_SKY2D) != 0,
                            )
                        })
                        .collect(),
                })
                .collect()
        } else {
            Vec::new()
        };
        SunTracer {
            brushes,
            toward_sun,
        }
    }

    #[cfg(test)]
    fn from_parts(brushes: Vec<(Vec<(Vec3, f64, bool)>, Aabb)>, toward_sun: Vec3) -> SunTracer {
        SunTracer {
            brushes: brushes
                .into_iter()
                .map(|(sides, bounds)| TraceBrush { bounds, sides })
                .collect(),
            toward_sun: Some(toward_sun),
        }
    }

    /// The sun's visibility at a Source-space point: 255 when the first
    /// opaque thing along the ray to the sun is sky, 0 otherwise or when the
    /// map has no skylight. A start inside a brush is nudged up along +Z, one
    /// unit at a time up to 16, to get out of it.
    pub fn visibility(&self, from: Vec3) -> u8 {
        let Some(dir) = self.toward_sun else {
            return 0;
        };
        let mut start = from;
        for step in 0..=NUDGE_LIMIT {
            match self.first_hit(start, dir) {
                Hit::Inside if step < NUDGE_LIMIT => start.z += 1.0,
                Hit::Enter { sky: true, .. } | Hit::Miss => return 255,
                _ => return 0,
            }
        }
        0
    }

    fn first_hit(&self, origin: Vec3, dir: Vec3) -> Hit {
        let mut best = Hit::Miss;
        let mut best_t = RAY_LENGTH;
        for brush in &self.brushes {
            if !crosses(&brush.bounds, origin, dir) {
                continue;
            }
            match clip(brush, origin, dir) {
                Hit::Inside => return Hit::Inside,
                Hit::Enter { t, sky } if t < best_t => {
                    best_t = t;
                    best = Hit::Enter { t, sky };
                }
                _ => {}
            }
        }
        best
    }
}

/// The unit vector from a skylight toward the sun: the light's `normal` is the
/// way its light travels.
fn sun_direction(light: &WorldLight) -> Option<Vec3> {
    let [x, y, z] = light.normal;
    let travel = Vec3::new(f64::from(x), f64::from(y), f64::from(z));
    (travel.length() > 1e-6).then(|| (-travel).normalized())
}

/// Whether the segment of `RAY_LENGTH` can touch the box (slab test).
fn crosses(bounds: &Aabb, origin: Vec3, dir: Vec3) -> bool {
    let (mut near, mut far) = (0.0f64, RAY_LENGTH);
    for axis in 0..3 {
        let (o, d) = (origin.axis(axis), dir.axis(axis));
        let (lo, hi) = (bounds.min.axis(axis) - 0.01, bounds.max.axis(axis) + 0.01);
        if d.abs() < 1e-12 {
            if o < lo || o > hi {
                return false;
            }
        } else {
            let (a, b) = ((lo - o) / d, (hi - o) / d);
            near = near.max(a.min(b));
            far = far.min(a.max(b));
            if near > far {
                return false;
            }
        }
    }
    true
}

/// A ray against one convex brush: where it enters, and by which kind of side.
fn clip(brush: &TraceBrush, origin: Vec3, dir: Vec3) -> Hit {
    let (mut enter, mut leave) = (f64::NEG_INFINITY, RAY_LENGTH);
    let mut sky = false;
    for &(normal, dist, is_sky) in &brush.sides {
        let distance = normal.dot(origin) - dist;
        let speed = normal.dot(dir);
        if speed.abs() < 1e-12 {
            if distance > 0.0 {
                return Hit::Miss;
            }
        } else {
            let t = -distance / speed;
            if speed < 0.0 {
                if t > enter {
                    enter = t;
                    sky = is_sky;
                }
            } else if t < leave {
                leave = t;
            }
        }
        if enter > leave {
            return Hit::Miss;
        }
    }
    if enter < 0.0 {
        // The start is inside it, or it lies behind the start.
        return if leave > 0.0 { Hit::Inside } else { Hit::Miss };
    }
    Hit::Enter { t: enter, sky }
}

/// Magic, version, the ambient samples' visibility, then each prop's lighting
/// origin in block space and its visibility.
pub fn encode(samples: &[u8], props: &[([f32; 3], u8)]) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(20 + samples.len() + props.len() * PROP_RECORD_BYTES);
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&u32::try_from(samples.len())?.to_le_bytes());
    out.extend_from_slice(samples);
    out.extend_from_slice(&u32::try_from(props.len())?.to_le_bytes());
    for (origin, visibility) in props {
        for value in origin {
            ensure!(value.is_finite(), "non-finite prop lighting origin");
            out.extend_from_slice(&(if *value == 0.0 { 0.0 } else { *value }).to_le_bytes());
        }
        out.extend_from_slice(&[*visibility, 0, 0, 0]);
    }
    Ok(out)
}

/// Each ambient sample's sun visibility, in the order section 22 stores them.
pub fn sample_visibility(tracer: &SunTracer, map: &crate::bsp::Map) -> Vec<u8> {
    map.ambient
        .samples
        .iter()
        .map(|sample| {
            let [x, y, z] = sample.position;
            tracer.visibility(Vec3::new(x, y, z))
        })
        .collect()
}

/// A prop's lighting origin in Source space: the lighting origin the map
/// gives it, else the model's `illumposition` placed like the model.
pub fn lighting_origin(item: &crate::source::extract::ModProp) -> Vec3 {
    if let Some(origin) = item.prop.lighting_origin {
        return origin;
    }
    let r = item.model.root;
    let v = item.model.illum;
    let turned = Vec3::new(
        r[0][0] * v.x + r[0][1] * v.y + r[0][2] * v.z,
        r[1][0] * v.x + r[1][1] * v.y + r[1][2] * v.z,
        r[2][0] * v.x + r[2][1] * v.y + r[2][2] * v.z,
    );
    item.prop.place(turned)
}

/// The prop's record for section 28.
pub fn prop_record(
    tracer: &SunTracer,
    transform: &Transform,
    item: &crate::source::extract::ModProp,
) -> ([f32; 3], u8) {
    let origin = lighting_origin(item);
    let block = transform.to_block_space(origin);
    (
        [block.x as f32, block.y as f32, block.z as f32],
        tracer.visibility(origin),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn slab(z0: f64, z1: f64, sky: bool) -> (Vec<(Vec3, f64, bool)>, Aabb) {
        let big = 1000.0;
        let sides = vec![
            (Vec3::new(1.0, 0.0, 0.0), big, false),
            (Vec3::new(-1.0, 0.0, 0.0), big, false),
            (Vec3::new(0.0, 1.0, 0.0), big, false),
            (Vec3::new(0.0, -1.0, 0.0), big, false),
            (Vec3::new(0.0, 0.0, 1.0), z1, sky),
            (Vec3::new(0.0, 0.0, -1.0), -z0, sky),
        ];
        (
            sides,
            Aabb::new(Vec3::new(-big, -big, z0), Vec3::new(big, big, z1)),
        )
    }

    fn up() -> Vec3 {
        Vec3::new(0.0, 0.0, 1.0)
    }

    #[test]
    fn an_open_sky_slab_lets_the_sun_in() {
        let tracer = SunTracer::from_parts(vec![slab(512.0, 520.0, true)], up());
        assert_eq!(tracer.visibility(Vec3::new(0.0, 0.0, 10.0)), 255);
    }

    #[test]
    fn a_solid_roof_under_the_sky_blocks_it() {
        let tracer = SunTracer::from_parts(
            vec![slab(100.0, 108.0, false), slab(512.0, 520.0, true)],
            up(),
        );
        assert_eq!(tracer.visibility(Vec3::new(0.0, 0.0, 10.0)), 0);
        // Above the roof it sees the sky.
        assert_eq!(tracer.visibility(Vec3::new(0.0, 0.0, 200.0)), 255);
    }

    #[test]
    fn nothing_overhead_is_a_miss_and_so_lit() {
        let tracer = SunTracer::from_parts(Vec::new(), up());
        assert_eq!(tracer.visibility(Vec3::ZERO), 255);
    }

    #[test]
    fn a_start_inside_a_wall_is_nudged_out_through_the_top() {
        let tracer = SunTracer::from_parts(
            vec![slab(0.0, 8.0, false), slab(512.0, 520.0, true)],
            up(),
        );
        // 4 units into an 8 unit floor: out after 5 steps, then sky.
        assert_eq!(tracer.visibility(Vec3::new(0.0, 0.0, 4.0)), 255);
        // Deeper than the nudge reaches stays dark.
        let thick = SunTracer::from_parts(vec![slab(0.0, 100.0, false)], up());
        assert_eq!(thick.visibility(Vec3::new(0.0, 0.0, 10.0)), 0);
    }

    #[test]
    fn a_slanted_sun_is_blocked_by_a_wall_beside_it() {
        let wall = (
            vec![
                (Vec3::new(1.0, 0.0, 0.0), 120.0, false),
                (Vec3::new(-1.0, 0.0, 0.0), -100.0, false),
                (Vec3::new(0.0, 1.0, 0.0), 1000.0, false),
                (Vec3::new(0.0, -1.0, 0.0), 1000.0, false),
                (Vec3::new(0.0, 0.0, 1.0), 1000.0, false),
                (Vec3::new(0.0, 0.0, -1.0), 1000.0, false),
            ],
            Aabb::new(Vec3::new(100.0, -1000.0, -1000.0), Vec3::new(120.0, 1000.0, 1000.0)),
        );
        let sun = Vec3::new(1.0, 0.0, 1.0).normalized();
        let tracer = SunTracer::from_parts(vec![wall], sun);
        assert_eq!(tracer.visibility(Vec3::new(0.0, 0.0, 0.0)), 0);
        assert_eq!(tracer.visibility(Vec3::new(200.0, 0.0, 0.0)), 255);
    }

    #[test]
    fn the_file_layout_is_fixed() {
        let bytes = encode(&[255, 0], &[([1.0, 2.0, 3.0], 255)]).unwrap();
        assert_eq!(&bytes[..8], &MAGIC);
        assert_eq!(bytes.len(), 8 + 4 + 4 + 2 + 4 + PROP_RECORD_BYTES);
        assert_eq!(&bytes[16..18], &[255, 0]);
        assert_eq!(bytes[bytes.len() - 4], 255);
    }
}
