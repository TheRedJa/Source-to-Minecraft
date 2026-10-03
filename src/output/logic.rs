//! A map's entities for the mod's logic runtime (format section 16): every
//! entity of the lump with its keyvalues and outputs, the shapes of its brush
//! entities, the choreography scenes it plays and the captions they show.
//!
//! The table is complete rather than cut to what the runtime uses today, so
//! later features read the map's own data instead of needing a new export.

use crate::bsp::entities::{EntityRecord, Output, OutputParse, parse_output};
use crate::geom::Vec3;
use crate::output::bundle;
use crate::output::metadata::{Diagnostic, Severity};
use crate::source::vcd;
use crate::source::vfs::{Vfs, read_file};
use crate::voxel::transform::Transform;
use anyhow::{Context, Result};
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet, HashMap};

pub const FORMAT: &str = "src2mc-logic";

/// The caption files read, in order; a token the first defines wins. INFRA
/// keeps its dialogue in `subtitles_english.txt` and only sound effects in
/// `closecaption_english.txt`.
const CAPTION_FILES: &[&str] = &[
    "resource/closecaption_english.txt",
    "resource/subtitles_english.txt",
];
/// Classnames of `CSceneEntity`; `scripted_scene` is its older name.
const SCENE_CLASSES: &[&str] = &["logic_choreographed_scene", "scripted_scene"];

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LogicTable {
    pub format: &'static str,
    pub version: u32,
    /// The map-local block position of Source's origin.
    pub source_origin: [f64; 3],
    pub entities: Vec<Entity>,
    pub volumes: Vec<Volume>,
    pub scenes: Vec<Scene>,
    pub captions: Vec<Caption>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Entity {
    /// Lowercase.
    pub classname: String,
    /// Every pair but the outputs, in lump order, keys as written.
    pub keyvalues: Vec<(String, String)>,
    pub outputs: Vec<Output>,
    /// Map-local position of the `origin` keyvalue.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin: Option<[f64; 3]>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub volume: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub scene: Option<u32>,
}

/// A brush entity's shape.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Volume {
    /// Map-local `[min x, min y, min z, max x, max y, max z]`.
    pub bounds: [f64; 6],
    /// Convex brushes, each a list of `[nx, ny, nz, d]` planes with unit
    /// outward normals: a point is inside where `n . p <= d` for all.
    pub brushes: Vec<Vec<[f64; 4]>>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Scene {
    /// The scene path as the entity names it.
    pub file: String,
    /// Seconds.
    pub length: f64,
    pub events: Vec<SceneEvent>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum SceneEvent {
    Speak {
        actor: String,
        start: f64,
        end: f64,
        /// Lowercase soundscript name.
        script: String,
        /// Lowercase caption token; present only when `captions` has it.
        #[serde(skip_serializing_if = "Option::is_none")]
        caption: Option<String>,
    },
    FireTrigger {
        start: f64,
        /// The `OnTrigger<n>` output the event fires, 1 to 16.
        trigger: u8,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Caption {
    /// Lowercase.
    pub token: String,
    /// As the caption file writes it, tags included.
    pub text: String,
}

impl LogicTable {
    pub fn encode(&self) -> Result<Vec<u8>> {
        bundle::canonical_json(self)
    }
}

pub struct LogicExport {
    pub table: LogicTable,
    pub diagnostics: Vec<Diagnostic>,
}

/// The scenes a map's scene entities play, read before the sound table so
/// that it can carry the lines they speak.
pub struct Scenes {
    /// Path as first named, and its parsed contents.
    scenes: Vec<(String, vcd::Scene)>,
    /// Scene index by entity lump index.
    by_entity: HashMap<usize, u32>,
    diagnostics: Vec<Diagnostic>,
}

impl Scenes {
    /// Read the text `.vcd` of every scene entity's `SceneFile`, from the
    /// map's pakfile or the game. Compiled scenes (`scenes.image`) are not
    /// read, so a game that only ships those has none.
    pub fn load(vfs: &Vfs, pak: Option<&vbsp::Packfile>, entities: &[EntityRecord]) -> Scenes {
        let mut scenes = Vec::new();
        let mut by_path: HashMap<String, Option<u32>> = HashMap::new();
        let mut by_entity = HashMap::new();
        let mut unavailable = BTreeSet::new();
        for entity in entities {
            let class = entity.classname.to_ascii_lowercase();
            if !SCENE_CLASSES.contains(&class.as_str()) {
                continue;
            }
            let Some(file) = entity
                .get("SceneFile")
                .map(str::trim)
                .filter(|f| !f.is_empty())
            else {
                continue;
            };
            let key = file.to_ascii_lowercase().replace('\\', "/");
            let index = *by_path.entry(key.clone()).or_insert_with(|| {
                let bytes = read_file(vfs, pak, &key)?;
                let id = scenes.len() as u32;
                scenes.push((
                    file.to_string(),
                    vcd::parse(&String::from_utf8_lossy(&bytes)),
                ));
                Some(id)
            });
            match index {
                Some(id) => {
                    by_entity.insert(entity.index, id);
                }
                None => {
                    unavailable.insert(file.to_string());
                }
            }
        }
        let diagnostics = unavailable
            .into_iter()
            .map(|file| {
                diagnostic(
                    Severity::Warning,
                    "LOGIC_SCENE_UNAVAILABLE",
                    "a scene entity names a scene with no text .vcd; it plays nothing",
                    [("file", file)],
                )
            })
            .collect();
        Scenes {
            scenes,
            by_entity,
            diagnostics,
        }
    }

    /// The soundscripts the scenes' lines speak, as written.
    pub fn speak_scripts(&self) -> BTreeSet<String> {
        self.scenes
            .iter()
            .flat_map(|(_, scene)| &scene.events)
            .filter_map(|event| match event {
                vcd::Event::Speak { script, .. } => Some(script.clone()),
                vcd::Event::FireTrigger { .. } => None,
            })
            .collect()
    }
}

/// Build the logic table. `audio_scripts` are the names in the sound table,
/// whose captions are exported along with the scenes' own.
pub fn build(
    map: &crate::bsp::Map,
    vfs: &Vfs,
    transform: &Transform,
    entities: &[EntityRecord],
    scenes: Scenes,
    audio_scripts: &BTreeSet<String>,
) -> Result<LogicExport> {
    let mut diagnostics = scenes.diagnostics;
    let mut malformed = 0usize;
    let mut output_count = 0usize;
    let mut volumes = Vec::new();
    let mut table_entities = Vec::with_capacity(entities.len());
    for entity in entities {
        let mut keyvalues = Vec::new();
        let mut outputs = Vec::new();
        for (key, value) in &entity.keyvalues {
            match parse_output(key, value) {
                OutputParse::Output(output) => outputs.push(output),
                OutputParse::Malformed => {
                    malformed += 1;
                    keyvalues.push((key.clone(), value.clone()));
                }
                OutputParse::NotOutput => keyvalues.push((key.clone(), value.clone())),
            }
        }
        output_count += outputs.len();
        let volume = match entity.brush_model {
            Some(model) if model > 0 && model < map.bsp.models.len() => {
                let origin = entity
                    .origin_source
                    .map(|[x, y, z]| Vec3::new(x, y, z))
                    .unwrap_or(Vec3::ZERO);
                volume(map, transform, model, origin)?.map(|v| {
                    volumes.push(v);
                    volumes.len() as u32 - 1
                })
            }
            _ => None,
        };
        table_entities.push(Entity {
            classname: entity.classname.to_ascii_lowercase(),
            keyvalues,
            outputs,
            origin: entity.origin_mc.map(canonical3).transpose()?,
            volume,
            scene: scenes.by_entity.get(&entity.index).copied(),
        });
    }

    // Each line's caption token: its own, or the line's soundscript.
    let caption_token = |event: &vcd::Event| match event {
        vcd::Event::Speak {
            script,
            cctoken,
            caption_disabled: false,
            ..
        } => Some(if cctoken.is_empty() { script } else { cctoken }.to_ascii_lowercase()),
        _ => None,
    };
    let mut wanted: BTreeSet<String> = audio_scripts
        .iter()
        .map(|s| s.to_ascii_lowercase())
        .collect();
    wanted.extend(
        scenes
            .scenes
            .iter()
            .flat_map(|(_, scene)| &scene.events)
            .filter_map(caption_token),
    );
    let all_captions = load_captions(vfs, Some(&map.bsp.pack));
    let captions: BTreeMap<String, String> = wanted
        .into_iter()
        .filter_map(|token| all_captions.get(&token).map(|text| (token, text.clone())))
        .collect();

    let mut table_scenes = Vec::with_capacity(scenes.scenes.len());
    for (file, scene) in scenes.scenes {
        let mut events = Vec::with_capacity(scene.events.len());
        for event in &scene.events {
            let token = caption_token(event).filter(|t| captions.contains_key(t));
            events.push(match event {
                vcd::Event::Speak {
                    actor,
                    start,
                    end,
                    script,
                    ..
                } => SceneEvent::Speak {
                    actor: actor.clone(),
                    start: canonical(*start)?,
                    end: canonical(*end)?,
                    script: script.to_ascii_lowercase(),
                    caption: token,
                },
                vcd::Event::FireTrigger { start, trigger } => SceneEvent::FireTrigger {
                    start: canonical(*start)?,
                    trigger: *trigger,
                },
            });
        }
        table_scenes.push(Scene {
            file,
            length: canonical(scene.length)?,
            events,
        });
    }

    if malformed > 0 {
        diagnostics.push(diagnostic(
            Severity::Warning,
            "LOGIC_OUTPUT_MALFORMED",
            "outputs whose value does not split into five fields; kept as plain keyvalues",
            [("count", malformed.to_string())],
        ));
    }
    let o = transform.to_block_space(Vec3::ZERO);
    let table = LogicTable {
        format: FORMAT,
        version: 1,
        source_origin: canonical3([o.x, o.y, o.z])?,
        entities: table_entities,
        volumes,
        scenes: table_scenes,
        captions: captions
            .into_iter()
            .map(|(token, text)| Caption { token, text })
            .collect(),
    };
    diagnostics.push(diagnostic(
        Severity::Info,
        "LOGIC_SUMMARY",
        "entity logic exported for the mod",
        [
            ("entities", table.entities.len().to_string()),
            ("outputs", output_count.to_string()),
            ("volumes", table.volumes.len().to_string()),
            ("scenes", table.scenes.len().to_string()),
            ("captions", table.captions.len().to_string()),
        ],
    ));
    Ok(LogicExport { table, diagnostics })
}

/// Every brush of a BSP model, moved to the entity's origin as the drawn
/// geometry is, in map-local blocks. `None` when no brush encloses a volume.
fn volume(
    map: &crate::bsp::Map,
    transform: &Transform,
    model: usize,
    origin: Vec3,
) -> Result<Option<Volume>> {
    let mut bounds = crate::geom::Aabb::empty();
    let mut brushes = Vec::new();
    for solid in map.solids(model) {
        let block = crate::convert::to_block_solid(&solid, transform, origin);
        bounds.extend(block.bounds.min);
        bounds.extend(block.bounds.max);
        brushes.push(
            block
                .planes
                .iter()
                .map(|plane| plane_record(plane.normal, plane.dist))
                .collect::<Result<Vec<_>>>()?,
        );
    }
    if brushes.is_empty() {
        return Ok(None);
    }
    Ok(Some(Volume {
        bounds: [
            canonical(bounds.min.x)?,
            canonical(bounds.min.y)?,
            canonical(bounds.min.z)?,
            canonical(bounds.max.x)?,
            canonical(bounds.max.y)?,
            canonical(bounds.max.z)?,
        ],
        brushes,
    }))
}

/// `[nx, ny, nz, d]` with a unit normal. Components a rounding error away
/// from zero are written as zero.
fn plane_record(normal: Vec3, dist: f64) -> Result<[f64; 4]> {
    let length = normal.length();
    anyhow::ensure!(length > 0.0, "degenerate brush plane");
    let snap = |v: f64| if v.abs() < 1.0e-12 { 0.0 } else { v };
    Ok([
        canonical(snap(normal.x / length))?,
        canonical(snap(normal.y / length))?,
        canonical(snap(normal.z / length))?,
        canonical(dist / length)?,
    ])
}

/// The caption files' tokens, the first file defining a token winning.
fn load_captions(vfs: &Vfs, pak: Option<&vbsp::Packfile>) -> HashMap<String, String> {
    let mut captions = HashMap::new();
    for file in CAPTION_FILES {
        let Some(bytes) = read_file(vfs, pak, file) else {
            continue;
        };
        let text = crate::source::captions::decode(&bytes);
        for (token, caption) in crate::source::captions::parse(&text) {
            captions.entry(token).or_insert(caption);
        }
    }
    captions
}

fn canonical(value: f64) -> Result<f64> {
    bundle::canonical_f64(value).context("non-finite logic value")
}

fn canonical3(value: [f64; 3]) -> Result<[f64; 3]> {
    Ok([
        canonical(value[0])?,
        canonical(value[1])?,
        canonical(value[2])?,
    ])
}

fn diagnostic(
    severity: Severity,
    code: &str,
    message: &str,
    context: impl IntoIterator<Item = (impl Into<String>, String)>,
) -> Diagnostic {
    Diagnostic {
        severity,
        code: code.into(),
        message: message.into(),
        context: context.into_iter().map(|(k, v)| (k.into(), v)).collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn planes_face_outward_from_the_brush() {
        // A 64-unit cube from Source (0,0,0) to (64,64,64), as VBSP writes
        // its six axial sides, moved to an entity origin of (32,0,0).
        let sides = [
            (Vec3::new(1.0, 0.0, 0.0), 64.0),
            (Vec3::new(-1.0, 0.0, 0.0), 0.0),
            (Vec3::new(0.0, 1.0, 0.0), 64.0),
            (Vec3::new(0.0, -1.0, 0.0), 0.0),
            (Vec3::new(0.0, 0.0, 1.0), 64.0),
            (Vec3::new(0.0, 0.0, -1.0), 0.0),
        ];
        let config = crate::config::Config::default();
        let transform = Transform::new(&config, crate::geom::Aabb::empty());
        let origin = Vec3::new(32.0, 0.0, 0.0);
        let planes: Vec<[f64; 4]> = sides
            .iter()
            .map(|&(n, d)| {
                let moved = crate::geom::Plane::new(n, d + n.dot(origin));
                let p = transform.transform_plane(moved);
                plane_record(p.normal, p.dist).unwrap()
            })
            .collect();
        let inside = |source: Vec3| {
            let p = transform.to_block_space(source);
            planes
                .iter()
                .all(|[x, y, z, d]| x * p.x + y * p.y + z * p.z <= *d)
        };
        assert!(
            inside(Vec3::new(64.0, 32.0, 32.0)),
            "centre of the moved cube"
        );
        assert!(inside(Vec3::new(95.9, 0.1, 63.9)));
        assert!(
            !inside(Vec3::new(16.0, 32.0, 32.0)),
            "left behind by the origin"
        );
        assert!(!inside(Vec3::new(64.0, 32.0, 70.0)), "above");
        assert!(!inside(Vec3::new(64.0, -1.0, 32.0)));
        for plane in &planes {
            let length = (plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]).sqrt();
            assert!((length - 1.0).abs() < 1e-12);
        }
    }

    #[test]
    fn scene_events_serialize_tagged_by_type() {
        let events = vec![
            SceneEvent::Speak {
                actor: "player".into(),
                start: 0.5,
                end: 2.0,
                script: "player.line".into(),
                caption: None,
            },
            SceneEvent::FireTrigger {
                start: 1.0,
                trigger: 3,
            },
        ];
        assert_eq!(
            serde_json::to_string(&events).unwrap(),
            r#"[{"type":"speak","actor":"player","start":0.5,"end":2.0,"script":"player.line"},{"type":"firetrigger","start":1.0,"trigger":3}]"#
        );
    }

    #[test]
    fn entities_keep_keyvalues_apart_from_outputs() {
        let entity = Entity {
            classname: "trigger_once".into(),
            keyvalues: vec![("targetname".into(), "t".into())],
            outputs: Vec::new(),
            origin: None,
            volume: Some(0),
            scene: None,
        };
        assert_eq!(
            serde_json::to_string(&entity).unwrap(),
            r#"{"classname":"trigger_once","keyvalues":[["targetname","t"]],"outputs":[],"volume":0}"#
        );
    }
}
