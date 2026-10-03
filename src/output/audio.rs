//! A map's sound for the mod (format section 15): the soundscapes its
//! `env_soundscape` entities select, its `ambient_generic` sounds, what each
//! surface property sounds like to walk on, and the soundscripts its logic
//! plays by name (scene lines, music, buttons and doors).
//!
//! Every sound entity is exported with its flags, whether or not it plays
//! from the start; which ones wait for the map's logic is the mod's call.
//!
//! Positions are map-local blocks. Sound levels, radii and the falloff the mod
//! computes from them stay in Source's own terms; the mod converts distances
//! back to Source units (32 per block) to apply them.

use crate::bsp::entities::EntityRecord;
use crate::output::bundle;
use crate::output::metadata::{Diagnostic, Severity};
use crate::source::keyvalues::{Block, Value};
use crate::source::sound::{
    Interval, SNDLVL_NORM, SoundScripts, Soundscapes, SurfaceProperties, WaveRef, read_attenuation,
    read_interval, read_pitch, read_sound_level, read_volume,
};
use crate::source::vfs::Vfs;
use anyhow::{Context, Result};
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet, HashMap};

pub const FORMAT: &str = "src2mc-audio";

/// Source's `MAX_SOUNDSCAPE_RECURSION`; the mod applies it again at runtime.
const MAX_SOUNDSCAPE_DEPTH: usize = 8;
/// `NUM_AUDIO_LOCAL_SOUNDS`: an `env_soundscape` names up to eight positions.
const LOCAL_POSITIONS: usize = 8;
const UNITS_PER_BLOCK: f64 = 32.0;

/// Player soundscripts the mod plays by name on landing. HL2-based games play
/// `Player.FallDamage` when a fall hurts (`CMoveHelperServer::
/// PlayerFallingDamage`); INFRA defines its own `Player.FallLight` and
/// `Player.FallMedium`, which the mod plays on a rough and a hurting landing.
const PLAYER_SCRIPTS: &[&str] = &["Player.FallDamage", "Player.FallLight", "Player.FallMedium"];

const SF_AMBIENT_EVERYWHERE: u32 = 1;

/// `EmitSound` defaults for a sound file played without a soundscript.
const FILE_VOLUME: f64 = 1.0;
const FILE_PITCH: f64 = 100.0;
const FILE_SOUND_LEVEL: f64 = SNDLVL_NORM;

/// Brush and prop entities whose keyvalues name the sounds they make.
const SOUND_ENTITY_CLASSES: &[&str] = &[
    "func_button",
    "infra_button",
    "func_rot_button",
    "momentary_rot_button",
    "func_door",
    "func_door_rotating",
    "func_movelinear",
    "prop_door_rotating",
];
/// The keyvalues on those that name a soundscript or a sound file.
const SOUND_KEYS: &[&str] = &[
    "noise1",
    "noise2",
    "startclosesound",
    "closesound",
    "locked_sound",
    "unlocked_sound",
    "soundopenoverride",
    "soundcloseoverride",
    "soundmoveoverride",
    "soundlockedoverride",
    "soundunlockedoverride",
];
/// Buttons whose `sounds` number picks `Buttons.snd<n>` (`MakeButtonSound`).
const BUTTON_CLASSES: &[&str] = &["func_button", "func_rot_button", "momentary_rot_button"];

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct AudioTable {
    pub format: &'static str,
    pub version: u32,
    pub sounds: Vec<SoundAsset>,
    pub soundscapes: Vec<Soundscape>,
    pub emitters: Vec<Emitter>,
    pub ambients: Vec<Ambient>,
    pub scripts: Vec<Script>,
    pub surfaces: Vec<Surface>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct SoundAsset {
    /// Payload at `audio/<content_id>.ogg`.
    pub content_id: String,
    /// The Source path, relative to `sound/`. Diagnostic provenance only.
    pub source: String,
    pub channels: u8,
    pub sample_rate: u32,
    pub frames: u64,
    /// Frame the loop restarts at; absent for a sound that plays once.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub loop_start: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Soundscape {
    pub name: String,
    pub loops: Vec<Loop>,
    pub randoms: Vec<Random>,
    pub children: Vec<Child>,
}

/// `playlooping`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Loop {
    pub sound: u32,
    pub volume: [f64; 2],
    pub pitch: [f64; 2],
    pub sound_level: [f64; 2],
    /// Local position index; absent for a loop heard everywhere.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position: Option<u32>,
}

/// `playrandom`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Random {
    pub sounds: Vec<u32>,
    pub time: [f64; 2],
    pub volume: [f64; 2],
    pub pitch: [f64; 2],
    pub sound_level: [f64; 2],
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position: Option<u32>,
    /// `position random`: a point around the listener, new each time.
    pub random_position: bool,
}

/// `playsoundscape`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Child {
    pub soundscape: u32,
    pub volume: [f64; 2],
    pub position: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position_override: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ambient_position_override: Option<u32>,
}

/// One `env_soundscape`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Emitter {
    /// Index in the entity lump.
    pub entity: u32,
    pub position: [f64; 3],
    /// In blocks; -1 for no limit.
    pub radius: f64,
    pub soundscape: u32,
    /// Exactly eight; `null` where the entity names no position or one that
    /// does not exist.
    pub positions: Vec<Option<[f64; 3]>>,
    /// `StartDisabled`: the map's logic enables it.
    pub start_disabled: bool,
}

/// One `ambient_generic`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Ambient {
    /// Index in the entity lump.
    pub entity: u32,
    pub position: [f64; 3],
    pub sounds: Vec<u32>,
    pub volume: [f64; 2],
    pub pitch: [f64; 2],
    /// 0 plays everywhere at full volume.
    pub sound_level: [f64; 2],
    /// The entity's spawnflags: whether it starts silent (16) or plays once
    /// (32) decides whether it plays from the start.
    pub flags: u32,
}

/// One soundscript entry.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Script {
    pub name: String,
    pub sounds: Vec<u32>,
    pub volume: [f64; 2],
    pub pitch: [f64; 2],
    pub sound_level: [f64; 2],
}

/// The scripts a surface property plays, by index into `scripts`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Surface {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub step_left: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub step_right: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub impact_soft: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub impact_hard: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub break_sound: Option<u32>,
}

impl AudioTable {
    pub fn encode(&self) -> Result<Vec<u8>> {
        bundle::canonical_json(self)
    }

    pub fn is_empty(&self) -> bool {
        self.emitters.is_empty()
            && self.ambients.is_empty()
            && self.scripts.is_empty()
            && self.surfaces.is_empty()
    }
}

/// Everything audio contributes to a map export.
pub struct AudioExport {
    pub table: AudioTable,
    /// Encoded Ogg Vorbis payloads, by content ID.
    pub assets: Vec<(String, Vec<u8>)>,
    pub diagnostics: Vec<Diagnostic>,
}

/// Build the map's audio. `surface_props` are the `$surfaceprop` names its
/// materials and models use; `default` is always added. `scene_scripts` are
/// the soundscripts its scenes speak.
pub fn build(
    map: &crate::bsp::Map,
    vfs: &Vfs,
    entities: &[EntityRecord],
    surface_props: &BTreeSet<String>,
    scene_scripts: &BTreeSet<String>,
) -> Result<AudioExport> {
    let pak = Some(&map.bsp.pack);
    let sources = Sources {
        scripts: SoundScripts::load(vfs, pak, &map.name),
        soundscapes: Soundscapes::load(vfs, pak, &map.name),
        surfaces: SurfaceProperties::load(vfs, pak),
    };
    let mut builder = Builder::new(&sources);
    builder.add_entities(entities)?;
    builder.add_surfaces(surface_props)?;
    let mut named: BTreeMap<String, &str> = BTreeMap::new();
    for name in scene_scripts {
        named.entry(name.clone()).or_insert("scene");
    }
    for (name, origin) in entity_sounds(entities) {
        named.entry(name).or_insert(origin);
    }
    builder.add_named(&named)?;
    let Builder {
        waves,
        soundscapes,
        emitters,
        ambients,
        scripts,
        surfaces,
        mut diagnostics,
        ..
    } = builder;

    // Decoding and Vorbis encoding are most of the cost, and every sound is
    // independent of the others.
    use rayon::prelude::*;
    let encoded: Vec<std::result::Result<(SoundAsset, Vec<u8>), String>> = waves
        .par_iter()
        .map(|wave| encode(vfs, pak, wave))
        .collect();
    let mut remap = Vec::with_capacity(encoded.len());
    let mut sounds = Vec::new();
    let mut assets = Vec::new();
    for (wave, result) in waves.iter().zip(encoded) {
        match result {
            Ok((asset, bytes)) => {
                remap.push(Some(sounds.len() as u32));
                assets.push((asset.content_id.clone(), bytes));
                sounds.push(asset);
            }
            Err(reason) => {
                remap.push(None);
                diagnostics.push(diagnostic(
                    Severity::Warning,
                    "AUDIO_SOUND_UNAVAILABLE",
                    "a referenced sound could not be read or decoded; whatever plays it is left silent",
                    [("sound", wave.path.clone()), ("reason", reason)],
                ));
            }
        }
    }
    let table = finish(
        &remap,
        sounds,
        soundscapes,
        emitters,
        ambients,
        scripts,
        surfaces,
    );
    diagnostics.push(diagnostic(
        Severity::Info,
        "AUDIO_SUMMARY",
        "sound exported for the mod",
        [
            ("sounds", table.sounds.len().to_string()),
            ("soundscapes", table.soundscapes.len().to_string()),
            ("soundscape_entities", table.emitters.len().to_string()),
            ("ambient_sounds", table.ambients.len().to_string()),
            ("scripts", table.scripts.len().to_string()),
            ("surfaces", table.surfaces.len().to_string()),
        ],
    ));
    Ok(AudioExport {
        table,
        assets,
        diagnostics,
    })
}

struct Sources {
    scripts: SoundScripts,
    soundscapes: Soundscapes,
    surfaces: SurfaceProperties,
}

/// Collects the table with provisional sound IDs: one per distinct wave
/// reference, in first-use order. `finish` renumbers them once every sound
/// has been decoded, dropping the ones that could not be.
struct Builder<'a> {
    sources: &'a Sources,
    waves: Vec<WaveRef>,
    wave_ids: HashMap<WaveRef, u32>,
    soundscape_ids: HashMap<String, u32>,
    soundscapes: Vec<Soundscape>,
    emitters: Vec<Emitter>,
    ambients: Vec<Ambient>,
    script_ids: HashMap<String, u32>,
    scripts: Vec<Script>,
    surfaces: Vec<Surface>,
    diagnostics: Vec<Diagnostic>,
}

impl<'a> Builder<'a> {
    fn new(sources: &'a Sources) -> Self {
        Builder {
            sources,
            waves: Vec::new(),
            wave_ids: HashMap::new(),
            soundscape_ids: HashMap::new(),
            soundscapes: Vec::new(),
            emitters: Vec::new(),
            ambients: Vec::new(),
            script_ids: HashMap::new(),
            scripts: Vec::new(),
            surfaces: Vec::new(),
            diagnostics: Vec::new(),
        }
    }

    fn wave(&mut self, wave: WaveRef) -> Option<u32> {
        if wave.path.is_empty() {
            return None;
        }
        if let Some(id) = self.wave_ids.get(&wave) {
            return Some(*id);
        }
        let id = self.waves.len() as u32;
        self.wave_ids.insert(wave.clone(), id);
        self.waves.push(wave);
        Some(id)
    }

    /// A wave name, or a soundscript name standing for its waves.
    fn waves_of(&mut self, raw: &str) -> Vec<u32> {
        if WaveRef::is_file(raw) {
            return self.wave(WaveRef::parse(raw)).into_iter().collect();
        }
        let Some(script) = self.sources.scripts.get(raw.trim()) else {
            return Vec::new();
        };
        let waves = script.waves.clone();
        waves
            .iter()
            .filter_map(|w| self.wave(WaveRef::parse(w)))
            .collect()
    }

    fn add_entities(&mut self, entities: &[EntityRecord]) -> Result<()> {
        let mut by_name: HashMap<String, &EntityRecord> = HashMap::new();
        for entity in entities {
            if let Some(name) = &entity.targetname {
                // `FindEntityByName` returns the first.
                by_name.entry(name.to_ascii_lowercase()).or_insert(entity);
            }
        }
        let origin_of = |name: &str| -> Option<[f64; 3]> {
            by_name
                .get(&name.to_ascii_lowercase())
                .and_then(|e| e.origin_mc)
        };
        let mut skipped: BTreeMap<&str, usize> = BTreeMap::new();
        for entity in entities {
            let property = |key: &str| entity.get(key);
            match entity.classname.to_ascii_lowercase().as_str() {
                "env_soundscape" => {
                    let (Some(origin), Some(name)) = (entity.origin_mc, property("soundscape"))
                    else {
                        continue;
                    };
                    let Some(soundscape) = self.soundscape(name, 0) else {
                        self.diagnostics.push(diagnostic(
                            Severity::Warning,
                            "AUDIO_SOUNDSCAPE_UNKNOWN",
                            "an env_soundscape names a soundscape no script defines",
                            [("soundscape", name.to_string())],
                        ));
                        continue;
                    };
                    let radius = property("radius").map_or(0.0, leading_float);
                    let positions = (0..LOCAL_POSITIONS)
                        .map(|i| {
                            property(&format!("position{i}"))
                                .filter(|n| !n.is_empty())
                                .and_then(origin_of)
                                .map(canonical3)
                                .transpose()
                        })
                        .collect::<Result<Vec<_>>>()?;
                    self.emitters.push(Emitter {
                        entity: entity.index as u32,
                        position: canonical3(origin)?,
                        radius: if radius == -1.0 {
                            -1.0
                        } else {
                            // Any other negative radius is never in range.
                            canonical(radius.max(0.0) / UNITS_PER_BLOCK)?
                        },
                        soundscape,
                        positions,
                        start_disabled: property("StartDisabled").is_some_and(|v| v.trim() == "1"),
                    });
                }
                "env_soundscape_triggerable" | "env_soundscape_proxy" => {
                    *skipped
                        .entry("soundscape_triggerable_or_proxy")
                        .or_default() += 1;
                }
                "ambient_generic" => {
                    let flags = property("spawnflags").map_or(0, |v| leading_float(v) as u32);
                    if let Some(ambient) = self.ambient(entity, flags, &origin_of)? {
                        self.ambients.push(ambient);
                    }
                }
                _ => {}
            }
        }
        if !skipped.is_empty() {
            self.diagnostics.push(diagnostic(
                Severity::Info,
                "AUDIO_ENTITIES_NOT_EXPORTED",
                "soundscape entities that select another entity's soundscape; not exported",
                skipped
                    .into_iter()
                    .map(|(k, v)| (k.to_string(), v.to_string())),
            ));
        }
        Ok(())
    }

    /// `CAmbientGeneric`: a wave plays at the entity's own volume (`health`
    /// tenths), pitch and the sound level its radius implies; a soundscript
    /// name plays with the script's own values, as `EmitAmbientSound` only
    /// overrides them for a later volume or pitch change.
    fn ambient(
        &mut self,
        entity: &EntityRecord,
        flags: u32,
        origin_of: &dyn Fn(&str) -> Option<[f64; 3]>,
    ) -> Result<Option<Ambient>> {
        let property = |key: &str| entity.get(key);
        let Some(message) = property("message").filter(|m| !m.trim().is_empty()) else {
            return Ok(None);
        };
        let position = property("SourceEntityName")
            .filter(|n| !n.is_empty())
            .and_then(origin_of)
            .or(entity.origin_mc);
        let Some(position) = position else {
            return Ok(None);
        };
        let sounds = self.waves_of(message);
        if sounds.is_empty() {
            self.diagnostics.push(diagnostic(
                Severity::Warning,
                "AUDIO_AMBIENT_UNRESOLVED",
                "an ambient_generic names neither a sound file nor a known soundscript",
                [("message", message.to_string())],
            ));
            return Ok(None);
        }
        let (volume, pitch, sound_level) = match self.sources.scripts.get(message.trim()) {
            Some(script) if !WaveRef::is_file(message) => {
                (script.volume, script.pitch, script.sound_level)
            }
            _ => {
                let health = property("health").map_or(0.0, leading_float);
                let pitch = property("pitch").map_or(0.0, leading_float);
                let radius = property("radius").map_or(0.0, leading_float);
                (
                    Interval::fixed((health * 10.0).round().clamp(0.0, 100.0) / 100.0),
                    Interval::fixed(if pitch == 0.0 { 100.0 } else { pitch }),
                    Interval::fixed(ambient_sound_level(
                        radius,
                        flags & SF_AMBIENT_EVERYWHERE != 0,
                    )),
                )
            }
        };
        Ok(Some(Ambient {
            entity: entity.index as u32,
            position: canonical3(position)?,
            sounds,
            volume: bounds(volume)?,
            pitch: bounds(pitch)?,
            sound_level: bounds(sound_level)?,
            flags,
        }))
    }

    /// The table index of a soundscape, adding it and everything it plays.
    fn soundscape(&mut self, name: &str, depth: usize) -> Option<u32> {
        let key = name.trim().to_ascii_lowercase();
        if let Some(id) = self.soundscape_ids.get(&key) {
            return Some(*id);
        }
        if depth > MAX_SOUNDSCAPE_DEPTH {
            return None;
        }
        let sources = self.sources;
        let (found, block) = sources.soundscapes.get(name.trim())?;
        let id = self.soundscapes.len() as u32;
        self.soundscape_ids.insert(key, id);
        self.soundscapes.push(Soundscape {
            name: found.to_string(),
            loops: Vec::new(),
            randoms: Vec::new(),
            children: Vec::new(),
        });
        let mut loops = Vec::new();
        let mut randoms = Vec::new();
        let mut children = Vec::new();
        for (command, value) in block {
            let Value::Block(body) = value else { continue };
            match command.to_ascii_lowercase().as_str() {
                "playlooping" => loops.extend(self.play_looping(body)),
                "playrandom" => randoms.extend(self.play_random(body)),
                "playsoundscape" => children.extend(self.play_soundscape(body, depth)),
                _ => {}
            }
        }
        let soundscape = &mut self.soundscapes[id as usize];
        soundscape.loops = loops;
        soundscape.randoms = randoms;
        soundscape.children = children;
        Some(id)
    }

    /// `ProcessPlayLooping`: volume defaults to 0, which never plays; sound
    /// level to `ATTN_NORM`'s.
    fn play_looping(&mut self, body: &Block) -> Option<Loop> {
        let mut volume = Interval::fixed(0.0);
        let mut pitch = Interval::fixed(100.0);
        let mut sound_level = Interval::fixed(SNDLVL_NORM);
        let mut position = None;
        let mut sound = None;
        for (key, value) in body {
            let Value::Text(value) = value else { continue };
            match key.to_ascii_lowercase().as_str() {
                "volume" => volume = read_volume(value),
                "pitch" => pitch = read_pitch(value),
                "wave" => sound = self.waves_of(value).first().copied(),
                "position" => position = position_index(value),
                "attenuation" => sound_level = read_attenuation(value),
                "soundlevel" => sound_level = read_sound_level(value),
                _ => {}
            }
        }
        Some(Loop {
            sound: sound?,
            volume: bounds(volume).ok()?,
            pitch: bounds(pitch).ok()?,
            sound_level: bounds(sound_level).ok()?,
            position,
        })
    }

    /// `ProcessPlayRandom`. Source starts every field at zero; a zero pitch
    /// is written as normal pitch here, which is what an unset pitch means
    /// everywhere else in Source.
    fn play_random(&mut self, body: &Block) -> Option<Random> {
        let mut random = Random {
            sounds: Vec::new(),
            time: [0.0; 2],
            volume: [0.0; 2],
            pitch: [100.0; 2],
            sound_level: [0.0; 2],
            position: None,
            random_position: false,
        };
        for (key, value) in body {
            match (key.to_ascii_lowercase().as_str(), value) {
                ("volume", Value::Text(v)) => random.volume = bounds(read_volume(v)).ok()?,
                ("pitch", Value::Text(v)) => random.pitch = bounds(read_pitch(v)).ok()?,
                ("attenuation", Value::Text(v)) => {
                    random.sound_level = bounds(read_attenuation(v)).ok()?
                }
                ("soundlevel", Value::Text(v)) => {
                    random.sound_level = bounds(read_sound_level(v)).ok()?
                }
                ("time", Value::Text(v)) => random.time = bounds(read_interval(v, &[])).ok()?,
                ("rndwave", Value::Block(waves)) => {
                    for (k, v) in waves {
                        if let (true, Value::Text(v)) = (k.eq_ignore_ascii_case("wave"), v) {
                            let ids = self.waves_of(v);
                            random.sounds.extend(ids);
                        }
                    }
                }
                ("position", Value::Text(v)) if v.trim().eq_ignore_ascii_case("random") => {
                    random.random_position = true
                }
                ("position", Value::Text(v)) => random.position = position_index(v),
                _ => {}
            }
        }
        (!random.sounds.is_empty()).then_some(random)
    }

    /// `ProcessPlaySoundscape`. Sub-soundscapes never set DSP, which is not
    /// exported anyway.
    fn play_soundscape(&mut self, body: &Block, depth: usize) -> Option<Child> {
        let mut volume = Interval::fixed(1.0);
        let mut position = 0;
        let mut position_override = None;
        let mut ambient_position_override = None;
        let mut name = None;
        for (key, value) in body {
            let Value::Text(value) = value else { continue };
            match key.to_ascii_lowercase().as_str() {
                "volume" => volume = read_volume(value),
                "position" => position = position_index(value).unwrap_or(0),
                "positionoverride" => position_override = position_index(value),
                "ambientpositionoverride" => ambient_position_override = position_index(value),
                "name" => name = Some(value.clone()),
                _ => {}
            }
        }
        Some(Child {
            soundscape: self.soundscape(&name?, depth + 1)?,
            volume: bounds(volume).ok()?,
            position,
            position_override,
            ambient_position_override,
        })
    }

    fn add_surfaces(&mut self, surface_props: &BTreeSet<String>) -> Result<()> {
        let sources = self.sources;
        let mut names: BTreeSet<String> = surface_props
            .iter()
            .map(|n| n.trim().to_ascii_lowercase())
            .filter(|n| !n.is_empty())
            .collect();
        names.insert("default".into());
        for name in names {
            let Some(sounds) = sources.surfaces.get(&name) else {
                continue;
            };
            let surface = Surface {
                step_left: self.script(sounds.step_left.as_deref())?,
                step_right: self.script(sounds.step_right.as_deref())?,
                impact_soft: self.script(sounds.impact_soft.as_deref())?,
                impact_hard: self.script(sounds.impact_hard.as_deref())?,
                break_sound: self.script(sounds.break_sound.as_deref())?,
                name,
            };
            self.surfaces.push(surface);
        }
        for name in PLAYER_SCRIPTS {
            self.script(Some(name))?;
        }
        Ok(())
    }

    fn script(&mut self, name: Option<&str>) -> Result<Option<u32>> {
        let Some(name) = name.map(str::trim).filter(|n| !n.is_empty()) else {
            return Ok(None);
        };
        let key = name.to_ascii_lowercase();
        if let Some(id) = self.script_ids.get(&key) {
            return Ok(Some(*id));
        }
        let Some(script) = self.sources.scripts.get(name) else {
            return Ok(None);
        };
        let script = script.clone();
        let sounds: Vec<u32> = script
            .waves
            .iter()
            .filter_map(|w| self.wave(WaveRef::parse(w)))
            .collect();
        if sounds.is_empty() {
            return Ok(None);
        }
        let id = self.scripts.len() as u32;
        self.scripts.push(Script {
            name: key.clone(),
            sounds,
            volume: bounds(script.volume)?,
            pitch: bounds(script.pitch)?,
            sound_level: bounds(script.sound_level)?,
        });
        self.script_ids.insert(key, id);
        Ok(Some(id))
    }

    /// Scripts the map's logic plays by name, each with where it was named.
    /// A sound file gets a script of its own, keyed by its path, with the
    /// values `EmitSound` plays a bare file at.
    fn add_named(&mut self, names: &BTreeMap<String, &str>) -> Result<()> {
        for (name, origin) in names {
            if WaveRef::is_file(name) {
                let wave = WaveRef::parse(name);
                let key = wave.path.clone();
                if self.script_ids.contains_key(&key) {
                    continue;
                }
                let Some(sound) = self.wave(wave) else {
                    continue;
                };
                let id = self.scripts.len() as u32;
                self.scripts.push(Script {
                    name: key.clone(),
                    sounds: vec![sound],
                    volume: [FILE_VOLUME; 2],
                    pitch: [FILE_PITCH; 2],
                    sound_level: [FILE_SOUND_LEVEL; 2],
                });
                self.script_ids.insert(key, id);
            } else if self.script(Some(name))?.is_none() {
                self.diagnostics.push(diagnostic(
                    Severity::Warning,
                    "AUDIO_SCRIPT_UNRESOLVED",
                    "the map's logic names a soundscript that is not defined or plays no sound",
                    [("script", name.clone()), ("named_by", origin.to_string())],
                ));
            }
        }
        Ok(())
    }
}

/// The soundscripts and sound files entities name in their keyvalues:
/// `infra_music`'s `sound`, and the sounds buttons and doors make. Names are
/// trimmed; empty values and `0` name nothing.
fn entity_sounds(entities: &[EntityRecord]) -> Vec<(String, &'static str)> {
    let mut out = Vec::new();
    let mut push = |value: &str, origin: &'static str| {
        let value = value.trim();
        if !value.is_empty() && value != "0" {
            out.push((value.to_string(), origin));
        }
    };
    for entity in entities {
        let class = entity.classname.to_ascii_lowercase();
        if class == "infra_music" {
            if let Some(sound) = entity.get("sound") {
                push(sound, "infra_music");
            }
            continue;
        }
        let Some(&class) = SOUND_ENTITY_CLASSES.iter().find(|c| **c == class) else {
            continue;
        };
        for key in SOUND_KEYS {
            if let Some(value) = entity.get(key) {
                push(value, class);
            }
        }
        if BUTTON_CLASSES.contains(&class)
            && let Some(sounds) = entity.get("sounds")
        {
            let n = leading_float(sounds);
            if (1.0..1000.0).contains(&n) {
                push(&format!("Buttons.snd{}", n as u32), class);
            }
        }
    }
    out
}

/// Renumber provisional sound IDs to the sounds that decoded, dropping
/// whatever is left without a sound.
fn finish(
    remap: &[Option<u32>],
    sounds: Vec<SoundAsset>,
    mut soundscapes: Vec<Soundscape>,
    emitters: Vec<Emitter>,
    mut ambients: Vec<Ambient>,
    mut scripts: Vec<Script>,
    mut surfaces: Vec<Surface>,
) -> AudioTable {
    let map = |id: u32| remap[id as usize];
    let map_all = |ids: &mut Vec<u32>| *ids = ids.iter().filter_map(|&id| map(id)).collect();
    for soundscape in &mut soundscapes {
        soundscape.loops.retain_mut(|l| match map(l.sound) {
            Some(id) => {
                l.sound = id;
                true
            }
            None => false,
        });
        for random in &mut soundscape.randoms {
            map_all(&mut random.sounds);
        }
        soundscape.randoms.retain(|r| !r.sounds.is_empty());
    }
    for ambient in &mut ambients {
        map_all(&mut ambient.sounds);
    }
    ambients.retain(|a| !a.sounds.is_empty());
    // Scripts left empty are removed and the surfaces' references renumbered.
    let mut script_remap = Vec::with_capacity(scripts.len());
    let mut kept = 0u32;
    for script in &mut scripts {
        map_all(&mut script.sounds);
        script_remap.push((!script.sounds.is_empty()).then(|| {
            kept += 1;
            kept - 1
        }));
    }
    scripts.retain(|s| !s.sounds.is_empty());
    for surface in &mut surfaces {
        for slot in [
            &mut surface.step_left,
            &mut surface.step_right,
            &mut surface.impact_soft,
            &mut surface.impact_hard,
            &mut surface.break_sound,
        ] {
            *slot = slot.and_then(|id| script_remap[id as usize]);
        }
    }
    AudioTable {
        format: FORMAT,
        version: 2,
        sounds,
        soundscapes,
        emitters,
        ambients,
        scripts,
        surfaces,
    }
}

/// Read, decode and encode one sound. Positional stereo (`)` or `(`) and
/// anything past stereo is mixed down to mono; plain stereo stays stereo,
/// which Source plays unpanned.
fn encode(
    vfs: &Vfs,
    pak: Option<&vbsp::Packfile>,
    wave: &WaveRef,
) -> std::result::Result<(SoundAsset, Vec<u8>), String> {
    if !wave.path.ends_with(".wav") {
        return Err("only WAV sounds are supported".into());
    }
    let bytes = crate::source::sound::read_sound(vfs, pak, &wave.path)
        .ok_or_else(|| "not found".to_string())?;
    let mut decoded = crate::source::wav::decode(&bytes).map_err(|e| format!("{e:#}"))?;
    if decoded.channels.len() > 2 || (wave.spatial_stereo && decoded.channels.len() > 1) {
        decoded = decoded.downmixed();
    }
    let ogg = crate::source::wav::encode_ogg(&decoded).map_err(|e| format!("{e:#}"))?;
    Ok((
        SoundAsset {
            content_id: bundle::content_id(&ogg),
            source: wave.path.clone(),
            channels: decoded.channels.len() as u8,
            sample_rate: decoded.sample_rate,
            frames: decoded.frames(),
            loop_start: decoded.loop_start,
        },
        ogg,
    ))
}

/// `ComputeSoundlevel` in `sound.cpp`: 40 dB at the radius, falling 6 dB per
/// doubling from a 36-unit reference; 0 (everywhere) without a radius.
pub fn ambient_sound_level(radius: f64, everywhere: bool) -> f64 {
    if radius > 0.0 && !everywhere {
        (40.0 + 20.0 * (radius / 36.0).log10()).trunc()
    } else {
        0.0
    }
}

fn position_index(text: &str) -> Option<u32> {
    let value = leading_float(text);
    (value >= 0.0).then_some(value as u32)
}

fn leading_float(text: &str) -> f64 {
    read_interval(text, &[]).start
}

fn canonical(value: f64) -> Result<f64> {
    bundle::canonical_f64(value).context("non-finite audio value")
}

fn canonical3(value: [f64; 3]) -> Result<[f64; 3]> {
    Ok([
        canonical(value[0])?,
        canonical(value[1])?,
        canonical(value[2])?,
    ])
}

fn bounds(interval: Interval) -> Result<[f64; 2]> {
    let [low, high] = interval.bounds();
    Ok([canonical(low)?, canonical(high)?])
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
    use crate::source::sound::{SoundScripts, Soundscapes, SurfaceProperties};

    fn sources() -> Sources {
        Sources {
            scripts: SoundScripts::from_text(
                r#""Concrete.StepLeft" { "volume" "0.5" "rndwave" { "wave" ")player/c1.wav" "wave" ")player/c2.wav" } }
                   "Concrete.StepRight" { "wave" ")player/c3.wav" }
                   "Machine.Hum" { "volume" "0.7" "soundlevel" "SNDLVL_80dB" "wave" "ambient/hum.wav" }
                   "Player.FallDamage" { "wave" "player/pl_fallpain1.wav" }"#,
            ),
            soundscapes: Soundscapes::from_text(
                r#""hall" {
                       "dsp" "1"
                       "playlooping" { "volume" "0.5" "wave" "ambient\bed.wav" }
                       "playlooping" { "wave" "ambient/silent_by_default.wav" }
                       "playrandom" { "time" "40, 55" "volume" "0.1,.2" "position" "random"
                                      "soundlevel" "SNDLVL_90dB" "rndwave" { "wave" "a/1.wav" "wave" "a/2.wav" } }
                       "playsoundscape" { "name" "machines" "volume" "0.5" "position" "2" }
                   }
                   "machines" { "playlooping" { "volume" "1" "position" "0" "wave" "m/loop.wav" } }"#,
            ),
            surfaces: SurfaceProperties::from_text(
                r#""default" { "stepleft" "Concrete.StepLeft" "stepright" "Concrete.StepRight" }
                   "metal" { "base" "default" "impacthard" "No.Such.Script" }"#,
            ),
        }
    }

    fn entity(classname: &str, origin: [f64; 3], props: &[(&str, &str)]) -> EntityRecord {
        let config = crate::config::Config::default();
        let transform =
            crate::voxel::transform::Transform::new(&config, crate::geom::Aabb::empty());
        let mut keyvalues = vec![("classname".to_string(), classname.to_string())];
        keyvalues.extend(props.iter().map(|(k, v)| (k.to_string(), v.to_string())));
        let mut record = crate::bsp::entities::record(0, keyvalues, &transform);
        record.origin_mc = Some(origin);
        record
    }

    /// Entities numbered by their position, as in the lump.
    fn numbered(mut entities: Vec<EntityRecord>) -> Vec<EntityRecord> {
        for (index, entity) in entities.iter_mut().enumerate() {
            entity.index = index;
        }
        entities
    }

    #[test]
    fn soundscapes_resolve_recursively_with_their_commands() {
        let sources = sources();
        let mut builder = Builder::new(&sources);
        builder
            .add_entities(&numbered(vec![
                entity("info_target", [1.0, 2.0, 3.0], &[("targetname", "spot")]),
                entity(
                    "env_soundscape",
                    [10.0, 0.0, 0.0],
                    &[
                        ("soundscape", "HALL"),
                        ("radius", "320"),
                        ("position2", "spot"),
                    ],
                ),
                entity(
                    "env_soundscape",
                    [0.0; 3],
                    &[
                        ("soundscape", "hall"),
                        ("radius", "-1"),
                        ("startdisabled", "1"),
                    ],
                ),
            ]))
            .unwrap();
        assert_eq!(builder.emitters.len(), 2, "start-disabled ones are kept");
        assert_eq!(
            builder
                .emitters
                .iter()
                .map(|e| (e.entity, e.start_disabled))
                .collect::<Vec<_>>(),
            [(1, false), (2, true)]
        );
        let emitter = &builder.emitters[0];
        assert_eq!(emitter.radius, 10.0);
        assert_eq!(emitter.positions[2], Some([1.0, 2.0, 3.0]));
        assert_eq!(emitter.positions[0], None);
        let hall = &builder.soundscapes[emitter.soundscape as usize];
        assert_eq!(hall.name, "hall");
        assert_eq!(hall.loops.len(), 2);
        assert_eq!(hall.loops[0].volume, [0.5, 0.5]);
        assert_eq!(
            hall.loops[1].volume,
            [0.0, 0.0],
            "playlooping volume defaults to 0"
        );
        assert_eq!(hall.loops[0].sound_level, [75.0, 75.0]);
        let random = &hall.randoms[0];
        assert!(random.random_position);
        assert_eq!(random.time, [40.0, 55.0]);
        assert_eq!(random.sound_level, [90.0, 90.0]);
        assert_eq!(random.sounds.len(), 2);
        let child = &hall.children[0];
        assert_eq!((child.volume, child.position), ([0.5, 0.5], 2));
        let machines = &builder.soundscapes[child.soundscape as usize];
        assert_eq!(machines.loops[0].position, Some(0));
        assert_eq!(builder.waves[0].path, "ambient/bed.wav");
    }

    #[test]
    fn every_ambient_generic_is_exported_with_its_flags() {
        let sources = sources();
        let mut builder = Builder::new(&sources);
        builder
            .add_entities(&numbered(vec![
                entity(
                    "ambient_generic",
                    [0.0; 3],
                    &[
                        ("message", "machinery/fan.wav"),
                        ("health", "7"),
                        ("radius", "1250"),
                        ("spawnflags", "0"),
                    ],
                ),
                entity(
                    "ambient_generic",
                    [0.0; 3],
                    &[("message", "x.wav"), ("health", "10"), ("spawnflags", "48")],
                ),
                entity(
                    "ambient_generic",
                    [0.0; 3],
                    &[("message", "y.wav"), ("health", "10"), ("spawnflags", "32")],
                ),
                entity(
                    "ambient_generic",
                    [5.0; 3],
                    &[
                        ("message", "Machine.Hum"),
                        ("health", "2"),
                        ("spawnflags", "1"),
                    ],
                ),
            ]))
            .unwrap();
        let flags: Vec<_> = builder
            .ambients
            .iter()
            .map(|a| (a.entity, a.flags))
            .collect();
        assert_eq!(flags, [(0, 0), (1, 48), (2, 32), (3, 1)]);
        let fan = &builder.ambients[0];
        assert_eq!(fan.volume, [0.7, 0.7]);
        assert_eq!(fan.pitch, [100.0, 100.0]);
        // 40 + 20 log10(1250 / 36) = 70.8
        assert_eq!(fan.sound_level, [70.0, 70.0]);
        let hum = &builder.ambients[3];
        assert_eq!(
            hum.volume,
            [0.7, 0.7],
            "a soundscript plays with its own values"
        );
        assert_eq!(hum.sound_level, [80.0, 80.0]);
    }

    #[test]
    fn surfaces_fall_back_to_default_and_drop_unknown_scripts() {
        let sources = sources();
        let mut builder = Builder::new(&sources);
        builder
            .add_surfaces(&["Metal".to_string(), "unknown".to_string()].into())
            .unwrap();
        let names: Vec<_> = builder.surfaces.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names, ["default", "metal", "unknown"]);
        let metal = &builder.surfaces[1];
        assert_eq!(metal.impact_hard, None);
        assert_eq!(metal.step_left, builder.surfaces[0].step_left);
        let left = &builder.scripts[metal.step_left.unwrap() as usize];
        assert_eq!(left.sounds.len(), 2);
        assert!(builder.waves[left.sounds[0] as usize].spatial_stereo);
        assert!(
            builder
                .scripts
                .iter()
                .any(|s| s.name == "player.falldamage")
        );
    }

    #[test]
    fn finish_drops_whatever_lost_its_sounds() {
        let sources = sources();
        let mut builder = Builder::new(&sources);
        builder
            .add_entities(&[entity(
                "env_soundscape",
                [0.0; 3],
                &[("soundscape", "hall"), ("radius", "-1")],
            )])
            .unwrap();
        builder.add_surfaces(&BTreeSet::new()).unwrap();
        // Every sound but the first random wave fails to decode.
        let random_wave = builder.wave_ids[&WaveRef::parse("a/1.wav")];
        let remap: Vec<Option<u32>> = (0..builder.waves.len() as u32)
            .map(|id| (id == random_wave).then_some(0))
            .collect();
        let table = finish(
            &remap,
            Vec::new(),
            builder.soundscapes,
            builder.emitters,
            builder.ambients,
            builder.scripts,
            builder.surfaces,
        );
        let hall = &table.soundscapes[0];
        assert!(hall.loops.is_empty());
        assert_eq!(hall.randoms[0].sounds, [0]);
        assert!(table.scripts.is_empty());
        assert_eq!(table.surfaces[0].step_left, None);
        assert_eq!(table.emitters[0].radius, -1.0);
    }

    #[test]
    fn scripts_named_by_logic_resolve_by_name_or_file() {
        let sources = sources();
        let mut builder = Builder::new(&sources);
        let entities = numbered(vec![
            entity("infra_music", [0.0; 3], &[("Sound", "Machine.Hum")]),
            entity(
                "func_button",
                [0.0; 3],
                &[
                    ("sounds", "3"),
                    ("locked_sound", "0"),
                    ("noise1", ")Buttons\\Lever_002.wav"),
                ],
            ),
            entity("func_door", [0.0; 3], &[("noise2", "No.Such.Door")]),
            entity("func_wall", [0.0; 3], &[("noise1", "ignored.wav")]),
        ]);
        let mut named: BTreeMap<String, &str> = BTreeMap::new();
        named.insert("Player.FallDamage".into(), "scene");
        named.extend(entity_sounds(&entities));
        builder.add_named(&named).unwrap();
        let names: Vec<_> = builder.scripts.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(
            names,
            ["buttons/lever_002.wav", "machine.hum", "player.falldamage"]
        );
        let file = &builder.scripts[0];
        assert_eq!(
            (file.volume, file.pitch, file.sound_level),
            ([1.0; 2], [100.0; 2], [75.0; 2])
        );
        assert!(builder.waves[file.sounds[0] as usize].spatial_stereo);
        let unresolved: Vec<_> = builder
            .diagnostics
            .iter()
            .filter(|d| d.code == "AUDIO_SCRIPT_UNRESOLVED")
            .map(|d| d.context["script"].as_str())
            .collect();
        assert_eq!(unresolved, ["Buttons.snd3", "No.Such.Door"]);
    }

    #[test]
    fn ambient_sound_level_matches_compute_soundlevel() {
        assert_eq!(ambient_sound_level(36.0, false), 40.0);
        assert_eq!(ambient_sound_level(360.0, false), 60.0);
        assert_eq!(ambient_sound_level(360.0, true), 0.0);
        assert_eq!(ambient_sound_level(0.0, false), 0.0);
    }
}
