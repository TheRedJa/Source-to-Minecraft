//! Reading a studio model's skeleton and animations, as Source's bone setup
//! reads them.
//!
//! A `prop_dynamic` the map tells to `SetAnimation` — a button pressing in, a
//! lever swinging, a clock's hands, a door sliding open — moves its bones
//! along one of the model's sequences. `vmdl` reads the model's geometry but
//! not its animation: it panics on data kept in an external `.ani` file, reads
//! the run-length value streams unsigned and stops at frame 255. This reads the
//! same structures the way `bone_setup.cpp` (`CalcAnimation`,
//! `CalcBoneQuaternion`, `CalcBonePosition`, `ExtractAnimValue`) and
//! `studio.cpp` (`mstudioanimdesc_t::pAnim`) of the 2013 SDK do, and keeps
//! every sequence's every frame as each bone's local position and rotation.
//!
//! Everything is read defensively: offsets and counts come from files that
//! routinely carry garbage, so a bad one makes the model's animation absent,
//! never a panic.

use crate::source::vfs::Vfs;

/// `mstudioseqdesc_t::flags`.
pub const STUDIO_LOOPING: u32 = 0x0001;
pub const STUDIO_SNAP: u32 = 0x0002;
pub const STUDIO_DELTA: u32 = 0x0004;
pub const STUDIO_ALLZEROS: u32 = 0x0020;
pub const STUDIO_REALTIME: u32 = 0x0100;

const STUDIO_ANIM_RAWPOS: u8 = 0x01;
const STUDIO_ANIM_RAWROT: u8 = 0x02;
const STUDIO_ANIM_ANIMPOS: u8 = 0x04;
const STUDIO_ANIM_ANIMROT: u8 = 0x08;
const STUDIO_ANIM_DELTA: u8 = 0x10;
const STUDIO_ANIM_RAWROT2: u8 = 0x20;
const BONE_FIXED_ALIGNMENT: u32 = 0x0010_0000;

const BONE_SIZE: usize = 216;
const ANIMDESC_SIZE: usize = 100;
const SEQDESC_SIZE: usize = 212;
const POSEPARAM_SIZE: usize = 20;

/// Far above any real model, far below anything that would exhaust memory
/// from a corrupt count.
const MAX_BONES: usize = 256;
const MAX_SEQUENCES: usize = 4096;
const MAX_FRAMES: usize = 1 << 16;
/// Every frame of every sequence of one model, bones included.
const MAX_POSES: usize = 1 << 22;

/// One bone of the skeleton, as `mstudiobone_t` stores it.
#[derive(Debug, Clone, PartialEq)]
pub struct Bone {
    pub name: String,
    /// Index of the parent bone, or -1 for a root.
    pub parent: i32,
    /// The bone's own position and rotation relative to its parent when no
    /// animation says otherwise.
    pub pos: [f32; 3],
    pub quat: [f32; 4],
    rot: [f32; 3],
    pos_scale: [f32; 3],
    rot_scale: [f32; 3],
    /// Model space to bone space at the reference pose, row-major 3x4: what
    /// the vertices were bound with.
    pub pose_to_bone: [[f32; 4]; 3],
    alignment: [f32; 4],
    flags: u32,
}

/// One bone's local position and rotation, XYZW.
pub type BonePose = ([f32; 3], [f32; 4]);

/// One sequence, flattened to what playing it needs.
#[derive(Debug, Clone, PartialEq)]
pub struct Sequence {
    /// `pszLabel`, as written.
    pub label: String,
    /// `pszActivityName`, as written; empty for none.
    pub activity: String,
    pub activity_weight: i32,
    pub flags: u32,
    pub fade_in: f32,
    pub fade_out: f32,
    /// Transition graph nodes; 0 for none.
    pub entry_node: i32,
    pub exit_node: i32,
    pub node_flags: i32,
    /// `Studio_CPS` with every pose parameter at Source's spawn value: cycles
    /// per second at playback rate 1.
    pub cycles_per_second: f32,
    /// How much each bone follows the sequence; one per bone.
    pub bone_weights: Vec<f32>,
    /// Every frame's pose, each bone's local transform; at least one frame.
    /// A frame between two is blended as `CalcBoneQuaternion` blends:
    /// position linearly, rotation by `QuaternionBlend`.
    pub frames: Vec<Vec<BonePose>>,
}

/// A model's skeleton and every sequence it can play.
#[derive(Debug, Clone, PartialEq)]
pub struct Animation {
    pub bones: Vec<Bone>,
    pub sequences: Vec<Sequence>,
    /// `numlocalnodes`; the transition table is `nodes * nodes` bytes, row
    /// `from - 1`, column `to - 1`.
    pub nodes: usize,
    pub transitions: Vec<u8>,
    /// What the reader met and could not reproduce, for diagnostics: include
    /// models, local hierarchy, IK rules and the like.
    pub unsupported: Vec<String>,
}

impl Animation {
    /// `LookupSequence`: the sequence whose label matches, ignoring ASCII
    /// case; failing that, every sequence of the activity of that name, one
    /// of which `SelectWeightedSequence` picks. Empty for neither.
    pub fn lookup(&self, name: &str) -> Vec<usize> {
        if let Some(index) = self
            .sequences
            .iter()
            .position(|s| s.label.eq_ignore_ascii_case(name))
        {
            return vec![index];
        }
        self.activity(name)
    }

    /// Every sequence of one activity, by name.
    pub fn activity(&self, name: &str) -> Vec<usize> {
        if name.is_empty() {
            return Vec::new();
        }
        self.sequences
            .iter()
            .enumerate()
            .filter(|(_, s)| s.activity.eq_ignore_ascii_case(name))
            .map(|(i, _)| i)
            .collect()
    }

    /// The sequence a prop spawns in: its `DefaultAnim` when the model has it,
    /// else sequence 0. `CDynamicProp::Spawn` looks the default up, and a
    /// prop without one keeps `m_nSequence` 0.
    pub fn spawn_sequence(&self, default_anim: &str) -> usize {
        self.lookup(default_anim.trim())
            .first()
            .copied()
            .unwrap_or(0)
    }

    /// The sequences a prop can come to play: sequence 0, every one the map
    /// asks for by name, the idle ones a random animator picks from, and,
    /// when the model has a transition graph, the sequences between its
    /// nodes, which `GotoSequence` plays on the way.
    pub fn reachable<'a>(
        &self,
        names: impl IntoIterator<Item = &'a str>,
        random: bool,
    ) -> std::collections::BTreeSet<usize> {
        let mut out = std::collections::BTreeSet::from([0]);
        for name in names {
            out.extend(self.lookup(name));
        }
        if random {
            out.extend(self.activity("ACT_IDLE"));
        }
        if self.nodes > 0 {
            out.extend(
                self.sequences
                    .iter()
                    .enumerate()
                    .filter(|(_, s)| s.entry_node != s.exit_node)
                    .map(|(i, _)| i),
            );
        }
        out.retain(|&i| i < self.sequences.len());
        out
    }

    /// Where a sequence leaves the prop once it has played: a sequence that
    /// stops holds its last frame, one that loops is where it starts.
    pub fn settled(&self, sequence: usize) -> &[BonePose] {
        let s = &self.sequences[sequence];
        if s.flags & STUDIO_LOOPING != 0 {
            &s.frames[0]
        } else {
            s.frames.last().expect("a sequence has a frame")
        }
    }

    /// Each bone's skinning transform for a pose: bone to model, after the
    /// model to bone of the reference pose the vertices were bound in.
    pub fn skinning(&self, pose: &[BonePose]) -> Vec<[[f64; 4]; 3]> {
        bone_to_model(&self.bones, pose)
            .iter()
            .zip(&self.bones)
            .map(|(m, bone)| multiply(m, &widen(&bone.pose_to_bone)))
            .collect()
    }
}

/// `model` drawn in `pose`, as the studio renderer skins it: every corner
/// moved by its bones' weighted transforms. `parts` are turned by `root`; the
/// posed copy is in the model's own space, so its `root` is the identity.
pub fn pose_model(
    model: &crate::source::mdl::Model,
    animation: &Animation,
    pose: &[BonePose],
) -> crate::source::mdl::Model {
    use crate::geom::{Aabb, Vec3};
    let skin = animation.skinning(pose);
    let r = model.root;
    // R^-1 = R^T for a rotation.
    let unturn = |v: Vec3| {
        Vec3::new(
            r[0][0] * v.x + r[1][0] * v.y + r[2][0] * v.z,
            r[0][1] * v.x + r[1][1] * v.y + r[2][1] * v.z,
            r[0][2] * v.x + r[1][2] * v.y + r[2][2] * v.z,
        )
    };
    let apply = |weights: &crate::source::mdl::Weights, v: Vec3, direction: bool| -> Vec3 {
        let mut out = [0.0; 3];
        let mut total = 0.0;
        for &(bone, weight) in weights {
            let Some(m) = skin.get(usize::from(bone)).filter(|_| weight > 0.0) else {
                continue;
            };
            let w = f64::from(weight);
            total += w;
            for (i, value) in out.iter_mut().enumerate() {
                *value += w
                    * (m[i][0] * v.x
                        + m[i][1] * v.y
                        + m[i][2] * v.z
                        + if direction { 0.0 } else { m[i][3] });
            }
        }
        if total <= 0.0 {
            return v;
        }
        Vec3::new(out[0], out[1], out[2])
    };
    let mut posed = model.clone();
    let mut bounds = Aabb::empty();
    for part in &mut posed.parts {
        for (t, tri) in part.triangles.iter_mut().enumerate() {
            let weights = part.weights.get(t).copied().unwrap_or_default();
            for c in 0..3 {
                tri[c] = apply(&weights[c], unturn(tri[c]), false);
                bounds.extend(tri[c]);
                let n = apply(&weights[c], unturn(part.normals[t][c]), true);
                part.normals[t][c] = if n.length() > 1e-12 {
                    n.normalized()
                } else {
                    n
                };
            }
        }
    }
    posed.bounds = bounds;
    posed.root = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
    posed
}

/// A bone at rest with no compression scales, for tests elsewhere.
#[cfg(test)]
pub fn test_bone() -> Bone {
    Bone {
        name: String::new(),
        parent: -1,
        pos: [0.0; 3],
        quat: [0.0, 0.0, 0.0, 1.0],
        rot: [0.0; 3],
        pos_scale: [1.0; 3],
        rot_scale: [1.0; 3],
        pose_to_bone: [
            [1.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0],
            [0.0, 0.0, 1.0, 0.0],
        ],
        alignment: [0.0, 0.0, 0.0, 1.0],
        flags: 0,
    }
}

/// A little-endian view that answers `None` past its end.
#[derive(Clone, Copy)]
struct Bytes<'a>(&'a [u8]);

impl<'a> Bytes<'a> {
    fn get<const N: usize>(&self, at: usize) -> Option<[u8; N]> {
        self.0.get(at..at.checked_add(N)?)?.try_into().ok()
    }
    fn u8(&self, at: usize) -> Option<u8> {
        self.0.get(at).copied()
    }
    fn i16(&self, at: usize) -> Option<i16> {
        self.get::<2>(at).map(i16::from_le_bytes)
    }
    fn u16(&self, at: usize) -> Option<u16> {
        self.get::<2>(at).map(u16::from_le_bytes)
    }
    fn i32(&self, at: usize) -> Option<i32> {
        self.get::<4>(at).map(i32::from_le_bytes)
    }
    fn u32(&self, at: usize) -> Option<u32> {
        self.get::<4>(at).map(u32::from_le_bytes)
    }
    fn f32(&self, at: usize) -> Option<f32> {
        self.get::<4>(at).map(f32::from_le_bytes)
    }
    fn vec3(&self, at: usize) -> Option<[f32; 3]> {
        Some([self.f32(at)?, self.f32(at + 4)?, self.f32(at + 8)?])
    }
    fn quat(&self, at: usize) -> Option<[f32; 4]> {
        Some([
            self.f32(at)?,
            self.f32(at + 4)?,
            self.f32(at + 8)?,
            self.f32(at + 12)?,
        ])
    }
    /// A NUL-terminated string at `base + relative`, the way every `sz*index`
    /// is stored.
    fn string(&self, base: usize, relative: i32) -> Option<String> {
        let at = offset(base, relative)?;
        let tail = self.0.get(at..)?;
        let end = tail.iter().position(|&b| b == 0)?;
        Some(String::from_utf8_lossy(&tail[..end]).into_owned())
    }
}

fn offset(base: usize, relative: i32) -> Option<usize> {
    usize::try_from(base as i64 + i64::from(relative)).ok()
}

fn count(value: i32, max: usize) -> Option<usize> {
    let value = usize::try_from(value).ok()?;
    (value <= max).then_some(value)
}

/// Reads the animation of the model at `path`, its `.ani` file included,
/// from the search path. `None` when it has no bones or cannot be read.
pub fn read(vfs: &Vfs, path: &str) -> Option<Animation> {
    let key = path.to_ascii_lowercase().replace('\\', "/");
    let mdl = vfs.open(&key)?;
    let header = Bytes(&mdl);
    let ani_name = header.string(0, header.i32(348)?).unwrap_or_default();
    let ani = if header.i32(352)? > 1 && !ani_name.is_empty() {
        vfs.open(&ani_name.to_ascii_lowercase().replace('\\', "/"))
    } else {
        None
    };
    parse(&mdl, ani.as_deref())
}

/// [`read`] over the bytes of the `.mdl` and, when it has one, its `.ani`.
pub fn parse(mdl: &[u8], ani: Option<&[u8]>) -> Option<Animation> {
    let file = Bytes(mdl);
    if &mdl.get(0..4)? != b"IDST" {
        return None;
    }
    let mut unsupported = Vec::new();

    let bone_count = count(file.i32(156)?, MAX_BONES)?;
    let bone_index = usize::try_from(file.i32(160)?).ok()?;
    if bone_count == 0 {
        return None;
    }
    let mut bones = Vec::with_capacity(bone_count);
    for i in 0..bone_count {
        let at = bone_index + i * BONE_SIZE;
        let mut pose_to_bone = [[0.0; 4]; 3];
        for (row, values) in pose_to_bone.iter_mut().enumerate() {
            for (column, value) in values.iter_mut().enumerate() {
                *value = file.f32(at + 96 + (row * 4 + column) * 4)?;
            }
        }
        let parent = file.i32(at + 4)?;
        if parent >= i as i32 || parent < -1 {
            return None;
        }
        bones.push(Bone {
            name: file.string(at, file.i32(at)?).unwrap_or_default(),
            parent,
            pos: file.vec3(at + 32)?,
            quat: file.quat(at + 44)?,
            rot: file.vec3(at + 60)?,
            pos_scale: file.vec3(at + 72)?,
            rot_scale: file.vec3(at + 84)?,
            pose_to_bone,
            alignment: file.quat(at + 144)?,
            flags: file.u32(at + 160)?,
        });
        if file.i32(at + 164)? != 0 {
            unsupported.push(format!("procedural bone {i}"));
        }
    }

    if file.i32(336)? > 0 {
        unsupported.push(format!("{} include models", file.i32(336)?));
    }

    let anim_count = count(file.i32(180)?, MAX_SEQUENCES)?;
    let anim_index = usize::try_from(file.i32(184)?).ok()?;
    let seq_count = count(file.i32(188)?, MAX_SEQUENCES)?;
    let seq_index = usize::try_from(file.i32(192)?).ok()?;
    let blocks = AnimBlocks::read(file, ani);

    let poses = PoseParameters::read(file)?;
    let mut sequences = Vec::with_capacity(seq_count);
    let mut total_poses = 0usize;
    for s in 0..seq_count {
        let at = seq_index + s * SEQDESC_SIZE;
        let sequence = read_sequence(
            file,
            &blocks,
            &bones,
            &poses,
            at,
            anim_index,
            anim_count,
            &mut unsupported,
        )?;
        total_poses = total_poses.checked_add(sequence.frames.len() * bones.len())?;
        if total_poses > MAX_POSES {
            return None;
        }
        sequences.push(sequence);
    }

    let nodes = count(file.i32(248)?, 256)?;
    let transitions = if nodes > 0 {
        let at = usize::try_from(file.i32(252)?).ok()?;
        mdl.get(at..at + nodes * nodes)?.to_vec()
    } else {
        Vec::new()
    };

    Some(Animation {
        bones,
        sequences,
        nodes,
        transitions,
        unsupported,
    })
}

/// The `.ani` blocks of a model: block 0 is the `.mdl` itself.
struct AnimBlocks<'a> {
    mdl: Bytes<'a>,
    ani: Option<Bytes<'a>>,
    /// `datastart` of each block, index 0 unused.
    starts: Vec<usize>,
}

impl<'a> AnimBlocks<'a> {
    fn read(mdl: Bytes<'a>, ani: Option<&'a [u8]>) -> AnimBlocks<'a> {
        let count = mdl.i32(352).and_then(|n| self::count(n, 4096)).unwrap_or(0);
        let index = mdl
            .i32(356)
            .and_then(|n| usize::try_from(n).ok())
            .unwrap_or(0);
        let starts = (0..count)
            .map(|i| {
                mdl.i32(index + i * 8)
                    .and_then(|n| usize::try_from(n).ok())
                    .unwrap_or(usize::MAX)
            })
            .collect();
        AnimBlocks {
            mdl,
            ani: ani.map(Bytes),
            starts,
        }
    }

    /// `mstudioanimdesc_t::pAnimBlock`: where block `block`'s data at
    /// `index` starts. Block 0 is relative to the animation descriptor.
    fn locate(&self, desc: usize, block: i32, index: i32) -> Option<(Bytes<'a>, usize)> {
        match block {
            -1 => None,
            0 => Some((self.mdl, offset(desc, index)?)),
            _ => {
                let start = *self.starts.get(usize::try_from(block).ok()?)?;
                if start == usize::MAX {
                    return None;
                }
                Some((self.ani?, offset(start, index)?))
            }
        }
    }
}

/// The pose parameters, at the value `CBaseAnimating` spawns them with.
struct PoseParameters {
    /// Each parameter's normalized value for a raw value of 0, as
    /// `Studio_SetPoseParameter` stores it.
    values: Vec<f32>,
    ranges: Vec<(f32, f32, f32)>,
}

impl PoseParameters {
    fn read(file: Bytes) -> Option<PoseParameters> {
        let count = count(file.i32(300)?, 64)?;
        let index = usize::try_from(file.i32(304)?).ok()?;
        let mut values = Vec::with_capacity(count);
        let mut ranges = Vec::with_capacity(count);
        for i in 0..count {
            let at = index + i * POSEPARAM_SIZE;
            let (start, end, looping) = (file.f32(at + 8)?, file.f32(at + 12)?, file.f32(at + 16)?);
            let mut value = 0.0f32;
            if looping != 0.0 {
                let wrap = (start + end) / 2.0 + looping / 2.0;
                let shift = looping - wrap;
                value -= looping * ((value + shift) / looping).floor();
            }
            let span = end - start;
            let normalized = if span == 0.0 {
                0.0
            } else {
                ((value - start) / span).clamp(0.0, 1.0)
            };
            values.push(normalized);
            ranges.push((start, end, looping));
        }
        Some(PoseParameters { values, ranges })
    }

    /// `Studio_LocalPoseParameter`: the blend index and fraction along one of
    /// a sequence's two blend axes.
    fn local(&self, file: Bytes, seq: usize, axis: usize) -> Option<(usize, f32)> {
        let param = file.i32(seq + 76 + axis * 4)?;
        let Some(pose) = usize::try_from(param)
            .ok()
            .filter(|&p| p < self.values.len())
        else {
            return Some((0, 0.0));
        };
        let (start, end, looping) = self.ranges[pose];
        let mut value = self.values[pose];
        if looping != 0.0 {
            let wrap = (start + end) / 2.0 + looping / 2.0;
            let shift = looping - wrap;
            value -= looping * ((value + shift) / looping).floor();
        }
        let group = file.i32(seq + 68 + axis * 4)?.max(1) as usize;
        if file.i32(seq + 160)? != 0 {
            // Pose keys: find the pair of blends around the value.
            let key_at = offset(seq, file.i32(seq + 160)?)?;
            let key = |i: usize| file.f32(key_at + (axis * group + i) * 4);
            if group < 2 {
                return Some((0, 0.0));
            }
            let mut index = 0;
            let mut setting = 0.0;
            for i in 0..group - 1 {
                let (a, b) = (key(i)?, key(i + 1)?);
                if b >= value || i == group - 2 {
                    index = i;
                    setting = if b == a {
                        0.0
                    } else {
                        ((value - a) / (b - a)).clamp(0.0, 1.0)
                    };
                    break;
                }
            }
            return Some((index, setting));
        }
        let span = end - start;
        if span == 0.0 {
            return Some((0, 0.0));
        }
        let local_start = (file.f32(seq + 84 + axis * 4)? - start) / span;
        let local_end = (file.f32(seq + 92 + axis * 4)? - start) / span;
        let mut setting = if local_end == local_start {
            0.0
        } else {
            ((value - local_start) / (local_end - local_start)).clamp(0.0, 1.0)
        };
        let mut index = 0;
        if group > 2 {
            index = (setting * (group - 1) as f32) as usize;
            if index == group - 1 {
                index = group - 2;
            }
            setting = setting * (group - 1) as f32 - index as f32;
        }
        Some((index, setting))
    }
}

#[allow(clippy::too_many_arguments)]
fn read_sequence(
    file: Bytes,
    blocks: &AnimBlocks,
    bones: &[Bone],
    poses: &PoseParameters,
    at: usize,
    anim_index: usize,
    anim_count: usize,
    unsupported: &mut Vec<String>,
) -> Option<Sequence> {
    let label = file.string(at, file.i32(at + 4)?).unwrap_or_default();
    let activity = file.string(at, file.i32(at + 8)?).unwrap_or_default();
    let flags = file.u32(at + 12)?;
    let activity_weight = file.i32(at + 20)?;
    let groups = [
        file.i32(at + 68)?.max(1) as usize,
        file.i32(at + 72)?.max(1) as usize,
    ];
    let blends_at = offset(at, file.i32(at + 60)?)?;
    // `mstudioseqdesc_t::anim`, clamped to the grid as Source clamps it.
    let anim = |x: usize, y: usize| -> Option<usize> {
        let (x, y) = (x.min(groups[0] - 1), y.min(groups[1] - 1));
        let value = file.i16(blends_at + (y * groups[0] + x) * 2)?;
        usize::try_from(value).ok().filter(|&a| a < anim_count)
    };
    let weights_at = offset(at, file.i32(at + 156)?)?;
    let bone_weights = (0..bones.len())
        .map(|i| file.f32(weights_at + i * 4))
        .collect::<Option<Vec<f32>>>()?;
    for (field, name) in [(144, "IK rules"), (148, "auto layers"), (164, "IK locks")] {
        if file.i32(at + field)? > 0 {
            unsupported.push(format!("{label}: {name}"));
        }
    }

    let (i0, s0) = poses.local(file, at, 0)?;
    let (i1, s1) = poses.local(file, at, 1)?;
    // `Studio_SeqAnims` and its weights, which `Studio_CPS` sums.
    let corners = [
        (anim(i0, i1)?, (1.0 - s0) * (1.0 - s1)),
        (anim(i0 + 1, i1)?, s0 * (1.0 - s1)),
        (anim(i0, i1 + 1)?, (1.0 - s0) * s1),
        (anim(i0 + 1, i1 + 1)?, s0 * s1),
    ];
    let descs: Vec<(AnimDesc, f32)> = corners
        .iter()
        .map(|&(index, weight)| {
            Some((
                AnimDesc::read(file, anim_index + index * ANIMDESC_SIZE)?,
                weight,
            ))
        })
        .collect::<Option<_>>()?;
    let cycles_per_second: f32 = descs
        .iter()
        .filter(|(desc, weight)| *weight > 0.0 && desc.frames > 1)
        .map(|(desc, weight)| desc.fps / (desc.frames - 1) as f32 * weight)
        .sum();
    for (desc, weight) in &descs {
        if *weight > 0.0 && desc.local_hierarchy > 0 {
            unsupported.push(format!("{label}: local hierarchy"));
        }
    }

    // One pose per frame of the longest animation that counts; with a single
    // animation, its own frames exactly. `CalcPoseSingle` blends the corners
    // at the same cycle: first along the first axis, then the second.
    let frame_count = descs
        .iter()
        .filter(|(_, w)| *w > 0.0)
        .map(|(d, _)| d.frames)
        .max()
        .unwrap_or(1)
        .clamp(1, MAX_FRAMES);
    let all_zeros = s0 < 0.001 && s1 < 0.001 && descs[0].0.flags & STUDIO_ALLZEROS != 0;
    let single = s0 < 0.001 && s1 < 0.001;
    let mut frames = Vec::with_capacity(frame_count);
    for f in 0..frame_count {
        let cycle = if frame_count > 1 {
            f as f32 / (frame_count - 1) as f32
        } else {
            0.0
        };
        let pose = if all_zeros {
            bones.iter().map(|b| (b.pos, b.quat)).collect()
        } else if single {
            descs[0].0.pose_at(blocks, bones, &bone_weights, f, 0.0)?
        } else {
            let sample = |corner: usize| descs[corner].0.pose(blocks, bones, &bone_weights, cycle);
            let a = sample(0)?;
            let first = if s0 > 0.001 {
                blend(&a, &sample(1)?, s0)
            } else {
                a
            };
            if s1 > 0.001 {
                let c = sample(2)?;
                let second = if s0 > 0.001 {
                    blend(&c, &sample(3)?, s0)
                } else {
                    c
                };
                blend(&first, &second, s1)
            } else {
                first
            }
        };
        frames.push(pose);
    }

    Some(Sequence {
        label,
        activity,
        activity_weight,
        flags,
        fade_in: file.f32(at + 104)?,
        fade_out: file.f32(at + 108)?,
        entry_node: file.i32(at + 112)?,
        exit_node: file.i32(at + 116)?,
        node_flags: file.i32(at + 120)?,
        cycles_per_second,
        bone_weights,
        frames,
    })
}

/// `SlerpBones` for two full poses of the same sequence.
fn blend(a: &[BonePose], b: &[BonePose], s: f32) -> Vec<BonePose> {
    a.iter()
        .zip(b)
        .map(|(&(pa, qa), &(pb, qb))| {
            (
                std::array::from_fn(|i| pa[i] * (1.0 - s) + pb[i] * s),
                slerp(qa, qb, s),
            )
        })
        .collect()
}

/// One `mstudioanimdesc_t`.
struct AnimDesc {
    at: usize,
    fps: f32,
    flags: u32,
    frames: usize,
    block: i32,
    index: i32,
    section_index: i32,
    section_frames: usize,
    local_hierarchy: i32,
}

impl AnimDesc {
    fn read(file: Bytes, at: usize) -> Option<AnimDesc> {
        Some(AnimDesc {
            at,
            fps: file.f32(at + 8)?,
            flags: file.u32(at + 12)?,
            frames: count(file.i32(at + 16)?, MAX_FRAMES)?.max(1),
            block: file.i32(at + 52)?,
            index: file.i32(at + 56)?,
            local_hierarchy: file.i32(at + 72)?,
            section_index: file.i32(at + 80)?,
            section_frames: count(file.i32(at + 84)?, MAX_FRAMES)?,
        })
    }

    /// `pAnim`: the data holding `frame`, and the frame within it.
    fn data<'a>(&self, blocks: &AnimBlocks<'a>, frame: usize) -> Option<(Bytes<'a>, usize, usize)> {
        let (mut block, mut index, mut frame) = (self.block, self.index, frame);
        if self.section_frames != 0 {
            let section = if self.frames > self.section_frames && frame == self.frames - 1 {
                // The last frame of a long animation is stored on its own.
                frame = 0;
                self.frames / self.section_frames + 1
            } else {
                let section = frame / self.section_frames;
                frame -= section * self.section_frames;
                section
            };
            let entry = offset(self.at, self.section_index)? + section * 8;
            block = blocks.mdl.i32(entry)?;
            index = blocks.mdl.i32(entry + 4)?;
        }
        let (bytes, at) = blocks.locate(self.at, block, index)?;
        Some((bytes, at, frame))
    }

    /// `CalcAnimation` at `cycle` for every bone the sequence weights.
    fn pose(
        &self,
        blocks: &AnimBlocks,
        bones: &[Bone],
        weights: &[f32],
        cycle: f32,
    ) -> Option<Vec<BonePose>> {
        let frame = cycle * (self.frames - 1) as f32;
        let whole = (frame as usize).min(self.frames - 1);
        self.pose_at(blocks, bones, weights, whole, frame - whole as f32)
    }

    /// [`Self::pose`] at whole frame `whole` blended `s` of the way to the next.
    fn pose_at(
        &self,
        blocks: &AnimBlocks,
        bones: &[Bone],
        weights: &[f32],
        whole: usize,
        s: f32,
    ) -> Option<Vec<BonePose>> {
        let delta = self.flags & STUDIO_DELTA != 0;
        let rest = |bone: &Bone| -> BonePose {
            if delta {
                ([0.0; 3], [0.0, 0.0, 0.0, 1.0])
            } else {
                (bone.pos, bone.quat)
            }
        };
        let mut pose: Vec<BonePose> = bones.iter().map(|b| (b.pos, b.quat)).collect();
        let whole = whole.min(self.frames - 1);
        let Some(first) = self.at_frame(blocks, bones, whole) else {
            // No data: the bones the sequence weights stay as `InitPose`.
            for (i, bone) in bones.iter().enumerate() {
                if weights[i] > 0.0 {
                    pose[i] = rest(bone);
                }
            }
            return Some(pose);
        };
        let second = if s > 0.001 && whole + 1 < self.frames {
            self.at_frame(blocks, bones, whole + 1)
        } else {
            None
        };
        for (i, bone) in bones.iter().enumerate() {
            if weights[i] <= 0.0 {
                continue;
            }
            let a = first[i].unwrap_or_else(|| rest(bone));
            pose[i] = match second
                .as_ref()
                .map(|next| next[i].unwrap_or_else(|| rest(bone)))
            {
                Some(b) => (
                    std::array::from_fn(|k| a.0[k] * (1.0 - s) + b.0[k] * s),
                    quaternion_blend(a.1, b.1, s),
                ),
                None => a,
            };
        }
        Some(pose)
    }

    /// Every bone's local transform at one whole frame; `None` for a bone the
    /// animation has no data for.
    fn at_frame(
        &self,
        blocks: &AnimBlocks,
        bones: &[Bone],
        frame: usize,
    ) -> Option<Vec<Option<BonePose>>> {
        let (bytes, mut at, local) = self.data(blocks, frame)?;
        let mut out = vec![None; bones.len()];
        // The list is sorted by bone; a cycle in `nextoffset` cannot be
        // followed further than there are bones.
        for _ in 0..=bones.len() {
            let bone = usize::from(bytes.u8(at)?);
            let flags = bytes.u8(at + 1)?;
            let next = bytes.i16(at + 2)?;
            if let Some(info) = bones.get(bone) {
                out[bone] = Some((
                    position(bytes, at + 4, flags, local, info)?,
                    rotation(bytes, at + 4, flags, local, info)?,
                ));
            }
            if next == 0 {
                break;
            }
            at = offset(at, i32::from(next))?;
        }
        Some(out)
    }
}

/// `CalcBoneQuaternion` with no sub-frame blend.
fn rotation(bytes: Bytes, data: usize, flags: u8, frame: usize, bone: &Bone) -> Option<[f32; 4]> {
    if flags & STUDIO_ANIM_RAWROT != 0 {
        return quaternion48(bytes, data);
    }
    if flags & STUDIO_ANIM_RAWROT2 != 0 {
        return quaternion64(bytes, data);
    }
    let delta = flags & STUDIO_ANIM_DELTA != 0;
    if flags & STUDIO_ANIM_ANIMROT == 0 {
        return Some(if delta {
            [0.0, 0.0, 0.0, 1.0]
        } else {
            bone.quat
        });
    }
    let mut angle = [0.0f32; 3];
    for (axis, value) in angle.iter_mut().enumerate() {
        *value = anim_value(bytes, data, axis, frame)? * bone.rot_scale[axis];
        if !delta {
            *value += bone.rot[axis];
        }
    }
    let mut q = angle_quaternion(angle);
    if !delta && bone.flags & BONE_FIXED_ALIGNMENT != 0 {
        q = align(bone.alignment, q);
    }
    Some(q)
}

/// `CalcBonePosition` with no sub-frame blend.
fn position(bytes: Bytes, data: usize, flags: u8, frame: usize, bone: &Bone) -> Option<[f32; 3]> {
    if flags & STUDIO_ANIM_RAWPOS != 0 {
        // `pPos`: after a raw rotation, if there is one.
        let skip = if flags & STUDIO_ANIM_RAWROT != 0 {
            6
        } else if flags & STUDIO_ANIM_RAWROT2 != 0 {
            8
        } else {
            0
        };
        return vector48(bytes, data + skip);
    }
    let delta = flags & STUDIO_ANIM_DELTA != 0;
    if flags & STUDIO_ANIM_ANIMPOS == 0 {
        return Some(if delta { [0.0; 3] } else { bone.pos });
    }
    // `pPosV`: after the rotation's value pointers, if there are any.
    let values = data
        + if flags & STUDIO_ANIM_ANIMROT != 0 {
            6
        } else {
            0
        };
    let mut pos = [0.0f32; 3];
    for (axis, value) in pos.iter_mut().enumerate() {
        *value = anim_value(bytes, values, axis, frame)? * bone.pos_scale[axis];
        if !delta {
            *value += bone.pos[axis];
        }
    }
    Some(pos)
}

/// `ExtractAnimValue` for one axis of an `mstudioanim_valueptr_t` at `pointer`.
fn anim_value(bytes: Bytes, pointer: usize, axis: usize, frame: usize) -> Option<f32> {
    let relative = bytes.i16(pointer + axis * 2)?;
    if relative <= 0 {
        return Some(0.0);
    }
    let mut at = pointer + relative as usize;
    let mut k = frame;
    // Each run: `valid` stored values then `total` frames; a frame past the
    // valid ones repeats the last.
    for _ in 0..MAX_FRAMES {
        let valid = usize::from(bytes.u8(at)?);
        let total = usize::from(bytes.u8(at + 1)?);
        if total == 0 {
            return Some(0.0);
        }
        if total > k {
            let slot = if valid > k { k + 1 } else { valid };
            return Some(f32::from(bytes.i16(at + slot * 2)?));
        }
        k -= total;
        at += (valid + 1) * 2;
    }
    None
}

fn quaternion48(bytes: Bytes, at: usize) -> Option<[f32; 4]> {
    let (x, y, zw) = (bytes.u16(at)?, bytes.u16(at + 2)?, bytes.u16(at + 4)?);
    let x = (f32::from(x) - 32768.0) * (1.0 / 32768.0);
    let y = (f32::from(y) - 32768.0) * (1.0 / 32768.0);
    let z = (f32::from(zw & 0x7FFF) - 16384.0) * (1.0 / 16384.0);
    let mut w = (1.0 - x * x - y * y - z * z).max(0.0).sqrt();
    if zw & 0x8000 != 0 {
        w = -w;
    }
    Some([x, y, z, w])
}

fn quaternion64(bytes: Bytes, at: usize) -> Option<[f32; 4]> {
    let bits = u64::from_le_bytes(bytes.get::<8>(at)?);
    let part = |shift: u32| ((bits >> shift) & 0x1F_FFFF) as f32;
    let x = (part(0) - 1_048_576.0) * (1.0 / 1_048_576.5);
    let y = (part(21) - 1_048_576.0) * (1.0 / 1_048_576.5);
    let z = (part(42) - 1_048_576.0) * (1.0 / 1_048_576.5);
    let mut w = (1.0 - x * x - y * y - z * z).max(0.0).sqrt();
    if bits >> 63 != 0 {
        w = -w;
    }
    Some([x, y, z, w])
}

fn vector48(bytes: Bytes, at: usize) -> Option<[f32; 3]> {
    Some([
        float16(bytes.u16(at)?),
        float16(bytes.u16(at + 2)?),
        float16(bytes.u16(at + 4)?),
    ])
}

/// `float16::Convert16bitFloatTo32bits`: IEEE half, but infinity is the
/// largest half and NaN is zero.
fn float16(raw: u16) -> f32 {
    let sign = if raw & 0x8000 != 0 { -1.0 } else { 1.0 };
    let exponent = (raw >> 10) & 0x1F;
    let mantissa = raw & 0x3FF;
    match (exponent, mantissa) {
        (31, 0) => sign * 65504.0,
        (31, _) => 0.0,
        (0, 0) => sign * 0.0,
        (0, m) => sign * f32::from(m) / 1024.0 / 16384.0,
        (e, m) => f32::from_bits(
            (u32::from(raw & 0x8000) << 16) | ((u32::from(e) + 112) << 23) | (u32::from(m) << 13),
        ),
    }
}

/// `AngleQuaternion(RadianEuler)`: x roll, y pitch, z yaw.
pub fn angle_quaternion(angle: [f32; 3]) -> [f32; 4] {
    let (sy, cy) = (angle[2] * 0.5).sin_cos();
    let (sp, cp) = (angle[1] * 0.5).sin_cos();
    let (sr, cr) = (angle[0] * 0.5).sin_cos();
    let (sr_cp, cr_sp) = (sr * cp, cr * sp);
    let (cr_cp, sr_sp) = (cr * cp, sr * sp);
    [
        sr_cp * cy - cr_sp * sy,
        cr_sp * cy + sr_cp * sy,
        cr_cp * sy - sr_sp * cy,
        cr_cp * cy + sr_sp * sy,
    ]
}

/// `QuaternionAlign`: `q`, or `-q` if that is nearer `p`.
pub fn align(p: [f32; 4], q: [f32; 4]) -> [f32; 4] {
    let (mut a, mut b) = (0.0, 0.0);
    for i in 0..4 {
        a += (p[i] - q[i]) * (p[i] - q[i]);
        b += (p[i] + q[i]) * (p[i] + q[i]);
    }
    if a > b { q.map(|v| -v) } else { q }
}

fn normalize(q: [f32; 4]) -> [f32; 4] {
    let length = q.iter().map(|v| v * v).sum::<f32>().sqrt();
    if length > 0.0 {
        q.map(|v| v / length)
    } else {
        q
    }
}

/// `QuaternionBlend`: aligned, linear, normalized.
pub fn quaternion_blend(p: [f32; 4], q: [f32; 4], t: f32) -> [f32; 4] {
    let q = align(p, q);
    normalize(std::array::from_fn(|i| (1.0 - t) * p[i] + t * q[i]))
}

/// `QuaternionSlerp`.
pub fn slerp(p: [f32; 4], q: [f32; 4], t: f32) -> [f32; 4] {
    let q = align(p, q);
    let cosom: f32 = (0..4).map(|i| p[i] * q[i]).sum();
    if 1.0 + cosom > 0.000_001 {
        let (sclp, sclq) = if 1.0 - cosom > 0.000_001 {
            let omega = cosom.acos();
            let sinom = omega.sin();
            (((1.0 - t) * omega).sin() / sinom, (t * omega).sin() / sinom)
        } else {
            (1.0 - t, t)
        };
        std::array::from_fn(|i| sclp * p[i] + sclq * q[i])
    } else {
        let mut qt = [-q[1], q[0], -q[3], q[2]];
        let sclp = ((1.0 - t) * 0.5 * std::f32::consts::PI).sin();
        let sclq = (t * 0.5 * std::f32::consts::PI).sin();
        for i in 0..3 {
            qt[i] = sclp * p[i] + sclq * qt[i];
        }
        qt
    }
}

/// How many solids a model's `.phy` has; 0 when it has none. More than one
/// makes `CDynamicProp` follow the animated bones with physics objects of
/// their own (`m_BoneFollowerManager`).
pub fn physics_solids(vfs: &Vfs, path: &str) -> usize {
    let key = path.to_ascii_lowercase().replace('\\', "/");
    let stem = key.strip_suffix(".mdl").unwrap_or(&key);
    vfs.open(&format!("{stem}.phy"))
        .and_then(|phy| Bytes(&phy).i32(8))
        .and_then(|n| usize::try_from(n).ok())
        .unwrap_or(0)
}

/// Every bone's transform from bone space to model space for one pose, as
/// `BuildBoneChain` composes them, row-major 3x4.
pub fn bone_to_model(bones: &[Bone], pose: &[BonePose]) -> Vec<[[f64; 4]; 3]> {
    let mut out: Vec<[[f64; 4]; 3]> = Vec::with_capacity(bones.len());
    for (i, bone) in bones.iter().enumerate() {
        let local = matrix(pose[i]);
        let world = match usize::try_from(bone.parent).ok().and_then(|p| out.get(p)) {
            Some(parent) => multiply(parent, &local),
            None => local,
        };
        out.push(world);
    }
    out
}

/// `QuaternionMatrix(q, pos)`.
pub fn matrix((pos, q): BonePose) -> [[f64; 4]; 3] {
    let [x, y, z, w] = q.map(f64::from);
    [
        [
            1.0 - 2.0 * y * y - 2.0 * z * z,
            2.0 * x * y - 2.0 * w * z,
            2.0 * x * z + 2.0 * w * y,
            f64::from(pos[0]),
        ],
        [
            2.0 * x * y + 2.0 * w * z,
            1.0 - 2.0 * x * x - 2.0 * z * z,
            2.0 * y * z - 2.0 * w * x,
            f64::from(pos[1]),
        ],
        [
            2.0 * x * z - 2.0 * w * y,
            2.0 * y * z + 2.0 * w * x,
            1.0 - 2.0 * x * x - 2.0 * y * y,
            f64::from(pos[2]),
        ],
    ]
}

/// `ConcatTransforms`: `a * b` for 3x4 affine matrices.
pub fn multiply(a: &[[f64; 4]; 3], b: &[[f64; 4]; 3]) -> [[f64; 4]; 3] {
    let mut out = [[0.0; 4]; 3];
    for r in 0..3 {
        for c in 0..4 {
            out[r][c] =
                (0..3).map(|k| a[r][k] * b[k][c]).sum::<f64>() + if c == 3 { a[r][3] } else { 0.0 };
        }
    }
    out
}

pub fn widen(m: &[[f32; 4]; 3]) -> [[f64; 4]; 3] {
    m.map(|row| row.map(f64::from))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn half_floats_decode_as_source_does() {
        assert_eq!(float16(0x3C00), 1.0);
        assert_eq!(float16(0xC000), -2.0);
        assert_eq!(float16(0x7C00), 65504.0);
        assert_eq!(float16(0x7C01), 0.0);
        assert_eq!(float16(0x0001), 1.0 / 1024.0 / 16384.0);
    }

    #[test]
    fn euler_angles_turn_as_source_turns_them() {
        // A quarter turn of yaw (z).
        let q = angle_quaternion([0.0, 0.0, std::f32::consts::FRAC_PI_2]);
        let m = matrix(([0.0; 3], q));
        assert!((m[1][0] - 1.0).abs() < 1e-6, "x turns onto y: {m:?}");
    }

    /// Runs of `valid` values covering `total` frames, the last value
    /// repeating; signed, so a negative angle stays negative.
    #[test]
    fn value_runs_repeat_and_stay_signed() {
        // Pointer: x at +6, y and z absent.
        let mut data = vec![];
        for v in [6i16, 0, 0] {
            data.extend(v.to_le_bytes());
        }
        // Run: 2 valid of 3 frames: -5, 7; then 1 valid of 300 frames: 9.
        data.extend([2u8, 3]);
        data.extend((-5i16).to_le_bytes());
        data.extend(7i16.to_le_bytes());
        data.extend([1u8, 255]);
        data.extend(9i16.to_le_bytes());
        data.extend([1u8, 45]);
        data.extend(11i16.to_le_bytes());
        data.extend([0u8, 0]);
        let bytes = Bytes(&data);
        assert_eq!(anim_value(bytes, 0, 0, 0), Some(-5.0));
        assert_eq!(anim_value(bytes, 0, 0, 1), Some(7.0));
        assert_eq!(anim_value(bytes, 0, 0, 2), Some(7.0));
        assert_eq!(anim_value(bytes, 0, 0, 3), Some(9.0));
        assert_eq!(anim_value(bytes, 0, 0, 257), Some(9.0));
        assert_eq!(anim_value(bytes, 0, 0, 258), Some(11.0));
        assert_eq!(anim_value(bytes, 0, 1, 2), Some(0.0));
    }
}

#[cfg(test)]
mod survey {
    use super::*;
    use std::path::{Path, PathBuf};

    fn deviation(mesh: &crate::source::mdl::Model, anim: &Animation) -> f64 {
        let rest = bone_to_model(&anim.bones, &anim.sequences[0].frames[0]);
        let skin: Vec<[[f64; 4]; 3]> = anim
            .bones
            .iter()
            .zip(&rest)
            .map(|(b, m)| multiply(m, &widen(&b.pose_to_bone)))
            .collect();
        let r = mesh.root;
        let mut worst = 0.0f64;
        for part in &mesh.parts {
            for (tri, weights) in part.triangles.iter().zip(&part.weights) {
                for c in 0..3 {
                    let v = tri[c];
                    let raw = [0, 1, 2].map(|i| r[0][i] * v.x + r[1][i] * v.y + r[2][i] * v.z);
                    let mut posed = [0.0; 3];
                    for &(bone, w) in &weights[c] {
                        let Some(m) = skin.get(usize::from(bone)).filter(|_| w > 0.0) else {
                            continue;
                        };
                        for i in 0..3 {
                            posed[i] += f64::from(w)
                                * (m[i][0] * raw[0]
                                    + m[i][1] * raw[1]
                                    + m[i][2] * raw[2]
                                    + m[i][3]);
                        }
                    }
                    worst = worst.max(
                        ((posed[0] - v.x).powi(2)
                            + (posed[1] - v.y).powi(2)
                            + (posed[2] - v.z).powi(2))
                        .sqrt(),
                    );
                }
            }
        }
        worst
    }

    const STEAM: &str = "/mnt/games/SteamLibrary/steamapps/common";

    fn maps() -> Vec<String> {
        let infra = format!("{STEAM}/infra/infra/pak02_dir.vpk:maps/");
        let mut maps: Vec<String> = [
            "infra_c4_m2_furnace",
            "infra_c3_m4_tunnel4",
            "infra_c6_m2_metro",
            "infra_c6_m4_waterplant",
        ]
        .iter()
        .map(|m| format!("{infra}{m}.bsp"))
        .collect();
        for m in ["escape_02", "testchmb_a_00"] {
            maps.push(format!("{STEAM}/Portal/portal/maps/{m}.bsp"));
        }
        for m in ["sp_a1_wakeup", "sp_a2_bts4", "sp_a3_01", "sp_a3_end"] {
            maps.push(format!("{STEAM}/Portal 2/portal2/maps/{m}.bsp"));
        }
        maps
    }

    /// Every animated prop of the test maps: what its model needs. Run with
    /// `cargo test --release survey -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn survey_animated_props() {
        for map_path in maps() {
            let Ok(map) = crate::bsp::Map::load(Path::new(&map_path)) else {
                println!("{map_path}: not found");
                continue;
            };
            let vfs = Vfs::for_map(&map.path, &[] as &[PathBuf]);
            let mut models = crate::source::mdl::Models::new(&vfs);
            let lump: Vec<Vec<(String, String)>> = map
                .bsp
                .entities
                .iter()
                .map(|raw| {
                    raw.properties()
                        .map(|(k, v)| (k.to_string(), v.to_string()))
                        .collect()
                })
                .collect();
            let roles = crate::bsp::logic_props::roles_of(&lump);
            // Dynamic props that never animate: Source draws them at sequence 0, frame 0.
            let mut still = std::collections::BTreeMap::new();
            for (index, raw) in lump.iter().enumerate() {
                let get = |key: &str| {
                    raw.iter()
                        .find(|(k, _)| k.eq_ignore_ascii_case(key))
                        .map(|(_, v)| v.to_ascii_lowercase())
                };
                let class = get("classname").unwrap_or_default();
                if !class.starts_with("prop_dynamic")
                    || roles.get(&index).is_some_and(|r| r.animated)
                {
                    continue;
                }
                if let Some(model) = get("model") {
                    *still.entry(model).or_insert(0usize) += 1;
                }
            }
            let mut moved = Vec::new();
            for (path, n) in &still {
                let (Some(mesh), Some(anim)) = (models.get(path), read(&vfs, path)) else {
                    continue;
                };
                let d = deviation(&mesh, &anim);
                if d > 0.5 {
                    moved.push(format!("{n}x {path} {d:.1}u"));
                }
            }
            println!(
                "   still dynamic props: {} models, {} off at sequence 0: {:?}",
                still.len(),
                moved.len(),
                moved
            );
            let mut seen = std::collections::BTreeMap::new();
            for (&index, role) in &roles {
                if !role.animated {
                    continue;
                }
                let model = lump[index]
                    .iter()
                    .find(|(k, _)| k.eq_ignore_ascii_case("model"))
                    .map(|(_, v)| v.to_ascii_lowercase())
                    .unwrap_or_default();
                *seen.entry(model).or_insert(0) += 1;
            }
            println!(
                "== {} ({} animated)",
                map.name,
                seen.values().sum::<usize>()
            );
            for (path, n) in seen {
                let mesh = models.get(&path);
                let anim = read(&vfs, &path);
                let solids = physics_solids(&vfs, &path);
                let Some(anim) = anim else {
                    println!("  {n} {path}: NO ANIMATION (mesh {})", mesh.is_some());
                    continue;
                };
                let frames: usize = anim.sequences.iter().map(|s| s.frames.len()).sum();
                let (mut corners, mut blended) = (0, 0);
                let mut deviation = 0.0f64;
                if let Some(mesh) = &mesh {
                    let rest = bone_to_model(&anim.bones, &anim.sequences[0].frames[0]);
                    let skin: Vec<[[f64; 4]; 3]> = anim
                        .bones
                        .iter()
                        .zip(&rest)
                        .map(|(b, m)| multiply(m, &widen(&b.pose_to_bone)))
                        .collect();
                    let r = mesh.root;
                    for part in &mesh.parts {
                        for (tri, weights) in part.triangles.iter().zip(&part.weights) {
                            for c in 0..3 {
                                corners += 1;
                                if weights[c].iter().filter(|w| w.1 > 0.0).count() > 1 {
                                    blended += 1;
                                }
                                let v = tri[c];
                                // R^-1 = R^T for a rotation.
                                let raw = [0, 1, 2]
                                    .map(|i| r[0][i] * v.x + r[1][i] * v.y + r[2][i] * v.z);
                                let mut posed = [0.0; 3];
                                for &(bone, w) in &weights[c] {
                                    if w <= 0.0 {
                                        continue;
                                    }
                                    let Some(m) = skin.get(usize::from(bone)) else {
                                        continue;
                                    };
                                    for i in 0..3 {
                                        posed[i] += f64::from(w)
                                            * (m[i][0] * raw[0]
                                                + m[i][1] * raw[1]
                                                + m[i][2] * raw[2]
                                                + m[i][3]);
                                    }
                                }
                                let d = ((posed[0] - v.x).powi(2)
                                    + (posed[1] - v.y).powi(2)
                                    + (posed[2] - v.z).powi(2))
                                .sqrt();
                                deviation = deviation.max(d);
                            }
                        }
                    }
                }
                println!(
                    "  {n} {path}: {} bones, {} seqs, {frames} frames, ~{} KiB, blended {blended}/{corners}, phy {solids}, seq0 dev {deviation:.2}u, nodes {}{}",
                    anim.bones.len(),
                    anim.sequences.len(),
                    frames * anim.bones.len() * 28 / 1024,
                    anim.nodes,
                    if anim.unsupported.is_empty() {
                        String::new()
                    } else {
                        format!(
                            ", unsupported: {:?}",
                            anim.unsupported.iter().take(4).collect::<Vec<_>>()
                        )
                    }
                );
            }
        }
    }
}
