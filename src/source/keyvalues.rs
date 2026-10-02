//! KeyValues trees, the text format of Source's scripts: soundscapes,
//! soundscripts and surface properties.
//!
//! Keys repeat and order matters (a soundscape lists several `playlooping`
//! blocks), so a block is a list of pairs, not a map.

/// One value: a string, or a nested block.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Text(String),
    Block(Block),
}

/// The pairs of one block, in file order. Keys are kept as written.
pub type Block = Vec<(String, Value)>;

/// Parse a whole file into its top-level pairs. Malformed input is read as
/// far as it makes sense: a stray `}` closes nothing, and a key with no value
/// at the end of the file is dropped.
pub fn parse(text: &str) -> Block {
    let tokens = crate::source::vmt::tokenize(text);
    let mut cursor = 0;
    let mut root = Vec::new();
    while cursor < tokens.len() {
        root.extend(block(&tokens, &mut cursor));
        // A `}` with no block open.
        cursor += 1;
    }
    root
}

fn block(tokens: &[String], cursor: &mut usize) -> Block {
    let mut pairs = Vec::new();
    while let Some(key) = tokens.get(*cursor) {
        if key == "}" {
            return pairs;
        }
        *cursor += 1;
        if key == "{" {
            // A block with no key; read it so its braces stay balanced.
            block(tokens, cursor);
            *cursor += 1;
            continue;
        }
        match tokens.get(*cursor).map(String::as_str) {
            Some("{") => {
                *cursor += 1;
                let inner = block(tokens, cursor);
                *cursor += 1;
                pairs.push((key.clone(), Value::Block(inner)));
            }
            Some("}") | None => return pairs,
            Some(value) => {
                *cursor += 1;
                pairs.push((key.clone(), Value::Text(value.to_string())));
            }
        }
    }
    pairs
}

/// The first value under `key`, compared case-insensitively.
pub fn get<'a>(block: &'a Block, key: &str) -> Option<&'a Value> {
    block
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .map(|(_, v)| v)
}

/// The first string value under `key`.
pub fn text<'a>(block: &'a Block, key: &str) -> Option<&'a str> {
    match get(block, key)? {
        Value::Text(text) => Some(text),
        Value::Block(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_blocks_keep_repeated_keys_in_order() {
        let parsed = parse(
            r#"
            "a.b"
            {
                "dsp" "1"
                // comment
                "playlooping" { "wave" "x.wav" }
                "playlooping" { "wave" "y.wav" }
            }
            "#,
        );
        assert_eq!(parsed.len(), 1);
        let Value::Block(inner) = &parsed[0].1 else {
            panic!("expected a block")
        };
        assert_eq!(text(inner, "DSP"), Some("1"));
        let waves: Vec<_> = inner
            .iter()
            .filter_map(|(_, v)| match v {
                Value::Block(b) => text(b, "wave"),
                _ => None,
            })
            .collect();
        assert_eq!(waves, ["x.wav", "y.wav"]);
    }

    #[test]
    fn unquoted_tokens_and_stray_braces_are_tolerated() {
        let parsed = parse("}\nentry { volume 0.5,0.7 wave common/a.wav }\n");
        let Value::Block(inner) = &parsed[0].1 else {
            panic!("expected a block")
        };
        assert_eq!(text(inner, "volume"), Some("0.5,0.7"));
        assert_eq!(text(inner, "wave"), Some("common/a.wav"));
    }
}
