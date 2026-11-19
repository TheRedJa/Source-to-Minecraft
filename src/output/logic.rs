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
use crate::source::credits::Credits;
use crate::source::scene_image::SceneImage;
use crate::source::titles::Title;
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
    /// Localized texts of the `#` tokens the map's texts name; absent when none.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub strings: Vec<Caption>,
    /// Entities the game's own code creates in the map; absent when none.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub engine_entities: Vec<Entity>,
    /// Inputs the game's own code queues as the map spawns; absent when none.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub engine_events: Vec<EngineEvent>,
    /// The `scripts/titles.txt` messages the map's `env_message` entities
    /// name; absent when none is defined.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub titles: Vec<Title>,
    /// `scripts/credits.txt`, present when the map has an `env_credits`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub credits: Option<Credits>,
    /// Bundle payloads the table points at, as (path below the map's
    /// directory, bytes); the campaign writer adds them. Not serialized.
    #[serde(skip)]
    pub assets: Vec<(String, Vec<u8>)>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct EngineEvent {
    pub target: String,
    pub input: String,
    pub parameter: String,
    /// Seconds after spawn; finite and not negative.
    pub delay: f64,
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
    /// Read every scene entity's `SceneFile`: its text `.vcd` from the map's
    /// pakfile or the game, else the compiled scene in the game's
    /// `scenes/scenes.image` (all Half-Life 2 ships).
    pub fn load(vfs: &Vfs, pak: Option<&vbsp::Packfile>, entities: &[EntityRecord]) -> Scenes {
        let mut scenes = Vec::new();
        let mut by_path: HashMap<String, Option<u32>> = HashMap::new();
        let mut by_entity = HashMap::new();
        let mut unavailable = BTreeSet::new();
        let mut broken = BTreeMap::new();
        let mut image: Option<Option<SceneImage>> = None;
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
                let scene = match read_file(vfs, pak, &key) {
                    Some(bytes) => vcd::parse(&String::from_utf8_lossy(&bytes)),
                    None => {
                        let image = image.get_or_insert_with(|| {
                            vfs.open("scenes/scenes.image")
                                .and_then(|bytes| SceneImage::parse(bytes).ok())
                        });
                        match image.as_ref()?.scene(&key)? {
                            Ok(scene) => scene.to_vcd(),
                            Err(error) => {
                                broken.insert(file.to_string(), format!("{error:#}"));
                                return None;
                            }
                        }
                    }
                };
                let id = scenes.len() as u32;
                scenes.push((file.to_string(), scene));
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
        let mut diagnostics: Vec<Diagnostic> = broken
            .iter()
            .map(|(file, error)| {
                diagnostic(
                    Severity::Warning,
                    "LOGIC_SCENE_MALFORMED",
                    "a compiled scene in scenes.image does not read; it plays nothing",
                    [("file", file.clone()), ("error", error.clone())],
                )
            })
            .collect();
        diagnostics.extend(
            unavailable
                .into_iter()
                .filter(|file| !broken.contains_key(file))
                .map(|file| {
                    diagnostic(
                        Severity::Warning,
                        "LOGIC_SCENE_UNAVAILABLE",
                        "a scene entity names a scene that is neither a .vcd nor in scenes.image; it plays nothing",
                        [("file", file)],
                    )
                }),
        );
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
    // What INFRA's game code adds: its chapter title texts and the script
    // that sets and shows them.
    let mut engine_entities = Vec::new();
    let mut engine_events = Vec::new();
    if let Some(script) = read_file(vfs, Some(&map.bsp.pack), crate::source::infra::SCRIPT)
        && let Some(title) =
            crate::source::infra::chapter_title(&String::from_utf8_lossy(&script), &map.name)
    {
        for keyvalues in crate::source::infra::chapter_entities() {
            engine_entities.push(Entity {
                classname: "game_text".into(),
                keyvalues,
                outputs: Vec::new(),
                origin: None,
                volume: None,
                scene: None,
            });
        }
        for event in crate::source::infra::chapter_events(&title) {
            engine_events.push(EngineEvent {
                target: event.target.into(),
                input: event.input.into(),
                parameter: event.parameter,
                delay: canonical(event.delay)?,
            });
        }
    }
    // Texts naming a `#` token show the token's localized text, as
    // `g_pVGuiLocalize->Find` gives the HUD.
    let token = |text: &str| text.strip_prefix('#').map(str::to_ascii_lowercase);
    let mut wanted_strings: BTreeSet<String> = BTreeSet::new();
    for entity in table_entities.iter().chain(&engine_entities) {
        if entity.classname == "game_text"
            && let Some(message) = entity
                .keyvalues
                .iter()
                .rev()
                .find(|(k, _)| k.eq_ignore_ascii_case("message"))
        {
            wanted_strings.extend(token(&message.1));
        }
        // `CHudHintKeyDisplay::SetHintText` looks the `message` up with
        // `g_pVGuiLocalize->Find` as written: hint entities name their tokens
        // without the `#`.
        if entity.classname == "env_hudhint"
            && let Some(message) = entity
                .keyvalues
                .iter()
                .rev()
                .find(|(k, _)| k.eq_ignore_ascii_case("message"))
        {
            let name = message.1.strip_prefix('#').unwrap_or(&message.1);
            if !name.is_empty() {
                wanted_strings.insert(name.to_ascii_lowercase());
            }
        }
        for output in &entity.outputs {
            if output.input.eq_ignore_ascii_case("settext") {
                wanted_strings.extend(token(&output.parameter));
            }
        }
    }
    for event in &engine_events {
        if event.input.eq_ignore_ascii_case("settext") {
            wanted_strings.extend(token(&event.parameter));
        }
    }
    // The HUD messages `env_message` shows, by the name `message` gives.
    let mut title_names: Vec<&str> = Vec::new();
    for entity in table_entities
        .iter()
        .filter(|e| e.classname == "env_message")
    {
        if let Some((_, message)) = entity
            .keyvalues
            .iter()
            .rev()
            .find(|(k, _)| k.eq_ignore_ascii_case("message"))
        {
            let name = message.strip_prefix('#').unwrap_or(message);
            if !name.is_empty()
                && !title_names
                    .iter()
                    .any(|seen| seen.eq_ignore_ascii_case(name))
            {
                title_names.push(name);
            }
        }
    }
    let pak = Some(&map.bsp.pack);
    let mut titles: Vec<Title> = Vec::new();
    if !title_names.is_empty() {
        let defined = crate::source::titles::load(vfs, pak);
        for name in &title_names {
            if let Some(title) = crate::source::titles::find(&defined, name) {
                titles.push(title.clone());
            }
        }
    }
    let all_strings = if wanted_strings.is_empty() && titles.is_empty() {
        HashMap::new()
    } else {
        load_strings(vfs, pak)
    };
    // A title whose text is a `#` token is drawn with the token's localized
    // text; one that names nothing is drawn as written
    // (`CHudMessage::MessageAdd` falls back to the raw string).
    for title in &mut titles {
        if let Some(token) = title.text.trim().strip_prefix('#')
            && let Some(text) = all_strings.get(&token.trim().to_ascii_lowercase())
        {
            title.text = text.clone();
        }
        for number in [
            &mut title.x,
            &mut title.y,
            &mut title.fadein,
            &mut title.fadeout,
            &mut title.holdtime,
            &mut title.fxtime,
        ] {
            *number = canonical(*number)?;
        }
    }
    let strings = wanted_strings
        .into_iter()
        .filter_map(|token| {
            all_strings.get(&token).map(|text| Caption {
                token,
                text: text.clone(),
            })
        })
        .collect::<Vec<_>>();

    // The credits roll and its logo, for `env_credits`.
    let mut assets = Vec::new();
    let credits = if table_entities.iter().any(|e| e.classname == "env_credits") {
        match crate::source::credits::load(vfs) {
            Some(mut credits) => {
                match crate::source::credits::render_logo(vfs, &credits) {
                    Some(png) => {
                        let path = "logic/credits_logo.png";
                        credits.logo_image = Some(path.to_string());
                        assets.push((path.to_string(), png));
                    }
                    None => diagnostics.push(diagnostic(
                        Severity::Warning,
                        "LOGIC_CREDITS_LOGO",
                        "the credits logo could not be drawn: the scheme font or its file is missing",
                        [("font", "WeaponIcons".to_string())],
                    )),
                }
                Some(credits)
            }
            None => {
                diagnostics.push(diagnostic(
                    Severity::Warning,
                    "LOGIC_CREDITS_MISSING",
                    "the map has an env_credits but scripts/credits.txt is missing or empty",
                    [("file", "scripts/credits.txt".to_string())],
                ));
                None
            }
        }
    } else {
        None
    };

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
        strings,
        engine_entities,
        engine_events,
        titles,
        credits,
        assets,
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
            ("strings", table.strings.len().to_string()),
            ("engine_entities", table.engine_entities.len().to_string()),
            ("titles", table.titles.len().to_string()),
            ("credits", table.credits.is_some().to_string()),
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

/// Localization files an archive may hold instead of the game directory,
/// which `Vfs::loose_files` cannot list: Valve's shared HUD strings and the
/// game's own.
const ARCHIVED_STRING_FILES: &[&str] = &[
    "resource/valve_english.txt",
    "resource/hl2_english.txt",
    "resource/gameui_english.txt",
];

/// Every `resource/*_english.txt`, as the HUD's localization loads them: the
/// loose files, then the well-known ones an archive holds. A token the first
/// file in path order defines wins, and so does the first definition within a
/// file once `[$SYMBOL]` conditionals are applied (`valve_english.txt` has
/// `Valve_Hint_Movement` for both `[$X360]` and `[$WIN32]`).
pub fn load_strings(vfs: &Vfs, pak: Option<&vbsp::Packfile>) -> HashMap<String, String> {
    let mut files = vfs.loose_files("resource/", "_english.txt");
    for file in ARCHIVED_STRING_FILES {
        if !files.iter().any(|f| f == file) {
            files.push((*file).to_string());
        }
    }
    let mut strings = HashMap::new();
    for file in files {
        let Some(bytes) = read_file(vfs, pak, &file) else {
            continue;
        };
        let text = apply_conditionals(&crate::source::captions::decode(&bytes));
        for (token, value) in crate::source::captions::parse(&text) {
            strings.entry(token).or_insert(value);
        }
    }
    strings
}

/// Localization files put a platform conditional after a pair
/// (`"token" "text" [$WIN32]`): drop the line when it is false and the
/// conditional itself when it holds, with `kvfile`'s platform (Windows PC).
fn apply_conditionals(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for line in text.lines() {
        let trimmed = line.trim_end();
        if let Some(open) = trimmed.strip_suffix(']').and_then(|l| l.rfind('['))
            && trimmed[open..].contains('$')
        {
            let condition = &trimmed[open + 1..trimmed.len() - 1];
            if crate::source::kvfile::evaluate(condition) {
                out.push_str(&trimmed[..open]);
                out.push('\n');
            }
            continue;
        }
        out.push_str(line);
        out.push('\n');
    }
    out
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
    #[test]
    fn conditionals_pick_the_pc_variant_of_a_string() {
        let text = "\"A\" \"console\" [$X360]\n\"A\" \"pc\" [$WIN32]\n\"B\" \"plain [x]\"\n\"C\" \"both\"\t[!$X360] \n";
        let kept = apply_conditionals(text);
        assert_eq!(
            kept,
            "\"A\" \"pc\" \n\"B\" \"plain [x]\"\n\"C\" \"both\"\t\n"
        );
    }

    /// The hint tokens of Half-Life 2's trainstation maps are all found, with
    /// their binding placeholders and the PC variant of the two-platform one.
    #[test]
    fn half_life_2_hint_tokens_are_localized() {
        let map = std::path::Path::new(
            "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_01.bsp",
        );
        if !map.exists() {
            return;
        }
        let vfs = Vfs::for_map(map, &[]);
        let strings = load_strings(&vfs, None);
        for token in [
            "valve_hint_usepickup",
            "valve_hint_ladder",
            "valve_hint_jump",
            "valve_hint_movement",
            "valve_hint_interact",
            "valve_hint_throwheld",
            "valve_hint_hevcharger",
            "valve_hint_sprint",
            "valve_hint_flashlight",
            "valve_hint_zoom",
            "valve_hint_attack",
        ] {
            assert!(strings.contains_key(token), "{token} missing");
        }
        assert_eq!(strings["valve_hint_jump"], "%+jump% JUMP");
        assert_eq!(
            strings["valve_hint_movement"],
            "FORWARD %+forward%BACK %+back%LEFT %+moveleft%RIGHT %+moveright%"
        );
        assert_eq!(strings["hl2_chapter1_title"], "POINT INSERTION");
    }
    /// The JSON the mod's validator reads (`LogicTitlesTest` holds the same
    /// text): keys in this order, empty `titles` and absent `credits` left out.
    #[test]
    fn titles_and_credits_serialize_in_the_documented_order() {
        let mut table = LogicTable {
            format: FORMAT,
            version: 1,
            source_origin: [0.0, 0.0, 0.0],
            entities: Vec::new(),
            volumes: Vec::new(),
            scenes: Vec::new(),
            captions: Vec::new(),
            strings: Vec::new(),
            engine_entities: Vec::new(),
            engine_events: Vec::new(),
            titles: Vec::new(),
            credits: None,
            assets: Vec::new(),
        };
        let bare = String::from_utf8(table.encode().unwrap()).unwrap();
        assert!(!bare.contains("titles") && !bare.contains("credits") && !bare.contains("assets"));
        table.titles = vec![Title {
            name: "CHAPTER1_TITLE".into(),
            x: -1.0,
            y: 0.58,
            effect: 2,
            color1: [100, 100, 100, 255],
            color2: [240, 110, 0, 255],
            fadein: 0.01,
            fadeout: 1.5,
            holdtime: 3.5,
            fxtime: 0.25,
            text: "POINT INSERTION".into(),
        }];
        table.credits = Some(Credits {
            params: BTreeMap::from([("logo".into(), "HALF-LIFE'".into())]),
            intro: vec![crate::source::credits::CreditName {
                name: "V".into(),
                font: "WeaponIcons".into(),
            }],
            outro: Vec::new(),
            logo_image: Some("logic/credits_logo.png".into()),
        });
        let json = String::from_utf8(table.encode().unwrap()).unwrap();
        assert!(
            json.ends_with(concat!(
                r#","titles":[{"name":"CHAPTER1_TITLE","x":-1.0,"y":0.58,"effect":2,"color1":[100,100,100,255],"color2":[240,110,0,255],"fadein":0.01,"fadeout":1.5,"holdtime":3.5,"fxtime":0.25,"text":"POINT INSERTION"}],"#,
                r#""credits":{"params":{"logo":"HALF-LIFE'"},"intro":[{"name":"V","font":"WeaponIcons"}],"outro":[],"logo_image":"logic/credits_logo.png"}}"#,
                "\n"
            )),
            "{json}"
        );
    }
}
