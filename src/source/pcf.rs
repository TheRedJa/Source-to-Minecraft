//! Reading Source's particle definitions: `.pcf` files, which are binary DMX.
//!
//! A DMX file is a string table, a list of element headers (type, name,
//! GUID), then each element's attributes. The binary encodings 2 to 5 differ
//! only in where strings come from: encoding 2 and 3 index a table of at most
//! 65,535 strings with `u16`, 4 counts the table with a `u32`, and from 5 the
//! indices are `u32` as well; before 4 an element's name and every string
//! value are written inline. Read as Artfunkel's `datamodel.py` (Blender
//! Source Tools) and noclip.website's `DMX.ts` read it.
//!
//! A `.pcf` has a root element listing its `DmeParticleSystemDefinition`s
//! under `particleSystemDefinitions`. Each definition holds its own
//! attributes (`max_particles`, `material`, ...) and arrays of
//! `DmeParticleOperator` elements -- renderers, operators, initializers,
//! emitters, forces, constraints -- each a `functionName` and its parameters,
//! plus `children`, `DmeParticleChild` elements naming another definition.

use anyhow::{Context, Result, bail, ensure};
use std::collections::BTreeMap;

/// One attribute value. Times are seconds.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    /// An element index into [`Dmx::elements`]; `None` for a null reference
    /// or one to an element in another file.
    Element(Option<usize>),
    Int(i32),
    Float(f32),
    Bool(bool),
    String(String),
    Binary(Vec<u8>),
    Time(f32),
    Color([u8; 4]),
    Vector2([f32; 2]),
    Vector3([f32; 3]),
    Vector4([f32; 4]),
    QAngle([f32; 3]),
    Quaternion([f32; 4]),
    Matrix([f32; 16]),
    Array(Vec<Value>),
}

#[derive(Debug, Clone)]
pub struct Element {
    pub kind: String,
    pub name: String,
    /// In file order.
    pub attributes: Vec<(String, Value)>,
}

impl Element {
    pub fn get(&self, name: &str) -> Option<&Value> {
        self.attributes
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value)
    }

    pub fn string(&self, name: &str) -> Option<&str> {
        match self.get(name)? {
            Value::String(s) => Some(s),
            _ => None,
        }
    }

    /// The element indices of an element-array attribute.
    pub fn elements(&self, name: &str) -> Vec<usize> {
        match self.get(name) {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|v| match v {
                    Value::Element(Some(i)) => Some(*i),
                    _ => None,
                })
                .collect(),
            _ => Vec::new(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Dmx {
    /// The `format` named in the header, `pcf` for particles.
    pub format: String,
    pub format_version: u32,
    pub elements: Vec<Element>,
}

/// Parses a binary DMX file, encodings 2 to 5.
pub fn parse(data: &[u8]) -> Result<Dmx> {
    let header_end = data
        .iter()
        .position(|&b| b == 0)
        .context("DMX header has no terminator")?;
    let header = std::str::from_utf8(&data[..header_end]).context("DMX header is not text")?;
    let words: Vec<&str> = header.split_whitespace().collect();
    // <!-- dmx encoding binary 2 format pcf 1 -->
    ensure!(
        words.len() >= 8 && words[0] == "<!--" && words[1] == "dmx" && words[2] == "encoding",
        "not a DMX header: {header:?}"
    );
    ensure!(
        words[3] == "binary",
        "DMX encoding {} is not binary",
        words[3]
    );
    let encoding: u32 = words[4].parse().context("DMX encoding version")?;
    ensure!(
        (2..=5).contains(&encoding),
        "DMX binary encoding {encoding} is not supported"
    );
    let format = words[6].to_string();
    let format_version: u32 = words[7].parse().unwrap_or(0);

    let mut r = Reader {
        data,
        at: header_end + 1,
    };
    let count = if encoding >= 4 {
        r.u32()? as usize
    } else {
        r.u16()? as usize
    };
    ensure!(
        count <= data.len(),
        "DMX string table count {count} exceeds the file"
    );
    let mut strings = Vec::with_capacity(count);
    for _ in 0..count {
        strings.push(r.cstr()?);
    }
    let table = |r: &mut Reader| -> Result<String> {
        let index = if encoding >= 5 {
            r.u32()? as usize
        } else {
            r.u16()? as usize
        };
        strings
            .get(index)
            .cloned()
            .with_context(|| format!("DMX string index {index} out of range"))
    };

    let element_count = r.u32()? as usize;
    ensure!(
        element_count <= data.len() / 18,
        "DMX element count {element_count} exceeds the file"
    );
    let mut elements = Vec::with_capacity(element_count);
    for _ in 0..element_count {
        let kind = table(&mut r)?;
        let name = if encoding >= 4 {
            table(&mut r)?
        } else {
            r.cstr()?
        };
        r.take(16)?;
        elements.push(Element {
            kind,
            name,
            attributes: Vec::new(),
        });
    }
    for element in elements.iter_mut().take(element_count) {
        let attribute_count = r.u32()? as usize;
        ensure!(
            attribute_count <= data.len(),
            "DMX attribute count exceeds the file"
        );
        let mut attributes = Vec::with_capacity(attribute_count.min(256));
        for _ in 0..attribute_count {
            let name = table(&mut r)?;
            let kind = r.u8()?;
            let value = if kind >= FIRST_ARRAY {
                let n = r.u32()? as usize;
                ensure!(n <= data.len(), "DMX array length exceeds the file");
                let mut items = Vec::with_capacity(n.min(4096));
                for _ in 0..n {
                    items.push(value(
                        &mut r,
                        kind - FIRST_ARRAY + 1,
                        true,
                        encoding,
                        &table,
                        element_count,
                    )?);
                }
                Value::Array(items)
            } else {
                value(&mut r, kind, false, encoding, &table, element_count)?
            };
            attributes.push((name, value));
        }
        element.attributes = attributes;
    }
    Ok(Dmx {
        format,
        format_version,
        elements,
    })
}

const FIRST_ARRAY: u8 = 15;

fn value(
    r: &mut Reader,
    kind: u8,
    in_array: bool,
    encoding: u32,
    table: &dyn Fn(&mut Reader) -> Result<String>,
    element_count: usize,
) -> Result<Value> {
    Ok(match kind {
        1 => {
            let index = r.i32()?;
            if index == -2 {
                // A reference to an element elsewhere, by GUID text.
                r.cstr()?;
                Value::Element(None)
            } else if index < 0 {
                Value::Element(None)
            } else {
                ensure!(
                    (index as usize) < element_count,
                    "DMX element reference {index} out of range"
                );
                Value::Element(Some(index as usize))
            }
        }
        2 => Value::Int(r.i32()?),
        3 => Value::Float(r.f32()?),
        4 => Value::Bool(r.u8()? != 0),
        5 => Value::String(if encoding >= 4 && !in_array {
            table(r)?
        } else {
            r.cstr()?
        }),
        6 => {
            let n = r.u32()? as usize;
            Value::Binary(r.take(n)?.to_vec())
        }
        7 => Value::Time(r.i32()? as f32 / 10_000.0),
        8 => {
            let b = r.take(4)?;
            Value::Color([b[0], b[1], b[2], b[3]])
        }
        9 => Value::Vector2([r.f32()?, r.f32()?]),
        10 => Value::Vector3([r.f32()?, r.f32()?, r.f32()?]),
        11 => Value::Vector4([r.f32()?, r.f32()?, r.f32()?, r.f32()?]),
        12 => Value::QAngle([r.f32()?, r.f32()?, r.f32()?]),
        13 => Value::Quaternion([r.f32()?, r.f32()?, r.f32()?, r.f32()?]),
        14 => {
            let mut m = [0f32; 16];
            for v in &mut m {
                *v = r.f32()?;
            }
            Value::Matrix(m)
        }
        other => bail!("DMX attribute type {other} is not known"),
    })
}

struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.at.checked_add(n).context("DMX offset overflow")?;
        let bytes = self.data.get(self.at..end).context("DMX file ends early")?;
        self.at = end;
        Ok(bytes)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into()?))
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into()?))
    }
    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into()?))
    }
    fn f32(&mut self) -> Result<f32> {
        Ok(f32::from_le_bytes(self.take(4)?.try_into()?))
    }
    fn cstr(&mut self) -> Result<String> {
        let rest = &self.data[self.at.min(self.data.len())..];
        let len = rest
            .iter()
            .position(|&b| b == 0)
            .context("DMX string has no terminator")?;
        let s = String::from_utf8_lossy(&rest[..len]).into_owned();
        self.at += len + 1;
        Ok(s)
    }
}

/// One function of a particle system: its name and parameters.
#[derive(Debug, Clone)]
pub struct Function {
    pub name: String,
    pub parameters: BTreeMap<String, Value>,
}

/// A child system: another definition, started with this one after `delay`.
#[derive(Debug, Clone)]
pub struct Child {
    pub name: String,
    pub delay: f32,
}

/// A `DmeParticleSystemDefinition`.
#[derive(Debug, Clone)]
pub struct Definition {
    pub name: String,
    /// The definition's own attributes, every one except the function and
    /// child arrays.
    pub attributes: BTreeMap<String, Value>,
    pub renderers: Vec<Function>,
    pub operators: Vec<Function>,
    pub initializers: Vec<Function>,
    pub emitters: Vec<Function>,
    pub forces: Vec<Function>,
    pub constraints: Vec<Function>,
    pub children: Vec<Child>,
}

pub const FUNCTION_LISTS: [&str; 6] = [
    "renderers",
    "operators",
    "initializers",
    "emitters",
    "forces",
    "constraints",
];

/// Every particle system definition in a `.pcf`.
pub fn definitions(data: &[u8]) -> Result<Vec<Definition>> {
    let dmx = parse(data)?;
    ensure!(dmx.format == "pcf", "DMX format {} is not pcf", dmx.format);
    let functions = |element: &Element, list: &str| -> Vec<Function> {
        element
            .elements(list)
            .into_iter()
            .map(|i| {
                let f = &dmx.elements[i];
                let name = f.string("functionName").unwrap_or(&f.name).to_string();
                Function {
                    name,
                    parameters: f
                        .attributes
                        .iter()
                        .filter(|(k, _)| k != "functionName")
                        .cloned()
                        .collect(),
                }
            })
            .collect()
    };
    Ok(dmx
        .elements
        .iter()
        .filter(|e| e.kind == "DmeParticleSystemDefinition")
        .map(|e| {
            let children = e
                .elements("children")
                .into_iter()
                .filter_map(|i| {
                    let child = &dmx.elements[i];
                    let target = match child.get("child")? {
                        Value::Element(Some(t)) => &dmx.elements[*t],
                        _ => return None,
                    };
                    let delay = match child.get("delay") {
                        Some(Value::Float(d)) => *d,
                        _ => 0.0,
                    };
                    Some(Child {
                        name: target.name.clone(),
                        delay,
                    })
                })
                .collect();
            Definition {
                name: e.name.clone(),
                attributes: e
                    .attributes
                    .iter()
                    .filter(|(k, _)| !FUNCTION_LISTS.contains(&k.as_str()) && k != "children")
                    .cloned()
                    .collect(),
                renderers: functions(e, "renderers"),
                operators: functions(e, "operators"),
                initializers: functions(e, "initializers"),
                emitters: functions(e, "emitters"),
                forces: functions(e, "forces"),
                constraints: functions(e, "constraints"),
                children,
            }
        })
        .collect())
}

/// The `.pcf` files a map can draw from, in the order the engine loads them:
/// `particles/particles_manifest.txt`, then the map's own
/// `maps/<map>_particles.txt` (which may replace the list), each a
/// `particles_manifest { "file" "particles/x.pcf" ... }` block. A leading `!`
/// marks a file to precache; it is read either way.
pub fn manifest_files(text: &str) -> Vec<String> {
    let mut files = Vec::new();
    let mut tokens = tokens(text).into_iter();
    while let Some(token) = tokens.next() {
        if token.eq_ignore_ascii_case("file")
            && let Some(value) = tokens.next()
        {
            let path = value
                .trim_start_matches('!')
                .replace('\\', "/")
                .to_ascii_lowercase();
            if !files.contains(&path) {
                files.push(path);
            }
        }
    }
    files
}

/// KeyValues tokens: quoted strings or bare words, braces dropped, `//`
/// comments skipped.
fn tokens(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut chars = text.chars().peekable();
    while let Some(&c) = chars.peek() {
        if c.is_whitespace() || c == '{' || c == '}' {
            chars.next();
        } else if c == '/' {
            chars.next();
            if chars.peek() == Some(&'/') {
                for c in chars.by_ref() {
                    if c == '\n' {
                        break;
                    }
                }
            }
        } else if c == '"' {
            chars.next();
            let mut s = String::new();
            for c in chars.by_ref() {
                if c == '"' {
                    break;
                }
                s.push(c);
            }
            out.push(s);
        } else {
            let mut s = String::new();
            while let Some(&c) = chars.peek() {
                if c.is_whitespace() || c == '"' || c == '{' || c == '}' {
                    break;
                }
                s.push(c);
                chars.next();
            }
            out.push(s);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build(encoding: u32) -> Vec<u8> {
        let mut out =
            format!("<!-- dmx encoding binary {encoding} format pcf 1 -->\n").into_bytes();
        out.push(0);
        let strings = [
            "DmeElement",
            "DmeParticleSystemDefinition",
            "DmeParticleOperator",
            "particleSystemDefinitions",
            "renderers",
            "functionName",
            "max_particles",
            "render_animated_sprites",
        ];
        let put_count = |out: &mut Vec<u8>, n: usize| {
            if encoding >= 4 {
                out.extend_from_slice(&(n as u32).to_le_bytes());
            } else {
                out.extend_from_slice(&(n as u16).to_le_bytes());
            }
        };
        let put_index = |out: &mut Vec<u8>, i: usize| {
            if encoding >= 5 {
                out.extend_from_slice(&(i as u32).to_le_bytes());
            } else {
                out.extend_from_slice(&(i as u16).to_le_bytes());
            }
        };
        put_count(&mut out, strings.len());
        for s in strings {
            out.extend_from_slice(s.as_bytes());
            out.push(0);
        }
        out.extend_from_slice(&3u32.to_le_bytes());
        for (kind, name) in [(0, "root"), (1, "sparks"), (2, "op")] {
            put_index(&mut out, kind);
            if encoding >= 4 {
                // Element names go through the table too; reuse the type name.
                put_index(&mut out, kind);
            } else {
                out.extend_from_slice(name.as_bytes());
                out.push(0);
            }
            out.extend_from_slice(&[0; 16]);
        }
        // root: particleSystemDefinitions = [1]
        out.extend_from_slice(&1u32.to_le_bytes());
        put_index(&mut out, 3);
        out.push(15);
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&1i32.to_le_bytes());
        // definition: max_particles = 32, renderers = [2]
        out.extend_from_slice(&2u32.to_le_bytes());
        put_index(&mut out, 6);
        out.push(2);
        out.extend_from_slice(&32i32.to_le_bytes());
        put_index(&mut out, 4);
        out.push(15);
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&2i32.to_le_bytes());
        // operator: functionName = render_animated_sprites
        out.extend_from_slice(&1u32.to_le_bytes());
        put_index(&mut out, 5);
        out.push(5);
        if encoding >= 4 {
            put_index(&mut out, 7);
        } else {
            out.extend_from_slice(b"render_animated_sprites\0");
        }
        out
    }

    #[test]
    fn reads_every_binary_encoding() {
        for encoding in 2..=5 {
            let defs = definitions(&build(encoding)).unwrap();
            assert_eq!(defs.len(), 1, "encoding {encoding}");
            assert_eq!(defs[0].renderers[0].name, "render_animated_sprites");
            assert_eq!(
                defs[0].attributes.get("max_particles"),
                Some(&Value::Int(32))
            );
        }
    }

    #[test]
    fn manifest_lists_files_once_without_precache_marks() {
        let text = "particles_manifest\n{\n // c\n \"file\" \"!particles/a.pcf\"\n file particles\\B.pcf\n \"file\" \"particles/a.pcf\"\n}";
        assert_eq!(
            manifest_files(text),
            vec!["particles/a.pcf", "particles/b.pcf"]
        );
    }
}
