//! Source's sound scripts: soundscripts (`game_sounds_*.txt`), soundscapes
//! (`soundscapes_*.txt`) and the sound half of surface properties
//! (`surfaceproperties*.txt`).
//!
//! What each key means follows the public Source SDK 2013 code that reads
//! them: `c_soundscape.cpp` for soundscapes, `soundflags.h` for sound levels.

use crate::source::keyvalues::{self, Block, Value};
use crate::source::vfs::Vfs;
use std::collections::HashMap;

/// A value written as `a` or `a, b`, picked uniformly from `[start, start +
/// range]` each time it is used.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Interval {
    pub start: f64,
    pub range: f64,
}

impl Interval {
    pub const fn fixed(value: f64) -> Interval {
        Interval {
            start: value,
            range: 0.0,
        }
    }

    pub fn bounds(&self) -> [f64; 2] {
        [self.start, self.start + self.range]
    }
}

/// `ReadInterval`: one or two comma-separated values, each optionally one of
/// `names`. Anything unreadable is zero, as `atof` would make it.
pub fn read_interval(text: &str, names: &[(&str, f64)]) -> Interval {
    let value = |part: &str| {
        let part = part.trim();
        names
            .iter()
            .find(|(name, _)| part.eq_ignore_ascii_case(name))
            .map(|(_, v)| *v)
            .unwrap_or_else(|| leading_number(part))
    };
    match text.split_once(',') {
        Some((low, high)) => {
            let start = value(low);
            Interval {
                start,
                range: value(high) - start,
            }
        }
        None => Interval::fixed(value(text)),
    }
}

/// The number `atof` reads off the front of `text`.
fn leading_number(text: &str) -> f64 {
    let end = text
        .char_indices()
        .take_while(|&(i, c)| {
            c.is_ascii_digit() || c == '.' || (i == 0 && (c == '-' || c == '+'))
        })
        .last()
        .map_or(0, |(i, c)| i + c.len_utf8());
    text[..end].parse().unwrap_or(0.0)
}

pub const SNDLVL_NORM: f64 = 75.0;

/// `ATTN_TO_SNDLVL` from `soundflags.h`.
pub fn attenuation_to_sound_level(attenuation: f64) -> f64 {
    if attenuation == 0.0 {
        0.0
    } else {
        (50.0 + 20.0 / attenuation).trunc()
    }
}

/// Sound-level names, `soundflags.h` and the soundscript parser's.
const SOUND_LEVEL_NAMES: &[(&str, f64)] = &[
    ("SNDLVL_NONE", 0.0),
    ("SNDLVL_NORM", 75.0),
    ("SNDLVL_IDLE", 60.0),
    ("SNDLVL_STATIC", 66.0),
    ("SNDLVL_TALKING", 80.0),
    ("SNDLVL_GUNFIRE", 140.0),
];

const ATTENUATION_NAMES: &[(&str, f64)] = &[
    ("ATTN_NONE", 0.0),
    ("ATTN_NORM", 0.8),
    ("ATTN_IDLE", 2.0),
    ("ATTN_STATIC", 1.25),
    ("ATTN_RICOCHET", 1.5),
    ("ATTN_GUNFIRE", 0.27),
];

/// A `soundlevel` value: `SNDLVL_*` names, including every `SNDLVL_<n>dB`, or
/// plain numbers, single or as an interval.
pub fn read_sound_level(text: &str) -> Interval {
    let named = |part: &str| -> Option<f64> {
        let part = part.trim();
        if let Some((_, v)) = SOUND_LEVEL_NAMES
            .iter()
            .find(|(n, _)| part.eq_ignore_ascii_case(n))
        {
            return Some(*v);
        }
        let upper = part.to_ascii_uppercase();
        upper
            .strip_prefix("SNDLVL_")
            .and_then(|rest| rest.strip_suffix("DB"))
            .and_then(|db| db.parse().ok())
    };
    let value = |part: &str| named(part).unwrap_or_else(|| leading_number(part.trim()));
    match text.split_once(',') {
        Some((low, high)) => {
            let start = value(low);
            Interval {
                start,
                range: value(high) - start,
            }
        }
        None => Interval::fixed(value(text)),
    }
}

/// An `attenuation` value, converted to the sound level it stands for.
pub fn read_attenuation(text: &str) -> Interval {
    let attenuation = read_interval(text, ATTENUATION_NAMES);
    let start = attenuation_to_sound_level(attenuation.start);
    Interval {
        start,
        range: attenuation_to_sound_level(attenuation.start + attenuation.range) - start,
    }
}

const VOLUME_NAMES: &[(&str, f64)] = &[("VOL_NORM", 1.0)];
const PITCH_NAMES: &[(&str, f64)] = &[
    ("PITCH_NORM", 100.0),
    ("PITCH_LOW", 95.0),
    ("PITCH_HIGH", 120.0),
];

pub fn read_volume(text: &str) -> Interval {
    read_interval(text, VOLUME_NAMES)
}

pub fn read_pitch(text: &str) -> Interval {
    read_interval(text, PITCH_NAMES)
}

/// Sound characters a wave name may start with (`soundchars.h`).
const SOUND_CHARS: &[char] = &[
    '*', '#', '@', '>', '<', '^', ')', '(', '}', '$', '!', '?', '&', '~', '`', '+', '%',
];

/// A wave reference split into its path and the sound characters before it.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct WaveRef {
    /// Lowercase, forward slashes, relative to `sound/`.
    pub path: String,
    /// `)` or `(`: a stereo file placed in the world as one point.
    pub spatial_stereo: bool,
}

impl WaveRef {
    pub fn parse(raw: &str) -> WaveRef {
        let prefix: String = raw.chars().take_while(|c| SOUND_CHARS.contains(c)).collect();
        WaveRef {
            path: raw[prefix.len()..]
                .trim()
                .to_ascii_lowercase()
                .replace('\\', "/"),
            spatial_stereo: prefix.contains(')') || prefix.contains('('),
        }
    }

    /// Whether this names a file rather than a soundscript entry.
    pub fn is_file(raw: &str) -> bool {
        let lower = raw.to_ascii_lowercase();
        lower.ends_with(".wav") || lower.ends_with(".mp3")
    }
}

/// One soundscript entry.
#[derive(Debug, Clone, PartialEq)]
pub struct SoundScript {
    pub volume: Interval,
    pub pitch: Interval,
    pub sound_level: Interval,
    pub waves: Vec<String>,
}

/// Every soundscript entry the game declares, by lowercase name.
#[derive(Debug, Default)]
pub struct SoundScripts {
    entries: HashMap<String, SoundScript>,
}

impl SoundScripts {
    /// The game's manifest, then the map's own `maps/<map>_level_sounds.txt`,
    /// whose entries replace the game's.
    pub fn load(vfs: &Vfs, pak: Option<&vbsp::Packfile>, map_name: &str) -> SoundScripts {
        let mut scripts = SoundScripts::default();
        let manifest = read(vfs, pak, "scripts/game_sounds_manifest.txt");
        for (_, value) in manifest.iter().flat_map(|(_, v)| block_of(v)) {
            if let Value::Text(file) = value {
                scripts.add_file(&read(vfs, pak, file), false);
            }
        }
        let level = format!("maps/{map_name}_level_sounds.txt");
        scripts.add_file(&read(vfs, pak, &level), true);
        scripts
    }

    fn add_file(&mut self, file: &Block, overrides: bool) {
        for (name, value) in file {
            let Value::Block(block) = value else { continue };
            let key = name.to_ascii_lowercase();
            if !overrides && self.entries.contains_key(&key) {
                continue;
            }
            self.entries.insert(key, parse_script(block));
        }
    }

    pub fn get(&self, name: &str) -> Option<&SoundScript> {
        self.entries.get(&name.to_ascii_lowercase())
    }

    #[cfg(test)]
    pub fn from_text(text: &str) -> SoundScripts {
        let mut scripts = SoundScripts::default();
        scripts.add_file(&keyvalues::parse(text), false);
        scripts
    }
}

fn parse_script(block: &Block) -> SoundScript {
    let mut script = SoundScript {
        volume: Interval::fixed(1.0),
        pitch: Interval::fixed(100.0),
        sound_level: Interval::fixed(SNDLVL_NORM),
        waves: Vec::new(),
    };
    for (key, value) in block {
        match (key.to_ascii_lowercase().as_str(), value) {
            ("volume", Value::Text(v)) => script.volume = read_volume(v),
            ("pitch", Value::Text(v)) => script.pitch = read_pitch(v),
            ("soundlevel", Value::Text(v)) => script.sound_level = read_sound_level(v),
            ("attenuation", Value::Text(v)) => script.sound_level = read_attenuation(v),
            ("wave", Value::Text(v)) => script.waves.push(v.clone()),
            ("rndwave", Value::Block(waves)) => {
                for (k, v) in waves {
                    if let (true, Value::Text(v)) = (k.eq_ignore_ascii_case("wave"), v) {
                        script.waves.push(v.clone());
                    }
                }
            }
            _ => {}
        }
    }
    script
}

/// Every soundscape the client would know on this map, in load order: the
/// manifest's files, then `scripts/soundscapes_<map>.txt` if the manifest
/// did not already name it. The first of a name wins, as
/// `FindSoundscapeByName` returns the first match.
#[derive(Debug, Default)]
pub struct Soundscapes {
    entries: Vec<(String, Block)>,
}

impl Soundscapes {
    pub fn load(vfs: &Vfs, pak: Option<&vbsp::Packfile>, map_name: &str) -> Soundscapes {
        let mut soundscapes = Soundscapes::default();
        let map_file = format!("scripts/soundscapes_{map_name}.txt");
        let mut map_file_loaded = false;
        let manifest = read(vfs, pak, "scripts/soundscapes_manifest.txt");
        for (key, value) in manifest.iter().flat_map(|(_, v)| block_of(v)) {
            if let (true, Value::Text(file)) = (key.eq_ignore_ascii_case("file"), value) {
                map_file_loaded |= file.eq_ignore_ascii_case(&map_file);
                soundscapes.add_file(read(vfs, pak, file));
            }
        }
        if !map_file_loaded {
            soundscapes.add_file(read(vfs, pak, &map_file));
        }
        soundscapes
    }

    fn add_file(&mut self, file: Block) {
        for (name, value) in file {
            // Only sections with contents are soundscapes.
            if let Value::Block(block) = value
                && !block.is_empty()
            {
                self.entries.push((name, block));
            }
        }
    }

    pub fn get(&self, name: &str) -> Option<(&str, &Block)> {
        self.entries
            .iter()
            .find(|(n, _)| n.eq_ignore_ascii_case(name))
            .map(|(n, b)| (n.as_str(), b))
    }

    #[cfg(test)]
    pub fn from_text(text: &str) -> Soundscapes {
        let mut soundscapes = Soundscapes::default();
        soundscapes.add_file(keyvalues::parse(text));
        soundscapes
    }
}

/// The soundscript names a surface property plays for each kind of contact.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SurfaceSounds {
    pub step_left: Option<String>,
    pub step_right: Option<String>,
    pub impact_soft: Option<String>,
    pub impact_hard: Option<String>,
    pub break_sound: Option<String>,
}

/// Surface properties by lowercase name, each already merged with its `base`.
#[derive(Debug, Default)]
pub struct SurfaceProperties {
    entries: HashMap<String, SurfaceSounds>,
}

impl SurfaceProperties {
    pub fn load(vfs: &Vfs, pak: Option<&vbsp::Packfile>) -> SurfaceProperties {
        let mut properties = SurfaceProperties::default();
        let manifest = read(vfs, pak, "scripts/surfaceproperties_manifest.txt");
        for (key, value) in manifest.iter().flat_map(|(_, v)| block_of(v)) {
            if let (true, Value::Text(file)) = (key.eq_ignore_ascii_case("file"), value) {
                properties.add_file(&read(vfs, pak, file));
            }
        }
        properties
    }

    /// A property starts as a copy of its `base`, which must already be
    /// defined, and its own keys override; the first definition of a name
    /// is the one kept.
    fn add_file(&mut self, file: &Block) {
        for (name, value) in file {
            let Value::Block(block) = value else { continue };
            let key = name.to_ascii_lowercase();
            if self.entries.contains_key(&key) {
                continue;
            }
            let mut sounds = keyvalues::text(block, "base")
                .and_then(|base| self.entries.get(&base.to_ascii_lowercase()))
                .cloned()
                .unwrap_or_default();
            for (k, v) in block {
                let Value::Text(v) = v else { continue };
                let slot = match k.to_ascii_lowercase().as_str() {
                    "stepleft" => &mut sounds.step_left,
                    "stepright" => &mut sounds.step_right,
                    "impactsoft" => &mut sounds.impact_soft,
                    "impacthard" => &mut sounds.impact_hard,
                    "break" => &mut sounds.break_sound,
                    _ => continue,
                };
                *slot = Some(v.clone());
            }
            self.entries.insert(key, sounds);
        }
    }

    /// A name Source does not know falls back to `default`, as an unknown
    /// `$surfaceprop` does in game.
    pub fn get(&self, name: &str) -> Option<&SurfaceSounds> {
        self.entries
            .get(&name.to_ascii_lowercase())
            .or_else(|| self.entries.get("default"))
    }

    #[cfg(test)]
    pub fn from_text(text: &str) -> SurfaceProperties {
        let mut properties = SurfaceProperties::default();
        properties.add_file(&keyvalues::parse(text));
        properties
    }
}

fn block_of(value: &Value) -> &[(String, Value)] {
    match value {
        Value::Block(block) => block,
        Value::Text(_) => &[],
    }
}

/// Read a script from the map's pakfile first, then the game, as Source's
/// `GAME` search path does. A missing file is an empty one.
fn read(vfs: &Vfs, pak: Option<&vbsp::Packfile>, path: &str) -> Block {
    let key = path.to_ascii_lowercase().replace('\\', "/");
    let bytes = pak
        .and_then(|pak| pak.get(&key).ok().flatten())
        .or_else(|| vfs.open(&key));
    bytes
        .map(|b| keyvalues::parse(&String::from_utf8_lossy(&b)))
        .unwrap_or_default()
}

/// Read a sound file the same way.
pub fn read_sound(vfs: &Vfs, pak: Option<&vbsp::Packfile>, path: &str) -> Option<Vec<u8>> {
    let key = format!("sound/{path}");
    pak.and_then(|pak| pak.get(&key).ok().flatten())
        .or_else(|| vfs.open(&key))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn intervals_read_one_or_two_values_with_names() {
        assert_eq!(read_volume("0.3, 1").bounds(), [0.3, 1.0]);
        assert_eq!(read_volume("VOL_NORM").bounds(), [1.0, 1.0]);
        assert_eq!(read_pitch("PITCH_NORM,110").bounds(), [100.0, 110.0]);
        assert_eq!(read_interval("40,55", &[]).bounds(), [40.0, 55.0]);
        assert_eq!(read_interval(".5", &[]).bounds(), [0.5, 0.5]);
    }

    #[test]
    fn sound_levels_read_names_db_names_numbers_and_attenuations() {
        assert_eq!(read_sound_level("SNDLVL_90dB").bounds(), [90.0, 90.0]);
        assert_eq!(read_sound_level("SNDLVL_NORM").bounds(), [75.0, 75.0]);
        assert_eq!(read_sound_level("70,80").bounds(), [70.0, 80.0]);
        // 50 + 20 / 0.8 = 75, the level `ATTN_NORM` stands for.
        assert_eq!(read_attenuation("ATTN_NORM").bounds(), [75.0, 75.0]);
        assert_eq!(read_attenuation("0").bounds(), [0.0, 0.0]);
    }

    #[test]
    fn wave_references_split_off_sound_characters() {
        let wave = WaveRef::parse(")Player\\Footsteps\\Concrete1.wav");
        assert_eq!(wave.path, "player/footsteps/concrete1.wav");
        assert!(wave.spatial_stereo);
        let plain = WaveRef::parse("*#music/a.mp3");
        assert_eq!(plain.path, "music/a.mp3");
        assert!(!plain.spatial_stereo);
        assert!(WaveRef::is_file("x/a.WAV"));
        assert!(!WaveRef::is_file("Concrete.StepLeft"));
    }

    #[test]
    fn soundscripts_default_to_normal_volume_pitch_and_level() {
        let scripts = SoundScripts::from_text(
            r#""Concrete.StepLeft" { "channel" "CHAN_BODY" "volume" "0.5" "soundlevel" "SNDLVL_NORM"
               "rndwave" { "wave" "player/a.wav" "wave" "player/b.wav" } }
               "Bare.One" { "wave" ")x/y.wav" }"#,
        );
        let step = scripts.get("concrete.stepleft").unwrap();
        assert_eq!(step.volume.bounds(), [0.5, 0.5]);
        assert_eq!(step.waves, ["player/a.wav", "player/b.wav"]);
        let bare = scripts.get("BARE.ONE").unwrap();
        assert_eq!(bare.pitch.bounds(), [100.0, 100.0]);
        assert_eq!(bare.sound_level.bounds(), [75.0, 75.0]);
    }

    #[test]
    fn surface_properties_inherit_their_base_and_unknown_names_fall_back() {
        let properties = SurfaceProperties::from_text(
            r#""default" { "stepleft" "Default.StepLeft" "stepright" "Default.StepRight" "break" "Default.Break" }
               "metal" { "base" "default" "stepleft" "Metal.StepLeft" }"#,
        );
        let metal = properties.get("Metal").unwrap();
        assert_eq!(metal.step_left.as_deref(), Some("Metal.StepLeft"));
        assert_eq!(metal.step_right.as_deref(), Some("Default.StepRight"));
        assert_eq!(metal.break_sound.as_deref(), Some("Default.Break"));
        assert_eq!(
            properties.get("no_such_surface").unwrap().step_left.as_deref(),
            Some("Default.StepLeft")
        );
    }

    #[test]
    fn the_first_soundscape_of_a_name_wins() {
        let soundscapes =
            Soundscapes::from_text(r#""A.b" { "dsp" "1" } "a.B" { "dsp" "2" } "empty" { }"#);
        let (name, block) = soundscapes.get("a.b").unwrap();
        assert_eq!(name, "A.b");
        assert_eq!(keyvalues::text(block, "dsp"), Some("1"));
        assert!(soundscapes.get("empty").is_none());
    }
}
