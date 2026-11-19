//! The models Half-Life 2's NPCs wear, as each class's `Spawn`,
//! `Precache` and `SelectModel` choose them (2013 SDK, `game/server/hl2`;
//! notes: `source_refs/data/hl2_npc_research.md`, "NPC models and held
//! weapons"). Many classes ignore the map's `model` key and set their own;
//! some take it with a default; citizens rebuild it from their type and head.

/// Classes that are actors: drawn with their model and animated as NPCs.
pub fn is_actor(classname: &str) -> bool {
    let c = classname.to_ascii_lowercase();
    if matches!(c.as_str(), "generic_actor" | "cycler_actor" | "monster_generic") {
        return true;
    }
    c.starts_with("npc_") && (fixed(&c).is_some() || default(&c).is_some() || c == "npc_citizen")
}

/// Whether an entity of this class has a model to draw: its class's own, or
/// the `.mdl` its key names.
pub fn has_model(classname: &str, key_is_model: bool) -> bool {
    let c = classname.to_ascii_lowercase();
    is_actor(&c) && (key_is_model || fixed(&c).is_some() || default(&c).is_some() || c == "npc_citizen")
}

/// Classes whose `Spawn` sets the model whatever the key says.
fn fixed(classname: &str) -> Option<&'static str> {
    Some(match classname {
        "npc_metropolice" => "models/police.mdl",
        "npc_barney" => "models/barney.mdl",
        "npc_gman" => "models/gman.mdl",
        "npc_mossman" => "models/mossman.mdl",
        "npc_dog" => "models/dog.mdl",
        "npc_monk" => "models/monk.mdl",
        "npc_combine_camera" => "models/combine_camera/combine_camera.mdl",
        "npc_cscanner" => "models/combine_scanner.mdl",
        "npc_pigeon" => "models/pigeon.mdl",
        "npc_seagull" => "models/seagull.mdl",
        "npc_headcrab" => "models/headcrabclassic.mdl",
        "npc_headcrab_fast" => "models/headcrab.mdl",
        "npc_headcrab_black" | "npc_headcrab_poison" => "models/headcrabblack.mdl",
        _ => return None,
    })
}

/// Classes that take the key, or this when it is empty.
fn default(classname: &str) -> Option<&'static str> {
    Some(match classname {
        "npc_breen" => "models/breen.mdl",
        "npc_kleiner" => "models/kleiner.mdl",
        "npc_eli" => "models/eli.mdl",
        "npc_alyx" => "models/alyx.mdl",
        "npc_vortigaunt" => "models/vortigaunt.mdl",
        "npc_crow" => "models/crow.mdl",
        "npc_strider" => "models/combine_strider.mdl",
        "npc_combine_s" => "models/combine_soldier.mdl",
        _ => return None,
    })
}

/// `SF_NPC_START_EFFICIENT`.
const START_EFFICIENT: i64 = 1 << 4;
const CITIZEN_MEDIC: i64 = 1 << 17;
const RANDOM_HEAD: i64 = 1 << 18;
const RANDOM_HEAD_MALE: i64 = 1 << 22;
const RANDOM_HEAD_FEMALE: i64 = 1 << 23;

/// `g_ppszRandomHeads` (npc_citizen17.cpp:257-273).
const HEADS: [&str; 15] = [
    "male_01.mdl",
    "male_02.mdl",
    "female_01.mdl",
    "male_03.mdl",
    "female_02.mdl",
    "male_04.mdl",
    "female_03.mdl",
    "male_05.mdl",
    "female_04.mdl",
    "male_06.mdl",
    "female_06.mdl",
    "male_07.mdl",
    "female_07.mdl",
    "male_08.mdl",
    "male_09.mdl",
];

/// Chooses models for one map's actors in lump order; citizens with a random
/// head balance the heads as `SelectModel` does.
pub struct Actors {
    map: String,
    heads: [u32; HEADS.len()],
}

impl Actors {
    pub fn new(map_name: &str) -> Actors {
        Actors {
            map: map_name.to_ascii_lowercase(),
            heads: [0; HEADS.len()],
        }
    }

    /// The model an actor spawns with, lowercase with forward slashes, or
    /// `None` for a class that is not an actor or has none.
    pub fn model(&mut self, classname: &str, key: Option<&str>, spawnflags: i64, citizentype: i64) -> Option<String> {
        let classname = classname.to_ascii_lowercase();
        let key = key
            .map(|k| k.trim().replace('\\', "/").to_ascii_lowercase())
            .filter(|k| !k.is_empty());
        let chosen = if classname == "npc_metropolice" && spawnflags & START_EFFICIENT != 0 {
            Some("models/police_cheaple.mdl".to_string())
        } else if let Some(model) = fixed(&classname) {
            Some(model.to_string())
        } else if let Some(model) = default(&classname) {
            Some(key.unwrap_or_else(|| model.to_string()))
        } else if classname == "npc_citizen" {
            self.citizen(key, spawnflags, citizentype)
        } else if is_actor(&classname) {
            key
        } else {
            None
        };
        chosen.filter(|m| m.ends_with(".mdl"))
    }

    /// `CNPC_Citizen::SelectModel` (npc_citizen17.cpp:574-718). Where Source
    /// picks a random head among the least used, this takes the first of
    /// them, so a conversion is the same every time.
    fn citizen(&mut self, key: Option<String>, spawnflags: i64, citizentype: i64) -> Option<String> {
        // `GetModelName` treats these obsolete names as none (:796-812).
        let key = key.filter(|k| {
            !["models/c17_", "models/male", "models/female", "models/citizen"]
                .iter()
                .any(|p| k.starts_with(p))
        });
        let mut kind = citizentype;
        if kind == 0 {
            const TAGS: [(&str, i64); 7] = [
                ("trainstation", 1),
                ("canals", 2),
                ("town", 2),
                ("coast", 2),
                ("prison", 1),
                ("c17", 3),
                ("citadel", 1),
            ];
            kind = TAGS
                .iter()
                .find(|(tag, _)| self.map.contains(tag))
                .map_or(1, |&(_, t)| t);
        }
        let random = spawnflags & (RANDOM_HEAD | RANDOM_HEAD_MALE | RANDOM_HEAD_FEMALE) != 0 || key.is_none();
        let name = if random {
            if spawnflags & START_EFFICIENT != 0 {
                return Some("models/humans/male_cheaple.mdl".into());
            }
            let gender = if spawnflags & RANDOM_HEAD_MALE != 0 {
                Some('m')
            } else if spawnflags & RANDOM_HEAD_FEMALE != 0 {
                Some('f')
            } else {
                None
            };
            let candidates: Vec<usize> = (0..HEADS.len())
                .filter(|&i| gender.is_none_or(|g| HEADS[i].starts_with(g)))
                .collect();
            let least = candidates.iter().map(|&i| self.heads[i]).min()?;
            let head = *candidates.iter().find(|&&i| self.heads[i] == least)?;
            self.heads[head] += 1;
            HEADS[head].to_string()
        } else {
            let key = key?;
            let base = key.rsplit('/').next().unwrap_or(&key).to_string();
            if let Some(head) = HEADS.iter().position(|h| h.eq_ignore_ascii_case(&base)) {
                self.heads[head] += 1;
            }
            if kind == 4 {
                return Some(key);
            }
            base
        };
        if kind == 4 {
            return None;
        }
        let folder = match kind {
            2 => "group02".to_string(),
            3 => format!("group03{}", if spawnflags & CITIZEN_MEDIC != 0 { "m" } else { "" }),
            _ => "group01".to_string(),
        };
        Some(format!("models/humans/{folder}/{name}"))
    }
}

/// `SF_COMBINE_CAMERA_STARTINACTIVE` (npc_combinecamera.cpp:57).
const CAMERA_START_INACTIVE: i64 = 0x80;

/// The activity an actor idles in from spawn: `ACT_IDLE`, but a combine
/// camera that starts active deploys to its open idle, and an inactive one
/// stays closed (`CNPC_CombineCamera::Spawn`, `Deploy`,
/// npc_combinecamera.cpp:343-364, 447).
pub fn idle_activity(classname: &str, spawnflags: i64) -> &'static str {
    if classname.eq_ignore_ascii_case("npc_combine_camera") {
        if spawnflags & CAMERA_START_INACTIVE != 0 {
            "ACT_COMBINE_CAMERA_CLOSED_IDLE"
        } else {
            "ACT_COMBINE_CAMERA_OPEN_IDLE"
        }
    } else {
        "ACT_IDLE"
    }
}

/// The world model of a weapon an NPC carries (`additionalequipment`), from
/// the `playermodel` of HL2's `scripts/weapon_*.txt`.
pub fn weapon_model(equipment: &str) -> Option<&'static str> {
    Some(match equipment.trim().to_ascii_lowercase().as_str() {
        "weapon_stunstick" => "models/weapons/w_stunbaton.mdl",
        "weapon_pistol" => "models/weapons/w_pistol.mdl",
        "weapon_smg1" => "models/weapons/w_smg1.mdl",
        "weapon_ar2" => "models/weapons/w_irifle.mdl",
        "weapon_shotgun" => "models/weapons/w_shotgun.mdl",
        "weapon_crowbar" => "models/weapons/w_crowbar.mdl",
        "weapon_357" => "models/weapons/w_357.mdl",
        "weapon_crossbow" => "models/weapons/w_crossbow.mdl",
        "weapon_rpg" => "models/weapons/w_rocket_launcher.mdl",
        "weapon_citizenpackage" => "models/weapons/w_package.mdl",
        "weapon_citizensuitcase" => "models/weapons/w_suitcase_passenger.mdl",
        "weapon_alyxgun" => "models/weapons/w_alyx_gun.mdl",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classes_choose_their_models_as_their_spawn_does() {
        let mut actors = Actors::new("d1_trainstation_01");
        // The key is ignored by police, taken by Breen.
        assert_eq!(actors.model("npc_metropolice", Some("models/x.mdl"), 0, 0).as_deref(), Some("models/police.mdl"));
        assert_eq!(actors.model("npc_breen", Some("models/breen_monitor.mdl"), 0, 0).as_deref(), Some("models/breen_monitor.mdl"));
        assert_eq!(actors.model("npc_breen", None, 0, 0).as_deref(), Some("models/breen.mdl"));
        assert_eq!(actors.model("cycler_actor", None, 0, 0), None);
        assert_eq!(actors.model("npc_maker", None, 0, 0), None);
        // A trainstation citizen is downtrodden: group01, whatever folder the key names.
        assert_eq!(
            actors.model("npc_citizen", Some("models/Humans/Group02/male_07.mdl"), 0, 0).as_deref(),
            Some("models/humans/group01/male_07.mdl")
        );
        // Random heads go to the least used first; male_07 is taken already.
        assert_eq!(actors.model("npc_citizen", None, 0, 0).as_deref(), Some("models/humans/group01/male_01.mdl"));
        assert_eq!(actors.model("npc_citizen", None, RANDOM_HEAD_FEMALE, 0).as_deref(), Some("models/humans/group01/female_01.mdl"));
        assert_eq!(actors.model("npc_citizen", None, 0, 3).as_deref(), Some("models/humans/group03/male_02.mdl"));
    }
}
