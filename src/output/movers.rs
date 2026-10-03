//! The map's moving brush entities (format section 17): each door, button and
//! platform as its own small map, in cells of its own, for the mod to move as
//! a physics sub-level.
//!
//! A mover is cut out of the world entirely — its blocks, faces, collision and
//! the props riding on it — so the world schematic has a hole where it stands
//! and the mover fills it at the pose the map spawns it in.

use crate::bsp::entities::EntityRecord;
use crate::output::bundle;
use crate::voxel::grid::IVec3;
use anyhow::{Context, Result, ensure};
use serde::Serialize;
use std::collections::{BTreeSet, HashMap};

pub const FORMAT: &str = "src2mc-movers";
pub const VERSION: u32 = 1;

/// Movers in one map. Far above any real map, which has a few hundred: each
/// mover is two bundle entries, so this also keeps one map from taking a
/// large share of the bundle's entry limit.
pub const MAX_MOVERS_PER_MAP: u64 = 16_384;
/// Cells along any one axis of a mover. A train or lift can be long, but not
/// longer than a Source map, which is 1,024 blocks across at 32 units each.
pub const MAX_MOVER_AXIS: i32 = 4_096;
/// Blocks, surface and carrier together, in one mover.
pub const MAX_MOVER_BLOCKS: u64 = 1_000_000;
/// Props attached to one mover.
pub const MAX_MOVER_PROPS: u64 = 65_536;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct MoverTable {
    pub format: &'static str,
    pub version: u32,
    pub movers: Vec<Mover>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Mover {
    /// Index of the entity in the BSP entity lump, as in the logic table.
    pub entity: u32,
    /// Lowercase.
    pub classname: String,
    /// The map-local cell mover-local `[0, 0, 0]` is at in the spawn pose.
    pub cell_origin: IVec3,
    /// Cells covering every mover-local cell used, at least 1 per axis.
    pub size: IVec3,
    /// `maps/<map-id>/movers/<entity>.s2faces`; absent with no faces.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub surfaces: Option<String>,
    /// `maps/<map-id>/movers/<entity>.s2coll`; absent with no shapes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub collision: Option<String>,
    pub blocks: Blocks,
    pub props: Vec<MoverProp>,
}

/// The mover's blocks, mover-local, each list strictly ascending by X, then
/// Y, then Z.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Blocks {
    /// Cells holding an `src2mc:surface` block: the mover's voxel grid.
    pub surface: Vec<IVec3>,
    /// Cells holding an `src2mc:carrier` block: the collision table's carriers.
    pub carrier: Vec<IVec3>,
}

/// A prop riding on a mover. The values its `props.s2props` record would
/// have had, moved into the mover's cells.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct MoverProp {
    /// Index of the prop's entity in the BSP entity lump.
    pub entity: u32,
    /// Index into the map's model-reference table.
    pub model: u32,
    /// Mover-local block coordinates of the model origin.
    pub translation: [f64; 3],
    /// Unit quaternion XYZW in Minecraft axes.
    pub rotation: [f64; 4],
    pub scale: f64,
    pub skin: i32,
}

/// Parent chains longer than this are taken for a cycle and attach nothing.
/// Source itself refuses to parent an entity to its own descendant; real
/// chains are two or three links long.
const MAX_PARENT_DEPTH: usize = 32;

/// Which mover each rider rides on.
#[derive(Debug, Default)]
pub struct Attachments {
    /// Mover entity index by rider entity index, for riders that have one.
    pub mover_of: HashMap<usize, usize>,
    /// Lowercase targetnames that several entities share and a rider's chain
    /// went through: Source would pick whichever it finds first, which is not
    /// necessarily the one taken here.
    pub ambiguous: BTreeSet<String>,
}

/// Find the mover each of `riders` is parented to, directly or through a
/// chain of parented entities.
///
/// `parentname` is `name` or `name,attachment`; the attachment only says where
/// on the parent the child sits, which its spawn position already does, so it
/// is dropped. Names match ignoring ASCII case, as Source matches them. When
/// several entities share a name the first mover among them in lump order is
/// taken, or failing one, the first whose own chain reaches a mover.
pub fn attach(
    entities: &[EntityRecord],
    movers: &BTreeSet<usize>,
    riders: impl IntoIterator<Item = usize>,
) -> Attachments {
    let by_index: HashMap<usize, &EntityRecord> = entities.iter().map(|e| (e.index, e)).collect();
    let mut by_name: HashMap<String, Vec<usize>> = HashMap::new();
    for entity in entities {
        if let Some(name) = entity.get("targetname").map(normalize_name)
            && !name.is_empty()
        {
            by_name.entry(name).or_default().push(entity.index);
        }
    }
    for indices in by_name.values_mut() {
        indices.sort_unstable();
    }

    fn resolve(
        index: usize,
        depth: usize,
        by_index: &HashMap<usize, &EntityRecord>,
        by_name: &HashMap<String, Vec<usize>>,
        movers: &BTreeSet<usize>,
        ambiguous: &mut BTreeSet<String>,
    ) -> Option<usize> {
        if movers.contains(&index) {
            return Some(index);
        }
        if depth >= MAX_PARENT_DEPTH {
            return None;
        }
        let parent = by_index.get(&index)?.get("parentname")?;
        let parent = normalize_name(parent.split(',').next().unwrap_or(""));
        let candidates = by_name.get(&parent)?;
        let found = candidates
            .iter()
            .copied()
            .find(|candidate| movers.contains(candidate))
            .or_else(|| {
                candidates.iter().find_map(|&candidate| {
                    resolve(candidate, depth + 1, by_index, by_name, movers, ambiguous)
                })
            });
        if found.is_some() && candidates.len() > 1 {
            ambiguous.insert(parent);
        }
        found
    }

    let mut attachments = Attachments::default();
    for rider in riders {
        if let Some(mover) = resolve(
            rider,
            0,
            &by_index,
            &by_name,
            movers,
            &mut attachments.ambiguous,
        ) {
            attachments.mover_of.insert(rider, mover);
        }
    }
    attachments
}

fn normalize_name(name: &str) -> String {
    name.trim().to_ascii_lowercase()
}

impl MoverTable {
    pub fn new(movers: Vec<Mover>) -> MoverTable {
        MoverTable {
            format: FORMAT,
            version: VERSION,
            movers,
        }
    }

    /// Validate against the map it belongs to and encode canonically.
    pub fn encode(mut self, map_id: &str, model_count: u32) -> Result<Vec<u8>> {
        ensure!(
            self.format == FORMAT && self.version == VERSION,
            "invalid mover table schema"
        );
        crate::output::limits::check_count(
            "map mover",
            self.movers.len() as u64,
            MAX_MOVERS_PER_MAP,
        )?;
        ensure!(
            self.movers
                .windows(2)
                .all(|pair| pair[0].entity < pair[1].entity),
            "movers must be unique and in entity lump order"
        );
        for mover in &mut self.movers {
            mover
                .validate(map_id, model_count)
                .with_context(|| format!("mover entity {}", mover.entity))?;
        }
        bundle::canonical_json(&self)
    }
}

impl Mover {
    /// The canonical path of this mover's surface table.
    pub fn surfaces_path(map_id: &str, entity: u32) -> String {
        format!("maps/{map_id}/movers/{entity}.s2faces")
    }

    /// The canonical path of this mover's collision table.
    pub fn collision_path(map_id: &str, entity: u32) -> String {
        format!("maps/{map_id}/movers/{entity}.s2coll")
    }

    fn validate(&mut self, map_id: &str, model_count: u32) -> Result<()> {
        ensure!(
            !self.classname.is_empty() && self.classname == self.classname.to_ascii_lowercase(),
            "mover classname must be lowercase and not empty"
        );
        for axis in 0..3 {
            ensure!(
                (1..=MAX_MOVER_AXIS).contains(&self.size[axis]),
                "{}: mover size {:?} out of range",
                crate::output::limits::ErrorCode::LimitExceeded.as_str(),
                self.size
            );
            ensure!(
                self.cell_origin[axis]
                    .checked_add(self.size[axis])
                    .is_some(),
                "mover cell bounds overflow"
            );
        }
        if let Some(path) = &self.surfaces {
            ensure!(
                path == &Mover::surfaces_path(map_id, self.entity),
                "non-canonical mover surface path"
            );
        }
        if let Some(path) = &self.collision {
            ensure!(
                path == &Mover::collision_path(map_id, self.entity),
                "non-canonical mover collision path"
            );
        }
        crate::output::limits::check_count(
            "mover block",
            (self.blocks.surface.len() + self.blocks.carrier.len()) as u64,
            MAX_MOVER_BLOCKS,
        )?;
        let inside = |cell: &IVec3| (0..3).all(|axis| (0..self.size[axis]).contains(&cell[axis]));
        for list in [&self.blocks.surface, &self.blocks.carrier] {
            ensure!(list.iter().all(inside), "mover block outside its size");
            ensure!(
                list.windows(2).all(|pair| pair[0] < pair[1]),
                "mover blocks must be unique and sorted"
            );
        }
        let surface: std::collections::BTreeSet<&IVec3> = self.blocks.surface.iter().collect();
        ensure!(
            self.blocks
                .carrier
                .iter()
                .all(|cell| !surface.contains(cell)),
            "a mover cell is both a surface block and a carrier"
        );
        crate::output::limits::check_count("mover prop", self.props.len() as u64, MAX_MOVER_PROPS)?;
        for prop in &mut self.props {
            ensure!(
                prop.model < model_count,
                "{}: mover prop references a missing model",
                crate::output::limits::ErrorCode::InvalidReference.as_str()
            );
            for value in prop.translation.iter_mut().chain(prop.rotation.iter_mut()) {
                *value = bundle::canonical_f64(*value)?;
            }
            prop.scale = bundle::canonical_f64(prop.scale)?;
            ensure!(prop.scale > 0.0, "mover prop scale must be positive");
            let length = prop.rotation.iter().map(|v| v * v).sum::<f64>().sqrt();
            ensure!(
                (length - 1.0).abs() < 1.0e-6,
                "mover prop rotation is not a unit quaternion"
            );
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mover(entity: u32) -> Mover {
        Mover {
            entity,
            classname: "func_door".into(),
            cell_origin: [10, -2, 7],
            size: [2, 3, 1],
            surfaces: Some(Mover::surfaces_path("m", entity)),
            collision: None,
            blocks: Blocks {
                surface: vec![[0, 0, 0], [0, 1, 0], [1, 0, 0]],
                carrier: vec![[1, 2, 0]],
            },
            props: vec![MoverProp {
                entity: 573,
                model: 0,
                translation: [0.5, -0.0, 1.25],
                rotation: [0.0, 0.0, 0.0, 1.0],
                scale: 1.0,
                skin: 2,
            }],
        }
    }

    fn entities(list: &[&[(&str, &str)]]) -> Vec<EntityRecord> {
        let config = crate::config::Config::default();
        let transform =
            crate::voxel::transform::Transform::new(&config, crate::geom::Aabb::empty());
        list.iter()
            .enumerate()
            .map(|(index, pairs)| {
                let pairs = pairs
                    .iter()
                    .map(|(k, v)| (k.to_string(), v.to_string()))
                    .collect();
                crate::bsp::entities::record(index, pairs, &transform)
            })
            .collect()
    }

    #[test]
    fn props_ride_on_the_mover_they_are_parented_to() {
        let list = entities(&[
            &[("classname", "worldspawn")],
            &[("classname", "func_door"), ("targetname", "Exit_Door")],
            // Directly, with an attachment and the name in another case.
            &[
                ("classname", "prop_dynamic"),
                ("parentname", "exit_door,handle"),
            ],
            // Through a chain: a prop on a prop on the door.
            &[
                ("classname", "prop_dynamic"),
                ("targetname", "frame"),
                ("parentname", "exit_door"),
            ],
            &[("classname", "prop_dynamic"), ("parentname", "frame")],
            // Parented to something that is not a mover.
            &[("classname", "prop_dynamic"), ("parentname", "nothing")],
            // A cycle attaches nothing rather than looping.
            &[
                ("classname", "prop_dynamic"),
                ("targetname", "a"),
                ("parentname", "a"),
            ],
        ]);
        let movers = BTreeSet::from([1]);
        let attached = attach(&list, &movers, [2, 3, 4, 5, 6]);
        assert_eq!(attached.mover_of.get(&2), Some(&1));
        assert_eq!(attached.mover_of.get(&3), Some(&1));
        assert_eq!(attached.mover_of.get(&4), Some(&1));
        assert_eq!(attached.mover_of.get(&5), None);
        assert_eq!(attached.mover_of.get(&6), None);
        assert!(attached.ambiguous.is_empty());
    }

    #[test]
    fn a_shared_name_takes_the_first_mover_and_is_reported() {
        let list = entities(&[
            &[("classname", "worldspawn")],
            &[("classname", "info_target"), ("targetname", "door")],
            &[("classname", "func_door"), ("targetname", "door")],
            &[("classname", "func_door"), ("targetname", "door")],
            &[("classname", "prop_dynamic"), ("parentname", "door")],
        ]);
        let attached = attach(&list, &BTreeSet::from([2, 3]), [4]);
        assert_eq!(attached.mover_of.get(&4), Some(&2));
        assert_eq!(attached.ambiguous, BTreeSet::from(["door".to_string()]));
    }

    #[test]
    fn fields_are_written_in_canonical_order() {
        let bytes = MoverTable::new(vec![mover(41)]).encode("m", 1).unwrap();
        assert_eq!(
            String::from_utf8(bytes).unwrap(),
            concat!(
                r#"{"format":"src2mc-movers","version":1,"movers":[{"entity":41,"#,
                r#""classname":"func_door","cell_origin":[10,-2,7],"size":[2,3,1],"#,
                r#""surfaces":"maps/m/movers/41.s2faces","#,
                r#""blocks":{"surface":[[0,0,0],[0,1,0],[1,0,0]],"carrier":[[1,2,0]]},"#,
                r#""props":[{"entity":573,"model":0,"translation":[0.5,0.0,1.25],"#,
                r#""rotation":[0.0,0.0,0.0,1.0],"scale":1.0,"skin":2}]}]}"#,
                "\n"
            )
        );
    }

    #[test]
    fn malformed_movers_are_rejected() {
        let encode = |m: Mover| MoverTable::new(vec![m]).encode("m", 1);
        let mut outside = mover(1);
        outside.blocks.surface.push([2, 0, 0]);
        assert!(encode(outside).is_err(), "block beyond the size");
        let mut unsorted = mover(1);
        unsorted.blocks.surface.swap(0, 2);
        assert!(encode(unsorted).is_err());
        let mut shared = mover(1);
        shared.blocks.carrier = vec![[0, 0, 0]];
        assert!(encode(shared).is_err(), "surface and carrier overlap");
        let mut missing = mover(1);
        missing.props[0].model = 1;
        assert!(encode(missing).is_err(), "model index out of range");
        let mut path = mover(1);
        path.collision = Some("maps/m/movers/2.s2coll".into());
        assert!(encode(path).is_err());
        let mut empty = mover(1);
        empty.size = [0, 1, 1];
        assert!(encode(empty).is_err());
        assert!(
            MoverTable::new(vec![mover(3), mover(2)])
                .encode("m", 1)
                .is_err(),
            "lump order"
        );
    }
}
