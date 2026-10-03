//! Extracting the entity lump into a structured, Minecraft-aware manifest.
//!
//! Nothing is discarded: every key/value pair is kept verbatim alongside the
//! derived fields, so logic that this tool does not translate can still be
//! rebuilt by hand from the manifest.

use crate::geom::Vec3;
use crate::voxel::transform::Transform;
use serde::Serialize;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Serialize)]
pub struct EntityRecord {
    /// Position in the entity lump.
    pub index: usize,
    pub classname: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub targetname: Option<String>,
    /// Value of the `model` key, e.g. `*12` or `models/props/foo.mdl`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    /// Model index for brush entities, parsed from a `*N` model value.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub brush_model: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin_source: Option<[f64; 3]>,
    /// `origin_source` mapped into Minecraft block space, fractional.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin_mc: Option<[f64; 3]>,
    /// Pitch/yaw/roll as authored.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub angles: Option<[f64; 3]>,
    /// Every pair in lump order, keys as written. Keys repeat (one entity
    /// lists an `OnTrigger` per output) and their case varies between maps,
    /// so look values up with [`EntityRecord::get`].
    pub keyvalues: Vec<(String, String)>,
}

/// One entity output: `OnTrigger` with `target,input,parameter,delay,times`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Output {
    /// The output's name as written.
    pub output: String,
    pub target: String,
    pub input: String,
    pub parameter: String,
    /// Seconds; finite and never negative.
    pub delay: f64,
    /// -1 for unlimited, otherwise at least 1.
    pub times: i32,
}

/// What a keyvalue is, read as Source's I/O system reads it.
#[derive(Debug, Clone, PartialEq)]
pub enum OutputParse {
    /// Not an output: a key not starting with `On`, or a value without a
    /// field separator.
    NotOutput,
    Output(Output),
    /// An `On` key with separators that do not make five usable fields.
    Malformed,
}

impl EntityRecord {
    /// The value of `key`, compared ignoring ASCII case. When the key repeats
    /// the last occurrence wins, as Source applies the pairs in order.
    pub fn get(&self, key: &str) -> Option<&str> {
        lookup(&self.keyvalues, key)
    }

    /// Every well-formed output, in lump order.
    pub fn outputs(&self) -> impl Iterator<Item = Output> + '_ {
        self.keyvalues
            .iter()
            .filter_map(|(k, v)| match parse_output(k, v) {
                OutputParse::Output(output) => Some(output),
                _ => None,
            })
    }

    /// Entities that name a target, for rebuilding the I/O graph.
    pub fn targets(&self) -> Vec<&str> {
        let mut out = Vec::new();
        for key in ["target", "filtername", "parentname"] {
            if let Some(value) = self.get(key) {
                out.push(value);
            }
        }
        // Outputs look like `OnTrigger` = `targetname,input,param,delay,times`.
        for (key, value) in &self.keyvalues {
            if let OutputParse::Output(_) = parse_output(key, value)
                && let Some((target, _)) = value.split_once(&[',', '\u{1b}'][..])
                && !target.is_empty()
            {
                out.push(target);
            }
        }
        out.sort_unstable();
        out.dedup();
        out
    }
}

/// Case-insensitive, last-wins lookup in ordered pairs.
fn lookup<'a>(pairs: &'a [(String, String)], key: &str) -> Option<&'a str> {
    pairs
        .iter()
        .rev()
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .map(|(_, v)| v.as_str())
}

/// Read a keyvalue as an output (`CEventAction`): the key starts with `On`
/// in any case and the value splits into exactly five fields on `0x1B`, or on
/// `,` in maps compiled before the escape character was used. The delay and
/// count are read like `atof` and `atoi`. A negative delay fires on the next
/// tick in Source, so it becomes 0; a count of 0 or below never reaches the
/// zero that removes an output, so it is unlimited.
pub fn parse_output(key: &str, value: &str) -> OutputParse {
    let starts_on = key
        .get(..2)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("on"));
    if !starts_on {
        return OutputParse::NotOutput;
    }
    let separator = if value.contains('\u{1b}') {
        '\u{1b}'
    } else if value.contains(',') {
        ','
    } else {
        return OutputParse::NotOutput;
    };
    let fields: Vec<&str> = value.split(separator).collect();
    let [target, input, parameter, delay, times] = fields[..] else {
        return OutputParse::Malformed;
    };
    let delay = leading_float(delay);
    if !delay.is_finite() {
        return OutputParse::Malformed;
    }
    let times = leading_float(times);
    let times = if times.is_finite() && times >= 1.0 {
        times.min(i32::MAX as f64) as i32
    } else {
        -1
    };
    OutputParse::Output(Output {
        output: key.to_string(),
        target: target.to_string(),
        input: input.to_string(),
        parameter: parameter.to_string(),
        delay: delay.max(0.0),
        times,
    })
}

/// `atof`: the longest leading number, 0 when there is none.
fn leading_float(text: &str) -> f64 {
    let text = text.trim_start();
    let end = text
        .char_indices()
        .take_while(|(_, c)| c.is_ascii_digit() || matches!(c, '+' | '-' | '.' | 'e' | 'E'))
        .map(|(i, c)| i + c.len_utf8())
        .last()
        .unwrap_or(0);
    (1..=end)
        .rev()
        .find_map(|n| text[..n].parse::<f64>().ok())
        .unwrap_or(0.0)
}

/// Parse a whitespace-separated triple such as an `origin` or `angles` value.
fn parse_triple(value: &str) -> Option<[f64; 3]> {
    let mut parts = value
        .split_whitespace()
        .filter_map(|p| p.parse::<f64>().ok());
    let (x, y, z) = (parts.next()?, parts.next()?, parts.next()?);
    parts.next().is_none().then_some([x, y, z])
}

/// Parse a brush-entity model reference (`*12`) into its model index.
fn parse_brush_model(value: &str) -> Option<usize> {
    value.strip_prefix('*')?.parse().ok()
}

/// Read every entity from the map, mapping positions through `transform`.
pub fn extract(map: &crate::bsp::Map, transform: &Transform) -> Vec<EntityRecord> {
    map.bsp
        .entities
        .iter()
        .enumerate()
        .map(|(index, raw)| {
            let keyvalues: Vec<(String, String)> = raw
                .properties()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect();
            record(index, keyvalues, transform)
        })
        .collect()
}

/// Derive an entity's fields from its pairs.
pub fn record(
    index: usize,
    keyvalues: Vec<(String, String)>,
    transform: &Transform,
) -> EntityRecord {
    let get = |key: &str| lookup(&keyvalues, key).map(str::to_string);
    let origin_source = get("origin").and_then(|v| parse_triple(&v));
    let model = get("model");
    EntityRecord {
        index,
        classname: get("classname").unwrap_or_else(|| "<unknown>".into()),
        targetname: get("targetname"),
        brush_model: model.as_deref().and_then(parse_brush_model),
        model,
        origin_source,
        origin_mc: origin_source.map(|o| {
            let p = transform.to_block_space(Vec3::new(o[0], o[1], o[2]));
            [p.x, p.y, p.z]
        }),
        angles: get("angles").and_then(|v| parse_triple(&v)),
        keyvalues,
    }
}

/// Count of each classname, most frequent first.
pub fn classname_histogram(entities: &[EntityRecord]) -> Vec<(String, usize)> {
    let mut counts: BTreeMap<&str, usize> = BTreeMap::new();
    for entity in entities {
        *counts.entry(entity.classname.as_str()).or_default() += 1;
    }
    let mut out: Vec<(String, usize)> = counts
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .collect();
    out.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_well_formed_triples() {
        assert_eq!(parse_triple("1 2 3"), Some([1.0, 2.0, 3.0]));
        assert_eq!(parse_triple("-64.5  0   1024"), Some([-64.5, 0.0, 1024.0]));
    }

    #[test]
    fn rejects_malformed_triples() {
        assert_eq!(parse_triple("1 2"), None);
        assert_eq!(parse_triple("1 2 3 4"), None);
        assert_eq!(parse_triple(""), None);
        assert_eq!(parse_triple("a b c"), None);
    }

    #[test]
    fn parses_brush_model_references() {
        assert_eq!(parse_brush_model("*12"), Some(12));
        assert_eq!(parse_brush_model("*0"), Some(0));
        assert_eq!(parse_brush_model("models/props/crate.mdl"), None);
        assert_eq!(parse_brush_model("*"), None);
    }

    fn entity(pairs: &[(&str, &str)]) -> EntityRecord {
        let config = crate::config::Config::default();
        let transform = Transform::new(&config, crate::geom::Aabb::empty());
        let pairs = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        record(0, pairs, &transform)
    }

    #[test]
    fn collects_targets_from_keys_and_outputs() {
        let entity = entity(&[
            ("classname", "trigger_once"),
            ("target", "door_a"),
            ("OnTrigger", "light_b,Toggle,,0,-1"),
        ]);
        assert_eq!(entity.targets(), vec!["door_a", "light_b"]);
    }

    #[test]
    fn keys_ignore_case_and_the_last_repeat_wins() {
        let entity = entity(&[
            ("classname", "env_soundscape"),
            ("StartDisabled", "0"),
            ("startdisabled", "1"),
        ]);
        assert_eq!(entity.get("STARTDISABLED"), Some("1"));
        assert_eq!(entity.get("missing"), None);
        assert_eq!(entity.keyvalues.len(), 3, "every pair is kept");
    }

    #[test]
    fn repeated_outputs_are_all_kept_in_order() {
        let entity = entity(&[
            ("classname", "trigger_once"),
            ("OnTrigger", "a\u{1b}Enable\u{1b}\u{1b}0\u{1b}-1"),
            ("ontrigger", "b,Disable,,1.5,1"),
            ("OnTrigger", "c\u{1b}FireUser1\u{1b}x,y\u{1b}0\u{1b}1"),
        ]);
        let outputs: Vec<Output> = entity.outputs().collect();
        assert_eq!(outputs.len(), 3);
        assert_eq!(outputs[1].output, "ontrigger");
        assert_eq!((outputs[1].target.as_str(), outputs[1].delay), ("b", 1.5));
        assert_eq!(outputs[2].parameter, "x,y", "commas are text under 0x1B");
    }

    #[test]
    fn outputs_split_on_either_separator() {
        let OutputParse::Output(output) =
            parse_output("OnPressed", "door\u{1b}Open\u{1b}\u{1b}0.25\u{1b}-1")
        else {
            panic!("expected an output")
        };
        assert_eq!(
            output,
            Output {
                output: "OnPressed".into(),
                target: "door".into(),
                input: "Open".into(),
                parameter: String::new(),
                delay: 0.25,
                times: -1,
            }
        );
        let OutputParse::Output(output) =
            parse_output("OnStartTouch", "!activator,SetHealth,50,-2,0")
        else {
            panic!("expected an output")
        };
        assert_eq!(output.parameter, "50");
        assert_eq!(output.delay, 0.0, "a negative delay fires at once");
        assert_eq!(output.times, -1, "a count of 0 never runs out");
    }

    #[test]
    fn malformed_outputs_and_plain_keys_are_told_apart() {
        assert_eq!(parse_output("OnTrigger", "a,b,c"), OutputParse::Malformed);
        assert_eq!(
            parse_output("OnTrigger", "a,b,c,d,e,f"),
            OutputParse::Malformed
        );
        assert_eq!(
            parse_output("OnTrigger", "a,b,c,1e999,1"),
            OutputParse::Malformed
        );
        assert_eq!(parse_output("OnTrigger", ""), OutputParse::NotOutput);
        assert_eq!(parse_output("target", "a,b,c,0,1"), OutputParse::NotOutput);
        assert_eq!(parse_output("O", "a,b,c,0,1"), OutputParse::NotOutput);
        let OutputParse::Output(output) = parse_output("OnUser1", "a,b,c,1.5junk,3x") else {
            panic!("expected an output")
        };
        assert_eq!(
            (output.delay, output.times),
            (1.5, 3),
            "read like atof and atoi"
        );
    }
}
