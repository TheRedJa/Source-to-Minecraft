//! Versioned skeleton and animation payload of an animated prop's model
//! (format section 19).
//!
//! Everything is in the model-local Minecraft block space its runtime mesh is
//! in: Source `(x, y, z)` is Minecraft `(x, z, -y)`, 32 units to the block.
//! That is a rotation and a uniform scale, so a bone's local transform keeps
//! its form: positions are mapped and scaled, rotations conjugated.

use crate::source::anim::{Animation, BonePose, STUDIO_LOOPING};
use crate::source::mdl::Weights;
use anyhow::{Result, ensure};
use std::collections::BTreeSet;

pub const MAGIC: [u8; 8] = *b"S2ANIM\0\0";
pub const VERSION: u32 = 1;
pub const MAX_BONES: usize = 256;
pub const MAX_SEQUENCES: usize = 4096;
pub const MAX_FRAMES: usize = 65536;
pub const MAX_NAME_BYTES: usize = 1024;

const UNITS: f64 = crate::output::mod_export::UNITS_PER_BLOCK;

/// A Source position in block space.
fn position(p: [f32; 3]) -> [f32; 3] {
    [
        (f64::from(p[0]) / UNITS) as f32,
        (f64::from(p[2]) / UNITS) as f32,
        (-f64::from(p[1]) / UNITS) as f32,
    ]
}

/// A Source rotation turned into block space: the axis mapped, the angle kept.
fn rotation(q: [f32; 4]) -> [f32; 4] {
    [q[0], q[2], -q[1], q[3]]
}

/// A quaternion as four signed 16-bit fractions of one.
fn quantize(q: [f32; 4]) -> [i16; 4] {
    q.map(|v| (v.clamp(-1.0, 1.0) * 32767.0).round() as i16)
}

/// Source model space to Minecraft model space, `A`: `(x, y, z) -> (x, z, -y)`.
const A: [[f64; 3]; 3] = [[1.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, -1.0, 0.0]];

/// A Source 3x4 transform in block space: `A M A^T`, translation `A t / 32`.
fn transform(m: &[[f64; 4]; 3]) -> [[f32; 4]; 3] {
    let mut out = [[0.0f32; 4]; 3];
    for r in 0..3 {
        for c in 0..3 {
            let mut value = 0.0;
            for i in 0..3 {
                for j in 0..3 {
                    value += A[r][i] * m[i][j] * A[c][j];
                }
            }
            out[r][c] = value as f32;
        }
        out[r][3] = ((0..3).map(|i| A[r][i] * m[i][3]).sum::<f64>() / UNITS) as f32;
    }
    out
}

fn put_name(out: &mut Vec<u8>, name: &str) -> Result<()> {
    let bytes = name.as_bytes();
    let bytes = &bytes[..bytes.len().min(MAX_NAME_BYTES)];
    out.extend(u16::try_from(bytes.len())?.to_le_bytes());
    out.extend(bytes);
    Ok(())
}

/// Encodes `animation` for a mesh whose vertices are bound as `bindings`,
/// one per runtime mesh vertex, and whose model-space parts were turned by
/// `root` (see [`crate::source::mdl::Model::root`]). Only the sequences in
/// `keep` carry their frames; every other one keeps its name and timing, so
/// a lookup still finds what Source would.
pub fn encode(
    animation: &Animation,
    root: [[f64; 3]; 3],
    bindings: &[Weights],
    keep: &BTreeSet<usize>,
) -> Result<Vec<u8>> {
    let bones = &animation.bones;
    ensure!(
        !bones.is_empty() && bones.len() <= MAX_BONES,
        "animation bone count out of range"
    );
    ensure!(
        animation.sequences.len() <= MAX_SEQUENCES && !animation.sequences.is_empty(),
        "animation sequence count out of range"
    );
    let mut out = Vec::new();
    out.extend(MAGIC);
    out.extend(VERSION.to_le_bytes());
    out.extend(u32::try_from(bones.len())?.to_le_bytes());
    out.extend(u32::try_from(bindings.len())?.to_le_bytes());
    out.extend(u32::try_from(animation.sequences.len())?.to_le_bytes());
    out.extend(u32::try_from(animation.nodes)?.to_le_bytes());
    for bone in bones {
        out.extend(bone.parent.to_le_bytes());
        for v in position(bone.pos) {
            out.extend(finite(v)?.to_le_bytes());
        }
        for v in quantize(rotation(bone.quat)) {
            out.extend(v.to_le_bytes());
        }
        // Model to bone at the reference pose, after undoing the turn the
        // mesh was given: `P2B R^T`.
        let p2b = crate::source::anim::widen(&bone.pose_to_bone);
        let mut bind = [[0.0f64; 4]; 3];
        for r in 0..3 {
            for c in 0..3 {
                bind[r][c] = (0..3).map(|k| p2b[r][k] * root[c][k]).sum();
            }
            bind[r][3] = p2b[r][3];
        }
        for row in transform(&bind) {
            for v in row {
                out.extend(finite(v)?.to_le_bytes());
            }
        }
    }
    for weights in bindings {
        let used = weights.iter().filter(|(_, w)| *w > 0.0).count();
        let mut slots: Vec<(u8, f32)> = weights.iter().copied().filter(|(_, w)| *w > 0.0).collect();
        slots.resize(3, (0, 0.0));
        out.push(used as u8);
        for (bone, _) in &slots {
            ensure!(
                usize::from(*bone) < bones.len(),
                "a vertex is bound to a bone the model lacks"
            );
            out.push(*bone);
        }
        for (_, weight) in &slots {
            out.extend(finite(*weight)?.to_le_bytes());
        }
    }
    ensure!(
        animation.transitions.len() == animation.nodes * animation.nodes,
        "transition table size mismatch"
    );
    out.extend(&animation.transitions);
    for (index, sequence) in animation.sequences.iter().enumerate() {
        put_name(&mut out, &sequence.label)?;
        put_name(&mut out, &sequence.activity)?;
        out.extend(sequence.activity_weight.to_le_bytes());
        out.extend(sequence.flags.to_le_bytes());
        out.extend(finite(sequence.fade_in)?.to_le_bytes());
        out.extend(finite(sequence.fade_out)?.to_le_bytes());
        out.extend(sequence.entry_node.to_le_bytes());
        out.extend(sequence.exit_node.to_le_bytes());
        out.extend(sequence.node_flags.to_le_bytes());
        out.extend(finite(sequence.cycles_per_second)?.to_le_bytes());
        ensure!(
            sequence.bone_weights.len() == bones.len(),
            "sequence bone weight count mismatch"
        );
        for weight in &sequence.bone_weights {
            out.extend(finite(*weight)?.to_le_bytes());
        }
        let frames: &[Vec<BonePose>] = if keep.contains(&index) {
            &sequence.frames
        } else {
            &[]
        };
        ensure!(frames.len() <= MAX_FRAMES, "sequence has too many frames");
        out.extend(u32::try_from(frames.len())?.to_le_bytes());
        if frames.is_empty() {
            continue;
        }
        for bone in 0..bones.len() {
            let positions: Vec<[f32; 3]> = frames.iter().map(|f| position(f[bone].0)).collect();
            let rotations: Vec<[i16; 4]> = frames
                .iter()
                .map(|f| quantize(rotation(f[bone].1)))
                .collect();
            let still_position = positions.iter().all(|p| *p == positions[0]);
            let still_rotation = rotations.iter().all(|q| *q == rotations[0]);
            out.push(u8::from(!still_position));
            out.push(u8::from(!still_rotation));
            for p in if still_position {
                &positions[..1]
            } else {
                &positions[..]
            } {
                for v in p {
                    out.extend(finite(*v)?.to_le_bytes());
                }
            }
            for q in if still_rotation {
                &rotations[..1]
            } else {
                &rotations[..]
            } {
                for v in q {
                    out.extend(v.to_le_bytes());
                }
            }
        }
    }
    Ok(out)
}

fn finite(value: f32) -> Result<f32> {
    ensure!(value.is_finite(), "animation value is not finite");
    Ok(if value == 0.0 { 0.0 } else { value })
}

/// Whether a sequence loops; for the collision of its settled pose.
pub fn loops(animation: &Animation, sequence: usize) -> bool {
    animation.sequences[sequence].flags & STUDIO_LOOPING != 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source::anim::{Bone, Sequence};

    fn bone(parent: i32) -> Bone {
        let mut bone = crate::source::anim::test_bone();
        bone.parent = parent;
        bone.pos = [32.0, 64.0, 0.0];
        bone.pose_to_bone = [
            [1.0, 0.0, 0.0, -32.0],
            [0.0, 1.0, 0.0, -64.0],
            [0.0, 0.0, 1.0, 0.0],
        ];
        bone
    }

    /// Reads an encoded animation back the way the mod does and poses the
    /// runtime mesh with one sequence's first frame: it must land where
    /// Source's own skinning of the model puts it, in block space.
    fn decode_and_pose(bytes: &[u8], sequence: usize, mesh_vertices: &[[f32; 3]]) -> Vec<[f64; 3]> {
        let mut at = 8;
        let u32_ = |at: &mut usize| {
            let v = u32::from_le_bytes(bytes[*at..*at + 4].try_into().unwrap());
            *at += 4;
            v
        };
        assert_eq!(u32_(&mut at), 1);
        let (bones, vertices, sequences, nodes) = (
            u32_(&mut at) as usize,
            u32_(&mut at) as usize,
            u32_(&mut at) as usize,
            u32_(&mut at) as usize,
        );
        assert_eq!(vertices, mesh_vertices.len());
        let f = |at: &mut usize| {
            let v = f32::from_le_bytes(bytes[*at..*at + 4].try_into().unwrap());
            *at += 4;
            f64::from(v)
        };
        let q16 = |at: &mut usize| {
            let q: [f64; 4] = std::array::from_fn(|i| {
                f64::from(i16::from_le_bytes(
                    bytes[*at + i * 2..*at + i * 2 + 2].try_into().unwrap(),
                )) / 32767.0
            });
            *at += 8;
            let length = q.iter().map(|v| v * v).sum::<f64>().sqrt();
            q.map(|v| v / length)
        };
        let mut parents = vec![];
        let mut binds = vec![];
        for _ in 0..bones {
            parents.push(i32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()));
            at += 4;
            at += 12 + 8;
            let mut bind = [[0.0; 4]; 3];
            for row in &mut bind {
                for v in row.iter_mut() {
                    *v = f(&mut at);
                }
            }
            binds.push(bind);
        }
        let mut weights = vec![];
        for _ in 0..vertices {
            let count = bytes[at] as usize;
            let ids = [bytes[at + 1], bytes[at + 2], bytes[at + 3]];
            at += 4;
            let w: [f64; 3] = std::array::from_fn(|_| f(&mut at));
            weights.push((count, ids, w));
        }
        at += nodes * nodes;
        let mut pose = None;
        for s in 0..sequences {
            for _ in 0..2 {
                let length = u16::from_le_bytes(bytes[at..at + 2].try_into().unwrap()) as usize;
                at += 2 + length;
            }
            at += 4 * 8 + 4 * bones;
            let frames = u32_(&mut at) as usize;
            let mut locals = vec![];
            for _ in 0..if frames == 0 { 0 } else { bones } {
                let (pt, rt) = (bytes[at], bytes[at + 1]);
                at += 2;
                let p = [f(&mut at), f(&mut at), f(&mut at)];
                at += if pt == 1 { 12 * (frames - 1) } else { 0 };
                let q = q16(&mut at);
                at += if rt == 1 { 8 * (frames - 1) } else { 0 };
                locals.push((p, q));
            }
            if s == sequence {
                pose = Some(locals);
            }
        }
        assert_eq!(at, bytes.len());
        let pose = pose.unwrap();
        let mut world: Vec<[[f64; 4]; 3]> = vec![];
        for bone in 0..bones {
            let (p, q) = pose[bone];
            let local = crate::source::anim::matrix((p.map(|v| v as f32), q.map(|v| v as f32)));
            let m = match usize::try_from(parents[bone]).ok() {
                Some(parent) => crate::source::anim::multiply(&world[parent], &local),
                None => local,
            };
            world.push(m);
        }
        let skins: Vec<_> = (0..bones)
            .map(|b| crate::source::anim::multiply(&world[b], &binds[b]))
            .collect();
        mesh_vertices
            .iter()
            .zip(&weights)
            .map(|(v, (count, ids, w))| {
                let mut out = [0.0; 3];
                for slot in 0..*count {
                    let m = &skins[ids[slot] as usize];
                    for (r, value) in out.iter_mut().enumerate() {
                        *value += w[slot]
                            * (m[r][0] * f64::from(v[0])
                                + m[r][1] * f64::from(v[1])
                                + m[r][2] * f64::from(v[2])
                                + m[r][3]);
                    }
                }
                out
            })
            .collect()
    }

    #[test]
    fn decoded_animation_poses_real_meshes_where_source_does() {
        let vpk = "/mnt/games/SteamLibrary/steamapps/common/infra/infra/pak02_dir.vpk";
        if !std::path::Path::new(vpk).exists() {
            return;
        }
        let map = crate::bsp::Map::load(std::path::Path::new(&format!(
            "{vpk}:maps/infra_c4_m2_furnace.bsp"
        )))
        .unwrap();
        let vfs = crate::source::vfs::Vfs::for_map(&map.path, &[] as &[std::path::PathBuf]);
        let mut models = crate::source::mdl::Models::new(&vfs);
        for path in [
            "models/props_door/metal_door_007.mdl",
            "models/props_electric/knife_switch_001_switch.mdl",
            "models/props_electric/switch_003.mdl",
            "models/props_nature/cockroaches_001.mdl",
        ] {
            let model = models.get(path).unwrap();
            let animation = crate::source::anim::read(&vfs, path).unwrap();
            let (mesh, _, bindings) =
                crate::output::mesh::from_source_model(&model, UNITS).unwrap();
            for sequence in 0..animation.sequences.len().min(3) {
                let bytes = encode(
                    &animation,
                    model.root,
                    &bindings,
                    &BTreeSet::from([sequence]),
                )
                .unwrap();
                let vertices: Vec<[f32; 3]> = mesh.vertices.iter().map(|v| v.position).collect();
                let ours = decode_and_pose(&bytes, sequence, &vertices);
                let source = crate::source::anim::pose_model(
                    &model,
                    &animation,
                    &animation.sequences[sequence].frames[0],
                );
                // Source's posed corners, mapped to block space, are each some mesh vertex posed.
                let mut worst = 0.0f64;
                for part in &source.parts {
                    for tri in &part.triangles {
                        for corner in tri {
                            let block = [corner.x / UNITS, corner.z / UNITS, -corner.y / UNITS];
                            let nearest = ours
                                .iter()
                                .map(|p| {
                                    (0..3)
                                        .map(|i| (p[i] - block[i]).powi(2))
                                        .sum::<f64>()
                                        .sqrt()
                                })
                                .fold(f64::INFINITY, f64::min);
                            worst = worst.max(nearest);
                        }
                    }
                }
                assert!(
                    worst < 2e-3,
                    "{path} sequence {sequence}: a posed corner is {worst} blocks off"
                );
            }
        }
    }

    #[test]
    fn positions_and_rotations_map_to_block_space() {
        assert_eq!(position([32.0, 64.0, 96.0]), [1.0, 3.0, -2.0]);
        // A quarter turn about Source z (up) is one about Minecraft y.
        let q = crate::source::anim::angle_quaternion([0.0, 0.0, std::f32::consts::FRAC_PI_2]);
        let m = rotation(q);
        assert!(m[0].abs() < 1e-6 && m[2].abs() < 1e-6 && m[1] > 0.7);
        let t = transform(&[
            [1.0, 0.0, 0.0, 32.0],
            [0.0, 1.0, 0.0, 64.0],
            [0.0, 0.0, 1.0, 96.0],
        ]);
        assert_eq!(t[0][3], 1.0);
        assert_eq!(t[1][3], 3.0);
        assert_eq!(t[2][3], -2.0);
        assert_eq!(t[1][1], 1.0);
    }

    #[test]
    fn only_kept_sequences_carry_frames_and_still_tracks_are_one_value() {
        let sequence = |frames: usize| Sequence {
            label: "open".into(),
            activity: String::new(),
            activity_weight: 1,
            flags: 0,
            fade_in: 0.2,
            fade_out: 0.2,
            entry_node: 0,
            exit_node: 0,
            node_flags: 0,
            cycles_per_second: 1.0,
            bone_weights: vec![1.0, 1.0],
            frames: (0..frames)
                .map(|f| {
                    vec![
                        ([0.0; 3], [0.0, 0.0, 0.0, 1.0]),
                        ([f as f32, 0.0, 0.0], [0.0, 0.0, 0.0, 1.0]),
                    ]
                })
                .collect(),
        };
        let animation = Animation {
            bones: vec![bone(-1), bone(0)],
            sequences: vec![sequence(3), sequence(5)],
            nodes: 0,
            transitions: vec![],
            unsupported: vec![],
        };
        let identity = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
        let bindings = vec![[(1, 1.0), (0, 0.0), (0, 0.0)]; 4];
        let kept = encode(&animation, identity, &bindings, &BTreeSet::from([0])).unwrap();
        let all = encode(&animation, identity, &bindings, &BTreeSet::from([0, 1])).unwrap();
        // Bone 0 still: 2 flag bytes, one position, one rotation; bone 1
        // moves in position only.
        let per_bone = |frames: usize| 2 + 12 + 8 + 2 + 12 * frames + 8;
        assert_eq!(all.len() - kept.len(), per_bone(5));
        assert_eq!(&kept[..8], &MAGIC);
        let bad = [(2, 1.0), (0, 0.0), (0, 0.0)];
        assert!(encode(&animation, identity, &[bad], &BTreeSet::new()).is_err());
    }
}
