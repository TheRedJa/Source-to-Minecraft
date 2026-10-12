//! Whether the sun and each local light reach a point (format.md section 28).
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
//!
//! A local light (`TestLine`, same file) is blocked by anything in that
//! environment, sky triangles included, so there every hit occludes. vrad also
//! lets static props block local lights (those without `NO_SHADOW`); they are
//! not traced here.

use crate::bsp::worldlights::{
    TYPE_POINT, TYPE_QUAKE, TYPE_SKYLIGHT, TYPE_SPOT, TYPE_SURFACE, WorldLight,
};
use crate::geom::{Aabb, Vec3};
use crate::voxel::transform::Transform;
use anyhow::{Result, ensure};

pub const MAGIC: [u8; 8] = *b"S2SUN\0\0\0";
pub const VERSION: u32 = 2;
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
/// A light's segment stops this far short of its origin, so a lamp set flush
/// into a ceiling brush is not hidden by the brush it sits in.
const LIGHT_SLACK: f64 = 1.0;
/// Brushes per BVH leaf at most.
const LEAF_BRUSHES: usize = 4;

struct TraceBrush {
    bounds: Aabb,
    /// Outward normal, distance, and whether the side is a sky side.
    sides: Vec<(Vec3, f64, bool)>,
}

/// A BVH node: a leaf holds `count` brushes from `first` in the order list;
/// an inner node (`count == 0`) has its children at `first` and `second`.
struct Node {
    bounds: Aabb,
    first: u32,
    second: u32,
    count: u32,
}

/// A local light that can be listed: its position and reach in Source units.
struct Reach {
    origin: Vec3,
    radius: f64,
}

/// What a point sees: the sun, and the indices of the local lights in reach
/// and in sight (section 28).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PointVisibility {
    pub sun: u8,
    pub lights: Vec<u16>,
}

/// A prop's lighting origin in block space and what it sees.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PropSun {
    pub origin: [f32; 3],
    pub seen: PointVisibility,
}

/// The opaque world brushes in a BVH, the direction to the sun, and the lights.
pub struct SunTracer {
    brushes: Vec<TraceBrush>,
    /// Brush indices, grouped by leaf.
    order: Vec<u32>,
    nodes: Vec<Node>,
    /// Unit vector from the lit point toward the sun; `None` without a skylight.
    toward_sun: Option<Vec3>,
    /// Per world light, in section 27 order: `None` for sky lights.
    lights: Vec<Option<Reach>>,
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
        let lights = map.world_lights.iter().map(reach).collect::<Vec<_>>();
        let brushes = if toward_sun.is_some() || lights.iter().any(Option::is_some) {
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
        SunTracer::assemble(brushes, toward_sun, lights)
    }

    fn assemble(
        brushes: Vec<TraceBrush>,
        toward_sun: Option<Vec3>,
        lights: Vec<Option<Reach>>,
    ) -> SunTracer {
        let mut order: Vec<u32> = (0..brushes.len() as u32).collect();
        let mut nodes = Vec::new();
        if !brushes.is_empty() {
            let len = order.len();
            build(&brushes, &mut order, 0, len, &mut nodes);
        }
        SunTracer {
            brushes,
            order,
            nodes,
            toward_sun,
            lights,
        }
    }

    #[cfg(test)]
    fn from_parts(brushes: Vec<(Vec<(Vec3, f64, bool)>, Aabb)>, toward_sun: Vec3) -> SunTracer {
        SunTracer::assemble(
            brushes
                .into_iter()
                .map(|(sides, bounds)| TraceBrush { bounds, sides })
                .collect(),
            Some(toward_sun),
            Vec::new(),
        )
    }

    #[cfg(test)]
    fn with_lights(mut self, lights: &[(Vec3, f64)]) -> SunTracer {
        self.lights = lights
            .iter()
            .map(|&(origin, radius)| Some(Reach { origin, radius }))
            .collect();
        self
    }

    /// The number of world lights, listed or not.
    pub fn light_count(&self) -> usize {
        self.lights.len()
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
            match self.first_hit(start, dir, RAY_LENGTH) {
                Hit::Inside if step < NUDGE_LIMIT => start.z += 1.0,
                Hit::Enter { sky: true, .. } | Hit::Miss => return 255,
                _ => return 0,
            }
        }
        0
    }

    /// Whether the light's origin is seen from the point: not behind any
    /// opaque brush, sky included. Same nudge out of a brush as the sun.
    fn sees(&self, from: Vec3, target: Vec3) -> bool {
        let mut start = from;
        for step in 0..=NUDGE_LIMIT {
            let delta = target - start;
            let length = delta.length();
            if length - LIGHT_SLACK <= 0.0 {
                return true;
            }
            let dir = delta * (1.0 / length);
            match self.first_hit(start, dir, length - LIGHT_SLACK) {
                Hit::Inside if step < NUDGE_LIMIT => start.z += 1.0,
                Hit::Miss => return true,
                _ => return false,
            }
        }
        false
    }

    /// Indices of the local lights whose radius reaches the point and whose
    /// origin it sees, ascending.
    fn visible_lights(&self, from: Vec3) -> Vec<u16> {
        let mut out = Vec::new();
        for (index, light) in self.lights.iter().enumerate() {
            let Some(light) = light else { continue };
            if (light.origin - from).length() <= light.radius && self.sees(from, light.origin) {
                out.push(index as u16);
            }
        }
        out
    }

    /// The sun and the lights at a Source-space point.
    pub fn at(&self, from: Vec3) -> PointVisibility {
        PointVisibility {
            sun: self.visibility(from),
            lights: self.visible_lights(from),
        }
    }

    /// How many local lights have the point inside their radius.
    pub fn lights_in_range(&self, from: Vec3) -> usize {
        self.lights
            .iter()
            .flatten()
            .filter(|light| (light.origin - from).length() <= light.radius)
            .count()
    }

    /// Walks the BVH for the nearest brush the ray enters within `max_t`.
    /// `Inside` as soon as any brush holds the start, whatever else was found.
    /// The search narrows to the nearest entry so far. Ties go to the lower brush index,
    /// as in a front to back scan of the brush list.
    fn first_hit(&self, origin: Vec3, dir: Vec3, max_t: f64) -> Hit {
        if self.nodes.is_empty() {
            return Hit::Miss;
        }
        let mut best = Hit::Miss;
        let mut best_t = max_t;
        let mut best_index = u32::MAX;
        let mut stack = [0u32; 128];
        let mut depth = 1;
        while depth > 0 {
            depth -= 1;
            let node = &self.nodes[stack[depth] as usize];
            if !crosses(&node.bounds, origin, dir, best_t) {
                continue;
            }
            if node.count == 0 {
                stack[depth] = node.first;
                stack[depth + 1] = node.second;
                depth += 2;
                continue;
            }
            let leaf = node.first as usize..(node.first + node.count) as usize;
            for &index in &self.order[leaf] {
                let brush = &self.brushes[index as usize];
                if !crosses(&brush.bounds, origin, dir, best_t) {
                    continue;
                }
                match clip(brush, origin, dir) {
                    Hit::Inside => return Hit::Inside,
                    Hit::Enter { t, sky } if t < best_t || (t == best_t && index < best_index) => {
                        best_t = t;
                        best_index = index;
                        best = Hit::Enter { t, sky };
                    }
                    _ => {}
                }
            }
        }
        best
    }

    /// The same search by scanning every brush, for checking the BVH.
    #[cfg(test)]
    fn first_hit_brute(&self, origin: Vec3, dir: Vec3, max_t: f64) -> Hit {
        let mut best = Hit::Miss;
        let mut best_t = max_t;
        for brush in &self.brushes {
            if !crosses(&brush.bounds, origin, dir, max_t) {
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

/// Builds the subtree over `order[from..to]`, returning its node index. Splits
/// at the median brush centre along the longest axis of the centres.
fn build(brushes: &[TraceBrush], order: &mut [u32], from: usize, to: usize, nodes: &mut Vec<Node>) -> u32 {
    let mut bounds = brushes[order[from] as usize].bounds;
    for &index in &order[from + 1..to] {
        bounds = bounds.union(&brushes[index as usize].bounds);
    }
    let at = nodes.len() as u32;
    nodes.push(Node {
        bounds,
        first: from as u32,
        second: 0,
        count: (to - from) as u32,
    });
    if to - from <= LEAF_BRUSHES {
        return at;
    }
    let mut lo = Vec3::new(f64::MAX, f64::MAX, f64::MAX);
    let mut hi = Vec3::new(f64::MIN, f64::MIN, f64::MIN);
    for &index in &order[from..to] {
        let c = brushes[index as usize].bounds.center();
        lo = Vec3::new(lo.x.min(c.x), lo.y.min(c.y), lo.z.min(c.z));
        hi = Vec3::new(hi.x.max(c.x), hi.y.max(c.y), hi.z.max(c.z));
    }
    let spread = hi - lo;
    let axis = if spread.x >= spread.y && spread.x >= spread.z {
        0
    } else if spread.y >= spread.z {
        1
    } else {
        2
    };
    let middle = from + (to - from) / 2;
    order[from..to].select_nth_unstable_by(middle - from, |a, b| {
        let key = |i: &u32| brushes[*i as usize].bounds.center().axis(axis);
        key(a).total_cmp(&key(b))
    });
    let left = build(brushes, order, from, middle, nodes);
    let right = build(brushes, order, middle, to, nodes);
    let node = &mut nodes[at as usize];
    node.first = left;
    node.second = right;
    node.count = 0;
    at
}

/// A light that can be listed, or `None` for the sky lights.
fn reach(light: &WorldLight) -> Option<Reach> {
    matches!(
        light.kind,
        TYPE_SURFACE | TYPE_POINT | TYPE_SPOT | TYPE_QUAKE
    )
    .then(|| {
        let [x, y, z] = light.origin;
        Reach {
            origin: Vec3::new(f64::from(x), f64::from(y), f64::from(z)),
            radius: f64::from(light.radius),
        }
    })
}

/// The unit vector from a skylight toward the sun: the light's `normal` is the
/// way its light travels.
fn sun_direction(light: &WorldLight) -> Option<Vec3> {
    let [x, y, z] = light.normal;
    let travel = Vec3::new(f64::from(x), f64::from(y), f64::from(z));
    (travel.length() > 1e-6).then(|| (-travel).normalized())
}

/// Whether the segment of `max_t` can touch the box (slab test).
fn crosses(bounds: &Aabb, origin: Vec3, dir: Vec3, max_t: f64) -> bool {
    let (mut near, mut far) = (0.0f64, max_t);
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

/// Magic, version, the light count, the ambient samples' sun visibility, each
/// prop's lighting origin in block space and sun visibility, then every
/// point's list of visible lights (samples first, then props).
pub fn encode(light_count: usize, samples: &[PointVisibility], props: &[PropSun]) -> Result<Vec<u8>> {
    ensure!(light_count <= usize::from(u16::MAX), "{light_count} world lights do not fit 16-bit indices");
    let listed: usize = samples
        .iter()
        .chain(props.iter().map(|prop| &prop.seen))
        .map(|seen| seen.lights.len() * 2 + 2)
        .sum();
    let mut out = Vec::with_capacity(24 + samples.len() + props.len() * PROP_RECORD_BYTES + listed);
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&u32::try_from(light_count)?.to_le_bytes());
    out.extend_from_slice(&u32::try_from(samples.len())?.to_le_bytes());
    out.extend(samples.iter().map(|sample| sample.sun));
    out.extend_from_slice(&u32::try_from(props.len())?.to_le_bytes());
    for prop in props {
        for value in prop.origin {
            ensure!(value.is_finite(), "non-finite prop lighting origin");
            out.extend_from_slice(&(if value == 0.0 { 0.0 } else { value }).to_le_bytes());
        }
        out.extend_from_slice(&[prop.seen.sun, 0, 0, 0]);
    }
    for seen in samples.iter().chain(props.iter().map(|prop| &prop.seen)) {
        ensure!(
            seen.lights.len() <= usize::from(u16::MAX),
            "{} visible lights do not fit a 16-bit count",
            seen.lights.len()
        );
        out.extend_from_slice(&(seen.lights.len() as u16).to_le_bytes());
        for light in &seen.lights {
            ensure!((usize::from(*light)) < light_count, "light index {light} out of range");
            out.extend_from_slice(&light.to_le_bytes());
        }
    }
    Ok(out)
}

/// What each ambient sample sees, in the order section 22 stores them. Runs
/// on all cores.
pub fn sample_visibility(tracer: &SunTracer, map: &crate::bsp::Map) -> Vec<PointVisibility> {
    use rayon::prelude::*;
    map.ambient
        .samples
        .par_iter()
        .map(|sample| {
            let [x, y, z] = sample.position;
            tracer.at(Vec3::new(x, y, z))
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
) -> PropSun {
    let origin = lighting_origin(item);
    let block = transform.to_block_space(origin);
    PropSun {
        origin: [block.x as f32, block.y as f32, block.z as f32],
        seen: tracer.at(origin),
    }
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
        let samples = [
            PointVisibility { sun: 255, lights: vec![0, 2] },
            PointVisibility { sun: 0, lights: Vec::new() },
        ];
        let props = [PropSun {
            origin: [1.0, 2.0, 3.0],
            seen: PointVisibility { sun: 255, lights: vec![1] },
        }];
        let bytes = encode(3, &samples, &props).unwrap();
        assert_eq!(&bytes[..8], &MAGIC);
        assert_eq!(&bytes[8..12], &2u32.to_le_bytes());
        assert_eq!(&bytes[12..16], &3u32.to_le_bytes());
        assert_eq!(&bytes[16..20], &2u32.to_le_bytes());
        assert_eq!(&bytes[20..22], &[255, 0]);
        assert_eq!(&bytes[22..26], &1u32.to_le_bytes());
        let record = 26 + PROP_RECORD_BYTES;
        assert_eq!(&bytes[record - 4..record], &[255, 0, 0, 0]);
        let lists: Vec<u8> = [2u16, 0, 2, 0, 1, 1]
            .iter()
            .flat_map(|v| v.to_le_bytes())
            .collect();
        assert_eq!(&bytes[record..], &lists[..]);
    }

    #[test]
    fn too_many_lights_for_sixteen_bits_is_an_error() {
        assert!(encode(65536, &[], &[]).is_err());
        assert!(encode(65535, &[], &[]).is_ok());
    }

    #[test]
    fn a_wall_hides_a_light_and_the_radius_limits_it() {
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
        let tracer = SunTracer::from_parts(vec![wall], up()).with_lights(&[
            (Vec3::new(200.0, 0.0, 0.0), 500.0),
            (Vec3::new(-50.0, 0.0, 0.0), 500.0),
            (Vec3::new(-50.0, 0.0, 0.0), 10.0),
        ]);
        // Lights 0 is behind the wall, 1 beside the viewer, 2 out of reach.
        assert_eq!(tracer.at(Vec3::ZERO).lights, vec![1]);
        assert_eq!(tracer.lights_in_range(Vec3::ZERO), 2);
        // A light set 0.5 units into the wall's face still lights from outside.
        let flush = SunTracer::from_parts(vec![slab(0.0, 8.0, false)], up())
            .with_lights(&[(Vec3::new(0.0, 0.0, 7.5), 100.0)]);
        assert_eq!(flush.at(Vec3::new(0.0, 0.0, 40.0)).lights, vec![0]);
    }

    /// Deterministic pseudo-random numbers in [0, 1).
    struct Lcg(u64);

    impl Lcg {
        fn next(&mut self) -> f64 {
            self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            (self.0 >> 11) as f64 / (1u64 << 53) as f64
        }
    }

    fn cube(center: Vec3, half: f64, sky: bool) -> (Vec<(Vec3, f64, bool)>, Aabb) {
        let sides = vec![
            (Vec3::new(1.0, 0.0, 0.0), center.x + half, false),
            (Vec3::new(-1.0, 0.0, 0.0), -(center.x - half), false),
            (Vec3::new(0.0, 1.0, 0.0), center.y + half, false),
            (Vec3::new(0.0, -1.0, 0.0), -(center.y - half), false),
            (Vec3::new(0.0, 0.0, 1.0), center.z + half, sky),
            (Vec3::new(0.0, 0.0, -1.0), -(center.z - half), false),
        ];
        let h = Vec3::new(half, half, half);
        (sides, Aabb::new(center - h, center + h))
    }

    fn same(a: &Hit, b: &Hit) -> bool {
        match (a, b) {
            (Hit::Miss, Hit::Miss) | (Hit::Inside, Hit::Inside) => true,
            (Hit::Enter { t: x, sky: p }, Hit::Enter { t: y, sky: q }) => x == y && p == q,
            _ => false,
        }
    }

    fn check_rays(tracer: &SunTracer, around: (Vec3, Vec3), rays: usize, seed: u64) -> usize {
        let mut random = Lcg(seed);
        let mut hits = 0;
        for _ in 0..rays {
            let mut point = |lo: Vec3, hi: Vec3| {
                Vec3::new(
                    lo.x + (hi.x - lo.x) * random.next(),
                    lo.y + (hi.y - lo.y) * random.next(),
                    lo.z + (hi.z - lo.z) * random.next(),
                )
            };
            let origin = point(around.0, around.1);
            let target = point(around.0, around.1);
            let length = (target - origin).length();
            if length < 1e-3 {
                continue;
            }
            let dir = (target - origin) * (1.0 / length);
            for max_t in [length, RAY_LENGTH] {
                let fast = tracer.first_hit(origin, dir, max_t);
                let slow = tracer.first_hit_brute(origin, dir, max_t);
                assert!(same(&fast, &slow), "rays disagree from {origin:?} toward {dir:?}");
                hits += usize::from(matches!(fast, Hit::Enter { .. }));
            }
        }
        hits
    }

    #[test]
    fn the_bvh_agrees_with_scanning_every_brush() {
        let mut random = Lcg(7);
        let mut brushes = Vec::new();
        for i in 0..400 {
            let center = Vec3::new(
                random.next() * 4000.0 - 2000.0,
                random.next() * 4000.0 - 2000.0,
                random.next() * 1000.0,
            );
            brushes.push(cube(center, 8.0 + random.next() * 120.0, i % 5 == 0));
        }
        let tracer = SunTracer::from_parts(brushes, Vec3::new(0.3, 0.2, 1.0).normalized());
        let area = (Vec3::new(-2200.0, -2200.0, -50.0), Vec3::new(2200.0, 2200.0, 1100.0));
        assert!(check_rays(&tracer, area, 20_000, 99) > 1000);
    }

    /// Needs the INFRA furnace map extracted next to the other references.
    #[test]
    #[ignore]
    fn the_bvh_agrees_with_scanning_every_brush_on_a_real_map() {
        let path = std::path::Path::new(
            "/home/jakob/projects/minecraft/tools/source_refs/data/infra_c4_m2_furnace.bsp",
        );
        let map = crate::bsp::Map::load(path).unwrap();
        let tracer = SunTracer::new(&map);
        assert!(tracer.brushes.len() > 100);
        let bounds = map.bounds();
        let hits = check_rays(&tracer, (bounds.min, bounds.max), 100_000, 5);
        assert!(hits > 10_000);
        // The shipped answers equal the scan's for the sun too.
        let mut random = Lcg(11);
        for _ in 0..2000 {
            let at = Vec3::new(
                bounds.min.x + (bounds.max.x - bounds.min.x) * random.next(),
                bounds.min.y + (bounds.max.y - bounds.min.y) * random.next(),
                bounds.min.z + (bounds.max.z - bounds.min.z) * random.next(),
            );
            if let Some(dir) = tracer.toward_sun {
                let slow = tracer.first_hit_brute(at, dir, RAY_LENGTH);
                let fast = tracer.first_hit(at, dir, RAY_LENGTH);
                assert!(same(&fast, &slow));
            }
        }
    }
}
