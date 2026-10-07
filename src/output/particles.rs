//! The map's particle systems (format section 23): the `.pcf` definitions the
//! map's entities and impacts start, with every parameter spelled out, and the
//! materials they draw with.
//!
//! A `.pcf` stores only the parameters an artist changed; the rest take the
//! defaults compiled into Source's particle library. Those defaults are in
//! `pcf_defaults.json`, read out of the Source SDK 2013's `particles.a`
//! unpack tables, so the table written here is complete and the mod never
//! needs to know a default of a function it finds in the file.

use crate::output::bundle;
use crate::output::metadata::{Diagnostic, Severity};
use crate::source::pcf::{self, Definition, Value};
use crate::source::vfs::{Vfs, read_file};
use anyhow::Result;
use serde::Serialize;
use serde_json::{Map as JsonMap, Value as Json};
use std::collections::{BTreeMap, BTreeSet};

/// Materials Source's own effect code draws with, whatever the map's
/// particle files name: debris flecks, dust puffs, sparks and flares of the
/// SDK's `c_impact_effects.cpp`, `fx_sparks.cpp` and `fx.cpp`. Exported when
/// the game has them, for impacts and the legacy effect entities.
pub const CODE_MATERIALS: &[&str] = &[
    "effects/fleck_cement1",
    "effects/fleck_cement2",
    "effects/fleck_wood1",
    "effects/fleck_wood2",
    "effects/fleck_glass1",
    "effects/fleck_glass2",
    "effects/fleck_tile1",
    "effects/fleck_tile2",
    "effects/blood",
    "particle/particle_smokegrenade",
    "particle/particle_noisesphere",
    "effects/spark",
    "effects/yellowflare",
    "effects/yellowflare_noz",
    "sprites/rico1",
];

/// `s_pImpactEffect` of the SDK's `fx_impact.cpp`: the particle system of each
/// game material (`CHAR_TEX_*`, as a letter) with `cl_new_impact_effects`.
const IMPACT_SYSTEMS: &[(char, &str)] = &[
    ('A', "impact_antlion"),
    ('C', "impact_concrete"),
    ('D', "impact_dirt"),
    ('M', "impact_metal"),
    ('N', "impact_dirt"),
    ('P', "impact_computer"),
    ('T', "impact_concrete"),
    ('V', "impact_metal"),
    ('W', "impact_wood"),
    ('Y', "impact_glass"),
];

/// The first BSP version whose games draw impacts with particle systems.
/// Portal (20) has `impact_fx.pcf` but keeps `cl_new_impact_effects` at 0,
/// so draws the code effects; Portal 2 (21) has no public client code and its
/// impact systems are taken to be used as the SDK's new path uses them.
const SYSTEM_IMPACTS_FROM_VERSION: i32 = 21;

#[derive(Debug, Clone, Serialize)]
pub struct ParticleTable {
    pub format: &'static str,
    pub version: u32,
    pub systems: Vec<System>,
    pub materials: Vec<Material>,
    pub impacts: Impacts,
    /// Bullet hole decals by game material letter (`scripts/decals_subrect.txt`).
    pub decals: BTreeMap<String, Vec<Decal>>,
}

/// One decal a game material may get, picked by weight.
#[derive(Debug, Clone, Serialize)]
pub struct Decal {
    /// Into [`ParticleTable::materials`].
    pub material: u32,
    /// The decal's part of the texture, `[u0, v0, u1, v1]` as fractions.
    pub rect: [f32; 4],
    /// Width and height in Source units: texels times `$decalscale`.
    pub size: [f32; 2],
    pub weight: f32,
}

impl ParticleTable {
    pub fn encode(&self) -> Result<Vec<u8>> {
        bundle::canonical_json(self)
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct System {
    pub name: String,
    /// Into [`ParticleTable::materials`]; absent when the material could not
    /// be read.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub material: Option<u32>,
    /// Every `DmeParticleSystemDefinition` attribute, defaults filled in.
    pub attributes: JsonMap<String, Json>,
    pub renderers: Vec<Function>,
    pub operators: Vec<Function>,
    pub initializers: Vec<Function>,
    pub emitters: Vec<Function>,
    pub forces: Vec<Function>,
    pub constraints: Vec<Function>,
    pub children: Vec<Child>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Function {
    pub function: String,
    /// Every parameter, defaults filled in when the function is one the
    /// defaults table knows.
    pub parameters: JsonMap<String, Json>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Child {
    /// Into [`ParticleTable::systems`].
    pub system: u32,
    pub delay: f32,
}

#[derive(Debug, Clone, Serialize)]
pub struct Material {
    /// Normalized: lowercase, forward slashes, no `materials/` or `.vmt`.
    pub name: String,
    /// Into the map's material table.
    pub material: u32,
    /// Lowercase shader name: `spritecard`, `sprite`, `unlitgeneric`, ...
    pub shader: String,
    /// `$additive`.
    pub additive: bool,
    /// Every numeric `$` parameter the material sets, by lowercase name.
    pub parameters: BTreeMap<String, f64>,
    /// The texture's sprite sheet by sequence number; empty for none.
    pub sheet: Vec<Option<Sequence>>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Sequence {
    pub clamp: bool,
    /// Per frame: seconds, then rectangles of `[u0, v0, u1, v1]`.
    pub frames: Vec<(f32, Vec<[f32; 4]>)>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Impacts {
    /// `systems`: the game draws a bullet impact with the particle system of
    /// its surface's game material; `code`: with the SDK's code effects.
    pub style: &'static str,
    /// Game material letter to system index, for `systems`.
    pub systems: BTreeMap<String, u32>,
    /// Material name to index into [`ParticleTable::materials`], for the code
    /// effects.
    pub materials: BTreeMap<String, u32>,
}

/// What the export needs before the material table is built: the systems,
/// the materials they and the code effects draw with, by normalized name.
pub struct Collected {
    definitions: Vec<Definition>,
    /// Game material letter to the decal materials it picks from, with their weights.
    decals: BTreeMap<char, Vec<(String, f32)>>,
    impact_systems: BTreeMap<char, usize>,
    system_impacts: bool,
    code_materials: bool,
    pub materials: BTreeSet<String>,
    pub diagnostics: Vec<Diagnostic>,
}

/// The `.pcf` files of the map, manifest order, map's own manifest last: a
/// later definition of a name replaces an earlier one, as the map's own
/// particles override the game's.
fn definitions_by_name(
    vfs: &Vfs,
    pak: Option<&vbsp::Packfile>,
    map_stem: &str,
    diagnostics: &mut Vec<Diagnostic>,
) -> BTreeMap<String, Definition> {
    let mut files = Vec::new();
    if let Some(text) = read_file(vfs, pak, "particles/particles_manifest.txt") {
        files.extend(pcf::manifest_files(&String::from_utf8_lossy(&text)));
    }
    if let Some(text) = read_file(vfs, pak, &format!("maps/{map_stem}_particles.txt")) {
        for file in pcf::manifest_files(&String::from_utf8_lossy(&text)) {
            files.retain(|f| f != &file);
            files.push(file);
        }
    }
    let mut by_name = BTreeMap::new();
    for file in files {
        let Some(data) = read_file(vfs, pak, &file) else {
            diagnostics.push(diagnostic(
                Severity::Warning,
                "PARTICLE_FILE_MISSING",
                "a particle file the manifest lists is missing",
                &[("file", &file)],
            ));
            continue;
        };
        match pcf::definitions(&data) {
            Ok(list) => {
                for definition in list {
                    by_name.insert(definition.name.to_ascii_lowercase(), definition);
                }
            }
            Err(error) => diagnostics.push(diagnostic(
                Severity::Warning,
                "PARTICLE_FILE_UNREADABLE",
                "a particle file could not be read",
                &[("file", &file), ("error", &format!("{error:#}"))],
            )),
        }
    }
    by_name
}

/// The systems the map's `info_particle_system` entities start, with their
/// children, and the game's impact systems.
pub fn collect(map: &crate::bsp::Map, vfs: &Vfs) -> Collected {
    let pak = Some(&map.bsp.pack);
    let mut diagnostics = Vec::new();
    let stem = map
        .path
        .file_stem()
        .map(|s| s.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default();
    let by_name = definitions_by_name(vfs, pak, &stem, &mut diagnostics);

    let mut wanted: Vec<String> = Vec::new();
    let mut sparks = false;
    for raw in map.bsp.entities.iter() {
        let mut class = None;
        let mut effect = None;
        for (key, value) in raw.properties() {
            if key.eq_ignore_ascii_case("classname") {
                class = Some(value.to_ascii_lowercase());
            } else if key.eq_ignore_ascii_case("effect_name") {
                effect = Some(value.to_ascii_lowercase());
            }
        }
        sparks |= class.as_deref() == Some("env_spark");
        if class.as_deref() == Some("info_particle_system")
            && let Some(effect) = effect.filter(|e| !e.is_empty())
        {
            wanted.push(effect);
        }
    }
    let system_impacts = map.version >= SYSTEM_IMPACTS_FROM_VERSION
        && IMPACT_SYSTEMS
            .iter()
            .any(|(_, name)| by_name.contains_key(*name));
    if system_impacts {
        wanted.extend(IMPACT_SYSTEMS.iter().map(|(_, name)| name.to_string()));
    }

    // Every wanted system and its children, each once, in first-seen order.
    let mut order: Vec<String> = Vec::new();
    let mut index: BTreeMap<String, usize> = BTreeMap::new();
    let mut stack: Vec<String> = wanted.into_iter().rev().collect();
    let mut missing = BTreeSet::new();
    while let Some(name) = stack.pop() {
        if index.contains_key(&name) {
            continue;
        }
        let Some(definition) = by_name.get(&name) else {
            missing.insert(name);
            continue;
        };
        index.insert(name.clone(), order.len());
        order.push(name);
        for child in definition.children.iter().rev() {
            stack.push(child.name.to_ascii_lowercase());
        }
    }
    for name in &missing {
        // Impact names a game lacks are expected.
        if IMPACT_SYSTEMS.iter().any(|(_, n)| n == name) {
            continue;
        }
        diagnostics.push(diagnostic(
            Severity::Warning,
            "PARTICLE_SYSTEM_MISSING",
            "the map starts a particle system no particle file defines",
            &[("system", name)],
        ));
    }
    let definitions: Vec<Definition> = order.iter().map(|n| by_name[n].clone()).collect();
    let impact_systems = if system_impacts {
        IMPACT_SYSTEMS
            .iter()
            .filter_map(|(letter, name)| Some((*letter, *index.get(*name)?)))
            .collect()
    } else {
        BTreeMap::new()
    };
    let mut materials: BTreeSet<String> = definitions
        .iter()
        .filter_map(|d| match d.attributes.get("material") {
            Some(Value::String(m)) => Some(normalize_material(m)),
            _ => None,
        })
        .filter(|m| !m.is_empty())
        .collect();
    // The code effects' materials: for impacts before Portal 2, and for
    // `env_spark`, which every game draws with `FX_ElectricSpark`.
    let code_materials = !system_impacts || sparks;
    if code_materials {
        materials.extend(CODE_MATERIALS.iter().map(|m| m.to_string()));
    }
    let decals = impact_decals(vfs, pak);
    let resolver = crate::source::vmt::Materials::new(vfs, pak);
    for name in decals.values().flatten().map(|(n, _)| n) {
        if let Some(base) = decal_base(&resolver, name) {
            materials.insert(base.0);
        }
    }
    Collected {
        decals,
        definitions,
        impact_systems,
        system_impacts,
        code_materials,
        materials,
        diagnostics,
    }
}

/// A material name as a definition writes it, made a material table name.
pub fn normalize_material(name: &str) -> String {
    let lower = name.trim().to_ascii_lowercase().replace('\\', "/");
    let lower = lower.strip_prefix("materials/").unwrap_or(&lower);
    lower.strip_suffix(".vmt").unwrap_or(lower).to_string()
}

/// The table, once the map's material table gives the particle materials
/// their IDs (`material_ids`, by normalized name; a material without one had
/// no readable texture and is left out). `None` when there is nothing to
/// draw.
pub fn finish(
    collected: Collected,
    material_ids: &BTreeMap<String, u32>,
    resolver: &crate::source::vmt::Materials,
    decoder: &crate::source::vtf::Textures,
) -> (Option<ParticleTable>, Vec<Diagnostic>) {
    let Collected {
        decals: decal_lists,
        definitions,
        impact_systems,
        system_impacts,
        code_materials,
        materials: _,
        mut diagnostics,
    } = collected;
    let mut materials: Vec<Material> = Vec::new();
    let mut material_index: BTreeMap<String, u32> = BTreeMap::new();
    let mut material_of = |name: &str, materials: &mut Vec<Material>| -> Option<u32> {
        if let Some(&i) = material_index.get(name) {
            return Some(i);
        }
        let &id = material_ids.get(name)?;
        let vmt = resolver.vmt(name)?;
        let base = vmt
            .get("$basetexture")
            .map(|v| v.trim().trim_matches('"').replace('\\', "/"));
        let sheet = base
            .as_deref()
            .and_then(|b| decoder.sheet(b))
            .unwrap_or_default()
            .into_iter()
            .map(|s| {
                s.map(|s| Sequence {
                    clamp: s.clamp,
                    frames: s
                        .frames
                        .into_iter()
                        .map(|f| (f.duration, f.rects))
                        .collect(),
                })
            })
            .collect();
        let parameters = vmt
            .keys
            .iter()
            .filter(|(k, _)| k.starts_with('$'))
            .filter_map(|(k, v)| {
                let n: f64 = v.trim().trim_matches('"').parse().ok()?;
                n.is_finite().then(|| (k.clone(), n))
            })
            .collect::<BTreeMap<_, _>>();
        let i = materials.len() as u32;
        materials.push(Material {
            name: name.to_string(),
            material: id,
            shader: vmt.shader.clone(),
            additive: parameters.get("$additive").is_some_and(|v| *v != 0.0),
            parameters,
            sheet,
        });
        material_index.insert(name.to_string(), i);
        Some(i)
    };

    let index_of: BTreeMap<String, u32> = definitions
        .iter()
        .enumerate()
        .map(|(i, d)| (d.name.to_ascii_lowercase(), i as u32))
        .collect();
    let defaults = defaults();
    let mut systems = Vec::with_capacity(definitions.len());
    let mut unknown = BTreeSet::new();
    for definition in &definitions {
        let material = match definition.attributes.get("material") {
            Some(Value::String(m)) => material_of(&normalize_material(m), &mut materials),
            _ => None,
        };
        let mut attributes = defaults_of(defaults.get("system"));
        for (key, value) in &definition.attributes {
            if key != "material" {
                attributes.insert(key.clone(), json(value));
            }
        }
        let mut list = |functions: &[pcf::Function]| -> Vec<Function> {
            functions
                .iter()
                .map(|f| {
                    let known = defaults
                        .get("functions")
                        .and_then(|all| all.get(&f.name))
                        .and_then(|entry| entry.get("parameters"));
                    if known.is_none() {
                        unknown.insert(f.name.clone());
                    }
                    let mut parameters = defaults_of(defaults.get("operator"));
                    parameters.extend(defaults_of(known));
                    for (key, value) in &f.parameters {
                        parameters.insert(key.clone(), json(value));
                    }
                    Function {
                        function: f.name.clone(),
                        parameters,
                    }
                })
                .collect()
        };
        let system = System {
            name: definition.name.clone(),
            material,
            attributes,
            renderers: list(&definition.renderers),
            operators: list(&definition.operators),
            initializers: list(&definition.initializers),
            emitters: list(&definition.emitters),
            forces: list(&definition.forces),
            constraints: list(&definition.constraints),
            children: definition
                .children
                .iter()
                .filter_map(|c| {
                    Some(Child {
                        system: *index_of.get(&c.name.to_ascii_lowercase())?,
                        delay: finite(c.delay),
                    })
                })
                .collect(),
        };
        systems.push(system);
    }
    for name in unknown {
        diagnostics.push(diagnostic(
            Severity::Info,
            "PARTICLE_FUNCTION_DEFAULTS_UNKNOWN",
            "a particle function newer than the defaults table keeps only the parameters its file sets",
            &[("function", &name)],
        ));
    }
    let mut impact_materials = BTreeMap::new();
    if code_materials {
        for name in CODE_MATERIALS {
            if let Some(i) = material_of(name, &mut materials) {
                impact_materials.insert(name.to_string(), i);
            }
        }
    }
    let mut decals: BTreeMap<String, Vec<Decal>> = BTreeMap::new();
    for (letter, list) in decal_lists {
        let mut out = Vec::new();
        for (name, weight) in list {
            let Some((base, scale, rect)) = decal_base(resolver, &name) else {
                continue;
            };
            let Some(vmt) = resolver.vmt(&base) else {
                continue;
            };
            let Some(texture) = vmt
                .get("$basetexture")
                .map(|v| v.trim().trim_matches('"').replace('\\', "/"))
            else {
                continue;
            };
            let Some(header) = decoder.header_of(&texture) else {
                continue;
            };
            let [w, h] = header.size.map(|v| v as f32);
            let rect = rect.unwrap_or([0.0, 0.0, w, h]);
            let Some(index) = material_of(&base, &mut materials) else {
                continue;
            };
            out.push(Decal {
                material: index,
                rect: [
                    rect[0] / w,
                    rect[1] / h,
                    (rect[0] + rect[2]) / w,
                    (rect[1] + rect[3]) / h,
                ],
                size: [rect[2] * scale, rect[3] * scale],
                weight,
            });
        }
        if !out.is_empty() {
            decals.insert(letter.to_string(), out);
        }
    }
    let impacts = Impacts {
        style: if system_impacts { "systems" } else { "code" },
        systems: impact_systems
            .into_iter()
            .map(|(letter, i)| (letter.to_string(), i as u32))
            .collect(),
        materials: impact_materials,
    };
    if systems.is_empty() && impacts.materials.is_empty() && decals.is_empty() {
        return (None, diagnostics);
    }
    (
        Some(ParticleTable {
            format: "src2mc-particles",
            version: 1,
            systems,
            materials,
            impacts,
            decals,
        }),
        diagnostics,
    )
}

/// The bullet decals of each game material: `scripts/decals_subrect.txt` (or `decals.txt`), its
/// `TranslationData` naming a decal group per letter, each group its materials and weights, as
/// the SDK's `CDecalEmitterSystem::LoadDecalsFromScript` reads it. Only `Impact.*` groups.
fn impact_decals(vfs: &Vfs, pak: Option<&vbsp::Packfile>) -> BTreeMap<char, Vec<(String, f32)>> {
    use crate::source::keyvalues::{self, Value};
    let Some(bytes) = read_file(vfs, pak, "scripts/decals_subrect.txt")
        .or_else(|| read_file(vfs, pak, "scripts/decals.txt"))
    else {
        return BTreeMap::new();
    };
    let root = keyvalues::parse(&String::from_utf8_lossy(&bytes));
    let mut groups: BTreeMap<String, Vec<(String, f32)>> = BTreeMap::new();
    let mut translation: Vec<(String, String)> = Vec::new();
    for (name, value) in &root {
        let Value::Block(block) = value else { continue };
        if name.eq_ignore_ascii_case("TranslationData") {
            for (letter, target) in block {
                if let Value::Text(target) = target {
                    translation.push((letter.clone(), target.clone()));
                }
            }
        } else {
            let list = block
                .iter()
                .filter_map(|(material, weight)| match weight {
                    Value::Text(w) => Some((
                        normalize_material(material),
                        w.trim().parse::<f32>().unwrap_or(1.0).max(0.0),
                    )),
                    Value::Block(_) => None,
                })
                .collect();
            groups.entry(name.to_ascii_lowercase()).or_insert(list);
        }
    }
    let mut out = BTreeMap::new();
    for (letter, target) in translation {
        let mut chars = letter.chars();
        let (Some(c), None) = (chars.next(), chars.next()) else {
            continue;
        };
        if !target.to_ascii_lowercase().starts_with("impact.") {
            continue;
        }
        if let Some(list) = groups.get(&target.to_ascii_lowercase()) {
            out.entry(c.to_ascii_uppercase())
                .or_insert_with(|| list.clone());
        }
    }
    out
}

/// A decal material's texture material, its `$decalscale`, and for a `Subrect` material its
/// rectangle on that texture in texels (`$Pos`, `$Size`).
fn decal_base(
    resolver: &crate::source::vmt::Materials,
    name: &str,
) -> Option<(String, f32, Option<[f32; 4]>)> {
    let vmt = resolver.vmt(name)?;
    let scale = vmt
        .get("$decalscale")
        .and_then(|v| v.trim().trim_matches('"').parse::<f32>().ok())
        .unwrap_or(1.0);
    if vmt.shader == "subrect" {
        let base = normalize_material(vmt.get("$material")?.trim().trim_matches('"'));
        let pair = |key: &str| -> Option<[f32; 2]> {
            let v: Vec<f32> = vmt
                .get(key)?
                .trim_matches('"')
                .split_whitespace()
                .filter_map(|n| n.parse().ok())
                .collect();
            (v.len() == 2).then(|| [v[0], v[1]])
        };
        let (pos, size) = (pair("$pos")?, pair("$size")?);
        return Some((base, scale, Some([pos[0], pos[1], size[0], size[1]])));
    }
    Some((name.to_string(), scale, None))
}

fn defaults() -> Json {
    serde_json::from_str(include_str!("../source/pcf_defaults.json"))
        .expect("pcf_defaults.json is valid JSON")
}

fn defaults_of(table: Option<&Json>) -> JsonMap<String, Json> {
    table
        .and_then(Json::as_object)
        .map(|o| {
            o.iter()
                .filter_map(|(k, v)| Some((k.clone(), parse_default(v.as_str()?))))
                .collect()
        })
        .unwrap_or_default()
}

/// A default as the library writes it: numbers separated by spaces (a `4.0f`
/// as C wrote it too), or a string.
fn parse_default(text: &str) -> Json {
    let numbers: Option<Vec<f64>> = text
        .split_whitespace()
        .map(|w| {
            w.trim_end_matches('f')
                .parse::<f64>()
                .ok()
                .filter(|n| n.is_finite())
        })
        .collect();
    match numbers {
        Some(n) if n.len() == 1 => number(n[0]),
        Some(n) if !n.is_empty() => Json::Array(n.into_iter().map(number).collect()),
        _ => Json::String(text.to_string()),
    }
}

fn number(n: f64) -> Json {
    serde_json::Number::from_f64(n).map_or(Json::from(0), Json::Number)
}

fn finite(v: f32) -> f32 {
    if v.is_finite() { v } else { 0.0 }
}

fn json(value: &Value) -> Json {
    let f = |v: f32| number(f64::from(finite(v)));
    match value {
        Value::Element(_) | Value::Binary(_) => Json::Null,
        Value::Int(i) => Json::from(*i),
        Value::Float(v) | Value::Time(v) => f(*v),
        Value::Bool(b) => Json::Bool(*b),
        Value::String(s) => Json::String(s.clone()),
        Value::Color(c) => Json::Array(c.iter().map(|&b| Json::from(b)).collect()),
        Value::Vector2(v) => Json::Array(v.iter().map(|&x| f(x)).collect()),
        Value::Vector3(v) | Value::QAngle(v) => Json::Array(v.iter().map(|&x| f(x)).collect()),
        Value::Vector4(v) | Value::Quaternion(v) => Json::Array(v.iter().map(|&x| f(x)).collect()),
        Value::Matrix(v) => Json::Array(v.iter().map(|&x| f(x)).collect()),
        Value::Array(items) => Json::Array(items.iter().map(json).collect()),
    }
}

fn diagnostic(
    severity: Severity,
    code: &str,
    message: &str,
    context: &[(&str, &str)],
) -> Diagnostic {
    Diagnostic {
        severity,
        code: code.to_string(),
        message: message.to_string(),
        context: context
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_parse_as_the_library_writes_them() {
        assert_eq!(parse_default("0.5"), serde_json::json!(0.5));
        assert_eq!(parse_default("4.0f"), serde_json::json!(4.0));
        assert_eq!(
            parse_default("255 255 255 255"),
            serde_json::json!([255.0, 255.0, 255.0, 255.0])
        );
        assert_eq!(parse_default("NONE"), serde_json::json!("NONE"));
        assert_eq!(parse_default(""), serde_json::json!(""));
    }

    #[test]
    fn every_function_of_the_defaults_table_has_parameters() {
        let table = defaults();
        let functions = table["functions"].as_object().unwrap();
        assert!(functions.len() > 80);
        assert_eq!(
            table["functions"]["Radius Random"]["parameters"]["radius_min"],
            "1"
        );
        assert_eq!(table["system"]["max_particles"], "1000");
    }

    #[test]
    fn material_names_are_normalized() {
        assert_eq!(
            normalize_material("particle\\Fire\\Fire_01.vmt"),
            "particle/fire/fire_01"
        );
        assert_eq!(
            normalize_material("materials/effects/spark"),
            "effects/spark"
        );
    }
}
