//! Which model entities the map's logic changes the look or the solidity of.
//!
//! A prop is drawn merged into its section's aggregate and its collision is
//! merged into the map's cells, which is right for the thousands that never
//! change. The few the map's I/O changes — a lamp switching `Skin`, a key a
//! button `Kill`s, a broken chain `Enable`d in place of a whole one — have to
//! stay apart so the mod can change them one at a time. This finds those few
//! by reading every output in the entity lump, the way `CEventQueue` resolves
//! a target.

use std::collections::{BTreeMap, BTreeSet, HashMap};

/// Inputs that change how a model entity looks or collides.
const STATE_INPUTS: &[&str] = &[
    "skin",
    "color",
    "enable",
    "disable",
    "turnon",
    "turnoff",
    "kill",
    "killhierarchy",
    "disablecollision",
    "enablecollision",
];

/// `CBaseAnimating` and `CDynamicProp` inputs that play or change an
/// animation.
const ANIMATION_INPUTS: &[&str] = &[
    "setanimation",
    "setdefaultanimation",
    "setplaybackrate",
    "setcycle",
];

/// Inputs after which the prop may no longer collide.
const COLLISION_INPUTS: &[&str] = &[
    "kill",
    "killhierarchy",
    "disablecollision",
    "enablecollision",
];

/// `CDynamicProp` and the classes built on it that read `StartDisabled` as
/// "spawn hidden".
pub const DYNAMIC_PROP_CLASSES: &[&str] = &[
    "prop_dynamic",
    "prop_dynamic_override",
    "prop_dynamic_ornament",
];

/// What the logic may do to one prop.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Role {
    /// `StartDisabled` on a dynamic prop: spawned with `EF_NODRAW`.
    pub start_hidden: bool,
    /// Removed, or its collision switched, by some input: its collision has
    /// to be removable rather than merged into the map's cells.
    pub collision: bool,
    /// A dynamic prop that plays animations: some input sets one, or it has a
    /// `DefaultAnim` or `RandomAnimation` of its own.
    pub animated: bool,
}

struct Entity {
    classname: String,
    targetname: String,
    parent: String,
    model: bool,
    start_disabled: bool,
    animates: bool,
}

/// Every model entity, by lump index, that some input changes, or that
/// starts hidden.
///
/// A target is resolved as `CEventQueue::ServiceEvents` does it: every entity
/// whose name matches, a trailing `*` matching every name it starts; failing
/// any, every entity of that classname. Targets that name the activator or
/// caller (`!activator`, `!self`, ...) depend on who fires, so they are not
/// followed. Removing an entity removes everything parented to it
/// (`UpdateOnRemove` deletes orphans), so the children of a killed entity
/// count as killed too.
pub fn roles(bsp: &vbsp::Bsp) -> BTreeMap<usize, Role> {
    let lump: Vec<Vec<(String, String)>> = bsp
        .entities
        .iter()
        .map(|raw| {
            raw.properties()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect()
        })
        .collect();
    roles_of(&lump)
}

/// [`roles`] over the entity lump's keyvalues, each entity's in lump order.
pub fn roles_of(lump: &[Vec<(String, String)>]) -> BTreeMap<usize, Role> {
    let entities = entities_of(lump);
    roles_from(lump, &entities)
}

fn entities_of(lump: &[Vec<(String, String)>]) -> Vec<Entity> {
    lump.iter()
        .map(|raw| {
            let mut entity = Entity {
                classname: String::new(),
                targetname: String::new(),
                parent: String::new(),
                model: false,
                start_disabled: false,
                animates: false,
            };
            for (key, value) in raw {
                match key.to_ascii_lowercase().as_str() {
                    "classname" => entity.classname = value.trim().to_ascii_lowercase(),
                    "targetname" => entity.targetname = value.trim().to_ascii_lowercase(),
                    "parentname" => {
                        entity.parent = value
                            .split(',')
                            .next()
                            .unwrap_or("")
                            .trim()
                            .to_ascii_lowercase()
                    }
                    "model" => entity.model = value.trim().to_ascii_lowercase().ends_with(".mdl"),
                    "startdisabled" => entity.start_disabled = atoi(value) != 0,
                    "randomanimation" => entity.animates |= atoi(value) != 0,
                    _ => {}
                }
            }
            entity
        })
        .collect()
}

fn roles_from(lump: &[Vec<(String, String)>], entities: &[Entity]) -> BTreeMap<usize, Role> {
    let mut changed: BTreeSet<usize> = BTreeSet::new();
    let mut collision: BTreeSet<usize> = BTreeSet::new();
    let mut killed: BTreeSet<usize> = BTreeSet::new();
    let animated: BTreeSet<usize> = requests_of(lump).into_keys().collect();
    for raw in lump {
        for (key, value) in raw {
            let crate::bsp::entities::OutputParse::Output(output) =
                crate::bsp::entities::parse_output(key, value)
            else {
                continue;
            };
            let input = output.input.trim().to_ascii_lowercase();
            if !STATE_INPUTS.contains(&input.as_str()) {
                continue;
            }
            let targets = resolve(entities, &output.target);
            if COLLISION_INPUTS.contains(&input.as_str()) {
                collision.extend(&targets);
            }
            if input == "kill" || input == "killhierarchy" {
                killed.extend(&targets);
            }
            changed.extend(targets);
        }
    }

    // Children of a removed entity go with it, through any depth.
    let mut children: HashMap<&str, Vec<usize>> = HashMap::new();
    for (index, entity) in entities.iter().enumerate() {
        if !entity.parent.is_empty() {
            children
                .entry(entity.parent.as_str())
                .or_default()
                .push(index);
        }
    }
    let mut pending: Vec<usize> = killed.iter().copied().collect();
    while let Some(index) = pending.pop() {
        let name = entities[index].targetname.as_str();
        if name.is_empty() {
            continue;
        }
        for &child in children.get(name).into_iter().flatten() {
            if killed.insert(child) {
                pending.push(child);
            }
        }
    }
    changed.extend(&killed);
    collision.extend(&killed);
    // A point_template takes its entities out of the map as it spawns and makes
    // copies of them on `ForceSpawn`: each is drawn and collides on its own.
    let templated = templated_of(lump, entities);
    changed.extend(&templated);
    collision.extend(&templated);

    let mut roles = BTreeMap::new();
    for (index, entity) in entities.iter().enumerate() {
        if !entity.model {
            continue;
        }
        let dynamic = DYNAMIC_PROP_CLASSES.contains(&entity.classname.as_str());
        let start_hidden = entity.start_disabled && dynamic;
        let animates = dynamic && (entity.animates || animated.contains(&index));
        if changed.contains(&index) || start_hidden || animates {
            roles.insert(
                index,
                Role {
                    start_hidden,
                    collision: collision.contains(&index),
                    animated: animates,
                },
            );
        }
    }
    roles
}

/// Every entity some `point_template` names in its `Template01` to
/// `Template16`, by lump index: every entity of that name, a trailing `*`
/// matching every name it starts (`CPointTemplate::StartBuildingTemplates`).
fn templated_of(lump: &[Vec<(String, String)>], entities: &[Entity]) -> BTreeSet<usize> {
    let mut templated = BTreeSet::new();
    for (index, raw) in lump.iter().enumerate() {
        if entities[index].classname != "point_template" {
            continue;
        }
        for (key, value) in raw {
            let key = key.to_ascii_lowercase();
            let Some(number) = key.strip_prefix("template") else {
                continue;
            };
            if number.len() != 2 || !number.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            let name = value.trim().to_ascii_lowercase();
            if name.is_empty() {
                continue;
            }
            let matches = |target: &str| match name.strip_suffix('*') {
                Some(prefix) => target.starts_with(prefix),
                None => target == name,
            };
            templated.extend(
                entities
                    .iter()
                    .enumerate()
                    .filter(|(_, e)| !e.targetname.is_empty() && matches(&e.targetname))
                    .map(|(i, _)| i),
            );
        }
    }
    templated
}

/// The animations the map asks each entity for, by lump index: its own
/// `DefaultAnim`, and the parameter of every `SetAnimation` and
/// `SetDefaultAnimation` sent to it, lowercased. An entity some input sends
/// `SetPlaybackRate` or `SetCycle` is in the map with no name of its own.
pub fn requests_of(lump: &[Vec<(String, String)>]) -> BTreeMap<usize, BTreeSet<String>> {
    let entities = entities_of(lump);
    let mut requests: BTreeMap<usize, BTreeSet<String>> = BTreeMap::new();
    for (index, raw) in lump.iter().enumerate() {
        for (key, value) in raw {
            if key.eq_ignore_ascii_case("defaultanim") && !value.trim().is_empty() {
                requests
                    .entry(index)
                    .or_default()
                    .insert(value.trim().to_ascii_lowercase());
            }
            let crate::bsp::entities::OutputParse::Output(output) =
                crate::bsp::entities::parse_output(key, value)
            else {
                continue;
            };
            let input = output.input.trim().to_ascii_lowercase();
            if !ANIMATION_INPUTS.contains(&input.as_str()) {
                continue;
            }
            let name = output.parameter.trim().to_ascii_lowercase();
            for target in resolve(&entities, &output.target) {
                let names = requests.entry(target).or_default();
                if (input == "setanimation" || input == "setdefaultanimation") && !name.is_empty() {
                    names.insert(name.clone());
                }
            }
        }
    }
    requests
}

fn resolve(entities: &[Entity], target: &str) -> Vec<usize> {
    let target = target.trim().to_ascii_lowercase();
    if target.is_empty() || target.starts_with('!') {
        return Vec::new();
    }
    let matches = |name: &str| match target.strip_suffix('*') {
        Some(prefix) => name.starts_with(prefix),
        None => name == target,
    };
    let named: Vec<usize> = entities
        .iter()
        .enumerate()
        .filter(|(_, e)| !e.targetname.is_empty() && matches(&e.targetname))
        .map(|(i, _)| i)
        .collect();
    if !named.is_empty() {
        return named;
    }
    entities
        .iter()
        .enumerate()
        .filter(|(_, e)| matches(&e.classname))
        .map(|(i, _)| i)
        .collect()
}

/// `atoi`, as `CDynamicProp` reads its integer keyvalues.
pub fn atoi_text(text: &str) -> i64 {
    atoi(text)
}

fn atoi(text: &str) -> i64 {
    let text = text.trim();
    let (sign, digits) = match text.strip_prefix('-') {
        Some(rest) => (-1, rest),
        None => (1, text.strip_prefix('+').unwrap_or(text)),
    };
    sign * digits
        .chars()
        .take_while(char::is_ascii_digit)
        .fold(0i64, |acc, d| {
            (acc * 10 + i64::from(d as u8 - b'0')).min(1 << 40)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entity(pairs: &[(&str, &str)]) -> Vec<(String, String)> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    /// Furnace's key pickups: a button kills the key, which takes its
    /// collision with it; a lamp only switches skin and stays solid.
    #[test]
    fn inputs_pick_the_props_and_kill_makes_collision_removable() {
        let lump = vec![
            entity(&[("classname", "worldspawn")]),
            entity(&[
                ("classname", "infra_button"),
                ("OnPressed", "hatch_key\x1bKill\x1b\x1b0\x1b-1"),
                ("OnPressed", "Lamp_*\x1bSkin\x1b1\x1b0\x1b-1"),
            ]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("targetname", "hatch_key"),
                ("model", "models/items/keys_001.mdl"),
            ]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("targetname", "lamp_1"),
                ("model", "models/lamp.mdl"),
            ]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("targetname", "untouched"),
                ("model", "models/lamp.mdl"),
            ]),
        ];
        let roles = roles_of(&lump);
        assert_eq!(
            roles.get(&2),
            Some(&Role {
                start_hidden: false,
                collision: true,
                animated: false
            })
        );
        assert_eq!(
            roles.get(&3),
            Some(&Role {
                start_hidden: false,
                collision: false,
                animated: false
            })
        );
        assert!(!roles.contains_key(&4));
        assert!(!roles.contains_key(&1), "a brush entity is no prop");
    }

    /// `UpdateOnRemove` deletes a removed entity's children; a dynamic prop
    /// with `StartDisabled` spawns hidden whether or not anything enables it.
    #[test]
    fn children_of_a_killed_entity_and_hidden_props_count() {
        let lump = vec![
            entity(&[
                ("classname", "logic_relay"),
                ("OnTrigger", "cart,Kill,,0,-1"),
            ]),
            entity(&[("classname", "func_brush"), ("targetname", "cart")]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("targetname", "lever"),
                ("parentname", "cart"),
                ("model", "models/lever.mdl"),
            ]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("parentname", "lever,attach"),
                ("model", "models/knob.mdl"),
            ]),
            entity(&[
                ("classname", "prop_dynamic_override"),
                ("StartDisabled", "1"),
                ("model", "models/fuse.mdl"),
            ]),
            entity(&[
                ("classname", "prop_physics"),
                ("StartDisabled", "1"),
                ("model", "models/crate.mdl"),
            ]),
        ];
        let roles = roles_of(&lump);
        assert!(roles[&2].collision);
        assert!(roles[&3].collision, "a grandchild goes with its parent");
        assert_eq!(
            roles.get(&4),
            Some(&Role {
                start_hidden: true,
                collision: false,
                animated: false
            })
        );
        assert!(!roles.contains_key(&5), "prop_physics has no StartDisabled");
    }

    /// No entity has the name, so the classname is tried, as Source does.
    #[test]
    fn an_unmatched_name_falls_back_to_the_classname() {
        let lump = vec![
            entity(&[
                ("classname", "logic_auto"),
                ("OnMapSpawn", "prop_dynamic,Color,255 0 0,0,-1"),
            ]),
            entity(&[("classname", "prop_dynamic"), ("model", "models/a.mdl")]),
            entity(&[
                ("classname", "logic_auto"),
                ("OnMapSpawn", "!activator,Skin,1,0,-1"),
            ]),
        ];
        let roles = roles_of(&lump);
        assert_eq!(roles.keys().copied().collect::<Vec<_>>(), vec![1]);
    }

    /// bts4's conveyor: a point_template names the belt module and every gib
    /// (`*` too); they leave the map at spawn and come back as copies.
    #[test]
    fn a_point_templates_entities_are_logic_props() {
        let lump = vec![
            entity(&[
                ("classname", "point_template"),
                ("Template01", "belt_model"),
                ("Template02", "gib_*"),
            ]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("targetname", "belt_model"),
                ("model", "models/belt.mdl"),
            ]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("targetname", "gib_small"),
                ("model", "models/gib.mdl"),
            ]),
            entity(&[
                ("classname", "prop_dynamic"),
                ("targetname", "other"),
                ("model", "models/gib.mdl"),
            ]),
        ];
        let roles = roles_of(&lump);
        assert_eq!(roles.keys().copied().collect::<Vec<_>>(), vec![1, 2]);
        assert!(roles[&1].collision && roles[&2].collision);
    }
}
