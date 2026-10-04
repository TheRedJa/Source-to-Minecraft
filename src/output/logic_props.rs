//! The props the map's logic changes (format section 18): which placed prop
//! or mover rider each one is, the model reference of every skin family it
//! can switch to, whether it spawns hidden, and its collision when the logic
//! can take that away.

use crate::output::bundle;
use anyhow::{Context, Result, ensure};
use serde::Serialize;

pub const FORMAT: &str = "src2mc-logic-props";
pub const VERSION: u32 = 3;

/// Logic props in one map. Real maps have up to a few hundred; each can add
/// one bundle entry, so this also bounds a map's share of the entry limit.
pub const MAX_LOGIC_PROPS_PER_MAP: u64 = 16_384;
/// Skin families of one model; Source networks the skin in 10 bits.
pub const MAX_SKINS: u64 = 1_024;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LogicPropTable {
    pub format: &'static str,
    pub version: u32,
    pub props: Vec<LogicProp>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LogicProp {
    /// Index of the prop's entity in the BSP entity lump.
    pub entity: u32,
    /// The `props.s2props` record, as 64 lowercase hex digits; absent for a
    /// prop riding a mover.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stable_id: Option<String>,
    /// The mover entity the prop rides on; absent for a placed prop.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mover: Option<u32>,
    /// Model-reference index of each skin family, family 0 first.
    pub skins: Vec<u32>,
    /// The skin the map spawns it with, as written; may name no family.
    pub skin: i32,
    /// Present, and true, when the prop spawns hidden.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub start_hidden: bool,
    /// `maps/<map-id>/logic_props/<entity>.s2coll`; absent without
    /// removable collision. Map-local for a placed prop, mover-local for a
    /// riding one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub collision: Option<String>,
    /// The sequence an animated prop spawns in; absent for one whose model
    /// has no animation.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sequence: Option<u32>,
    /// For a prop whose collision follows its bones, the collision of the
    /// pose each sequence it can play leaves it in, by ascending sequence.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub poses: Vec<Pose>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Pose {
    pub sequence: u32,
    /// `maps/<map-id>/logic_props/<entity>_<sequence>.s2coll`.
    pub collision: String,
}

impl LogicPropTable {
    pub fn new(props: Vec<LogicProp>) -> LogicPropTable {
        LogicPropTable {
            format: FORMAT,
            version: VERSION,
            props,
        }
    }

    /// Validate against the map it belongs to and encode canonically.
    pub fn encode(self, map_id: &str, model_count: u32) -> Result<Vec<u8>> {
        crate::output::limits::check_count(
            "map logic prop",
            self.props.len() as u64,
            MAX_LOGIC_PROPS_PER_MAP,
        )?;
        ensure!(
            self.props
                .windows(2)
                .all(|pair| pair[0].entity < pair[1].entity),
            "logic props must be unique and in entity lump order"
        );
        for prop in &self.props {
            prop.validate(map_id, model_count)
                .with_context(|| format!("logic prop entity {}", prop.entity))?;
        }
        bundle::canonical_json(&self)
    }
}

impl LogicProp {
    /// The canonical path of this prop's collision table.
    pub fn collision_path(map_id: &str, entity: u32) -> String {
        format!("maps/{map_id}/logic_props/{entity}.s2coll")
    }

    /// The canonical path of the collision of one of its poses.
    pub fn pose_path(map_id: &str, entity: u32, sequence: u32) -> String {
        format!("maps/{map_id}/logic_props/{entity}_{sequence}.s2coll")
    }

    fn validate(&self, map_id: &str, model_count: u32) -> Result<()> {
        ensure!(
            self.stable_id.is_some() != self.mover.is_some(),
            "a logic prop is either placed or riding a mover"
        );
        if let Some(id) = &self.stable_id {
            ensure!(
                id.len() == 64
                    && id
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
                "stable ID must be 64 lowercase hex digits"
            );
        }
        ensure!(!self.skins.is_empty(), "a logic prop has at least one skin");
        crate::output::limits::check_count("logic prop skin", self.skins.len() as u64, MAX_SKINS)?;
        ensure!(
            self.skins.iter().all(|model| *model < model_count),
            "skin model reference out of range"
        );
        if let Some(path) = &self.collision {
            ensure!(
                path == &LogicProp::collision_path(map_id, self.entity),
                "non-canonical logic prop collision path"
            );
        }
        if !self.poses.is_empty() {
            ensure!(
                self.collision.is_some() && self.sequence.is_some(),
                "only an animated prop with collision of its own has poses"
            );
            ensure!(
                self.poses.windows(2).all(|p| p[0].sequence < p[1].sequence),
                "poses must be unique and in sequence order"
            );
            for pose in &self.poses {
                ensure!(
                    pose.collision == LogicProp::pose_path(map_id, self.entity, pose.sequence),
                    "non-canonical pose collision path"
                );
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn placed(entity: u32) -> LogicProp {
        LogicProp {
            entity,
            stable_id: Some("ab".repeat(32)),
            mover: None,
            skins: vec![0, 1],
            skin: 1,
            start_hidden: false,
            collision: None,
            sequence: None,
            poses: Vec::new(),
        }
    }

    #[test]
    fn encodes_canonically_and_leaves_out_defaults() {
        let mut hidden = placed(9);
        hidden.start_hidden = true;
        hidden.collision = Some(LogicProp::collision_path("m", 9));
        let rider = LogicProp {
            stable_id: None,
            mover: Some(3),
            ..placed(12)
        };
        let bytes = LogicPropTable::new(vec![placed(4), hidden, rider])
            .encode("m", 2)
            .unwrap();
        let text = String::from_utf8(bytes).unwrap();
        let id = "ab".repeat(32);
        assert_eq!(
            text,
            format!(
                "{{\"format\":\"src2mc-logic-props\",\"version\":3,\"props\":[\
                 {{\"entity\":4,\"stable_id\":\"{id}\",\"skins\":[0,1],\"skin\":1}},\
                 {{\"entity\":9,\"stable_id\":\"{id}\",\"skins\":[0,1],\"skin\":1,\"start_hidden\":true,\"collision\":\"maps/m/logic_props/9.s2coll\"}},\
                 {{\"entity\":12,\"mover\":3,\"skins\":[0,1],\"skin\":1}}]}}\n"
            )
        );
    }

    /// An animated prop names the sequence it spawns in; one whose collision
    /// follows its bones lists a collision per pose, in sequence order.
    #[test]
    fn animated_props_carry_their_sequence_and_poses() {
        let mut door = placed(7);
        door.collision = Some(LogicProp::collision_path("m", 7));
        door.sequence = Some(2);
        door.poses = [0, 3]
            .map(|sequence| Pose {
                sequence,
                collision: LogicProp::pose_path("m", 7, sequence),
            })
            .to_vec();
        let text = String::from_utf8(
            LogicPropTable::new(vec![door.clone()])
                .encode("m", 2)
                .unwrap(),
        )
        .unwrap();
        assert!(text.ends_with(
            "\"collision\":\"maps/m/logic_props/7.s2coll\",\"sequence\":2,\"poses\":[\
             {\"sequence\":0,\"collision\":\"maps/m/logic_props/7_0.s2coll\"},\
             {\"sequence\":3,\"collision\":\"maps/m/logic_props/7_3.s2coll\"}]}]}\n"
        ));
        let mut unordered = door.clone();
        unordered.poses.reverse();
        assert!(LogicPropTable::new(vec![unordered]).encode("m", 2).is_err());
        let mut still = door.clone();
        still.sequence = None;
        assert!(LogicPropTable::new(vec![still]).encode("m", 2).is_err());
        let mut elsewhere = door;
        elsewhere.poses[0].collision = "maps/m/logic_props/7.s2coll".into();
        assert!(LogicPropTable::new(vec![elsewhere]).encode("m", 2).is_err());
    }

    #[test]
    fn rejects_bad_records() {
        assert!(
            LogicPropTable::new(vec![placed(4), placed(4)])
                .encode("m", 2)
                .is_err()
        );
        assert!(LogicPropTable::new(vec![placed(4)]).encode("m", 1).is_err());
        let mut both = placed(4);
        both.mover = Some(1);
        assert!(LogicPropTable::new(vec![both]).encode("m", 2).is_err());
        let mut riding_collision = placed(4);
        riding_collision.stable_id = None;
        riding_collision.mover = Some(1);
        riding_collision.collision = Some(LogicProp::collision_path("m", 4));
        assert!(
            LogicPropTable::new(vec![riding_collision])
                .encode("m", 2)
                .is_ok()
        );
    }
}
