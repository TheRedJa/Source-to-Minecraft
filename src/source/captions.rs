//! Closed captions: the `resource/closecaption_<language>.txt` and
//! `resource/subtitles_<language>.txt` files, UTF-16 KeyValues of
//! `"lang" { "Language" "English" "Tokens" { "<token>" "<text>" } }`.

use crate::source::keyvalues::{self, Value};
use std::collections::HashMap;

/// A caption longer than this is garbage, not a line anyone reads.
const MAX_TEXT_BYTES: usize = 16 * 1024;

/// Decode a caption file: UTF-16 with a byte-order mark as Source writes
/// them, little-endian without one, or UTF-8 when it starts with a UTF-8 BOM
/// or is evidently not UTF-16 (no zero bytes at all).
pub fn decode(bytes: &[u8]) -> String {
    let (bytes, big_endian) = match bytes {
        [0xFF, 0xFE, rest @ ..] => (rest, false),
        [0xFE, 0xFF, rest @ ..] => (rest, true),
        [0xEF, 0xBB, 0xBF, rest @ ..] => return String::from_utf8_lossy(rest).into_owned(),
        _ if !bytes.contains(&0) => return String::from_utf8_lossy(bytes).into_owned(),
        _ => (bytes, false),
    };
    let units: Vec<u16> = bytes
        .as_chunks::<2>()
        .0
        .iter()
        .map(|&pair| {
            if big_endian {
                u16::from_be_bytes(pair)
            } else {
                u16::from_le_bytes(pair)
            }
        })
        .collect();
    String::from_utf16_lossy(&units)
}

/// The tokens of one caption file by lowercase token, the first definition of
/// a token winning. `[english]` keys, the untranslated originals translators
/// keep beside their text, are not captions.
pub fn parse(text: &str) -> HashMap<String, String> {
    let mut captions = HashMap::new();
    let root = keyvalues::parse_escaped(text);
    for (_, lang) in &root {
        let Value::Block(lang) = lang else { continue };
        let Some(Value::Block(tokens)) = keyvalues::get(lang, "Tokens") else {
            continue;
        };
        for (token, value) in tokens {
            let Value::Text(value) = value else { continue };
            let token = token.trim();
            if token.is_empty() || token.starts_with('[') || value.len() > MAX_TEXT_BYTES {
                continue;
            }
            captions
                .entry(token.to_ascii_lowercase())
                .or_insert_with(|| value.clone());
        }
    }
    captions
}

#[cfg(test)]
mod tests {
    use super::*;

    fn utf16(text: &str) -> Vec<u8> {
        let mut bytes = vec![0xFF, 0xFE];
        bytes.extend(text.encode_utf16().flat_map(u16::to_le_bytes));
        bytes
    }

    #[test]
    fn reads_utf16_tokens_with_tags_and_escapes() {
        let file = utf16(
            "\"lang\"\r\n{\r\n\t\"Language\" \"English\"\r\n\t\"Tokens\"\r\n\t{\r\n\
             \t\t// player\r\n\
             \t\t\"Player.Monologue.Furnace.Cellar\"\t\"<clr:250,231,181>Looks like they could use some ventilation here.\"\r\n\
             \t\t\"[english]Player.Monologue.Furnace.Cellar\"\t\"untranslated\"\r\n\
             \t\t\"xray.box\" \"<sfx><len:2>[Female Computer Voice \\\"Box\\\"]\"\r\n\
             \t\t\"XRAY.BOX\" \"a later duplicate\"\r\n\
             \t}\r\n}\r\n",
        );
        let captions = parse(&decode(&file));
        assert_eq!(captions.len(), 2);
        assert_eq!(
            captions["player.monologue.furnace.cellar"],
            "<clr:250,231,181>Looks like they could use some ventilation here."
        );
        assert_eq!(
            captions["xray.box"], "<sfx><len:2>[Female Computer Voice \"Box\"]",
            "escaped quotes are text, and the first definition wins"
        );
    }

    #[test]
    fn plain_utf8_files_are_read_too() {
        let captions = parse(&decode(b"\"lang\" { \"Tokens\" { \"A.B\" \"<I>hi\" } }"));
        assert_eq!(captions["a.b"], "<I>hi");
    }
}
