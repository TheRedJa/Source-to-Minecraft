//! `scripts/titles.txt`, the HUD message table `env_message` names.
//!
//! The Source client only hands a message name to `engine->TextMessageGet`
//! (SDK 2013 `game/client/message.cpp` `MessageAdd`, `cdll_util.cpp`); loading
//! and parsing the file is engine code that is not in the SDK. The format
//! followed here is the one the GoldSrc/Source `titles.txt` has and that the
//! open reimplementation Xash3D FWGS reads in `engine/client/titles.c`
//! (`CL_TextMessageParse` and `TitleParseDirective`):
//!
//! - Lines are trimmed. Empty lines and lines starting with `//` are skipped
//!   outside a body.
//! - `$position x y`, `$effect n`, `$color r g b`, `$color2 r g b`,
//!   `$fadein s`, `$fadeout s`, `$holdtime s` and `$fxtime s` set the
//!   parameters of every message that follows, until set again. A directive
//!   with too few arguments is ignored, extra arguments are ignored, and an
//!   unknown directive (`$boxcolor`, `$boxsize` and `$clearmessage` occur in
//!   Half-Life 2's file) is skipped.
//! - Any other non-empty line is a message name (the last one before the
//!   `{` wins, spaces allowed); a line starting with `{` opens its body and a
//!   line starting with `}` closes it. The body is the raw text between those
//!   lines, its lines joined by `\n`.
//!
//! Choices this file makes where the sources differ or say nothing:
//! - Before any directive every number is 0, as Xash3D's zero-initialized
//!   parameters are. Half-Life 2's file sets all of them before its first
//!   message, so the defaults never show there.
//! - The colors' alpha is 255. Xash3D leaves it 0 and the Source client never
//!   reads it (`message.cpp` draws with its own alpha); `$color` accepts an
//!   optional fourth number and takes it as alpha.
//! - A `\r` from CRLF line endings is dropped from body lines, which Xash3D
//!   keeps. Directive numbers are read as C's `atoi`/`atof` do: a leading
//!   number counts and a non-number is 0.

use serde::Serialize;

/// One message of `titles.txt` with the parameters in force where it stands.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Title {
    /// As the file writes it.
    pub name: String,
    /// Screen fractions 0 to 1; -1 centers.
    pub x: f64,
    pub y: f64,
    pub effect: u32,
    /// `[r, g, b, a]`.
    pub color1: [u8; 4],
    pub color2: [u8; 4],
    pub fadein: f64,
    pub fadeout: f64,
    pub holdtime: f64,
    pub fxtime: f64,
    /// The body, lines joined by `\n`.
    pub text: String,
}

#[derive(Clone)]
struct Parameters {
    x: f64,
    y: f64,
    effect: u32,
    color1: [u8; 4],
    color2: [u8; 4],
    fadein: f64,
    fadeout: f64,
    holdtime: f64,
    fxtime: f64,
}

impl Default for Parameters {
    fn default() -> Self {
        Parameters {
            x: 0.0,
            y: 0.0,
            effect: 0,
            color1: [0, 0, 0, 255],
            color2: [0, 0, 0, 255],
            fadein: 0.0,
            fadeout: 0.0,
            holdtime: 0.0,
            fxtime: 0.0,
        }
    }
}

/// Every message of a file in file order. A name may repeat; the first
/// definition is the one a lookup finds, as Xash3D's `CL_TextMessageGet` does.
pub fn parse(text: &str) -> Vec<Title> {
    let mut titles = Vec::new();
    let mut parameters = Parameters::default();
    let mut name = String::new();
    let mut body: Option<Vec<&str>> = None;
    for line in text.split('\n') {
        let line = line.strip_suffix('\r').unwrap_or(line);
        let trimmed = line.trim();
        if let Some(lines) = &mut body {
            if trimmed.starts_with('}') {
                let p = &parameters;
                titles.push(Title {
                    name: name.clone(),
                    x: p.x,
                    y: p.y,
                    effect: p.effect,
                    color1: p.color1,
                    color2: p.color2,
                    fadein: p.fadein,
                    fadeout: p.fadeout,
                    holdtime: p.holdtime,
                    fxtime: p.fxtime,
                    text: lines.join("\n"),
                });
                body = None;
            } else if trimmed.starts_with('{') {
                // Xash3D abandons the whole file here.
                return titles;
            } else {
                lines.push(line);
            }
        } else if trimmed.is_empty() || trimmed.starts_with("//") {
        } else if trimmed.starts_with('$') {
            directive(trimmed, &mut parameters);
        } else if trimmed.starts_with('{') {
            body = Some(Vec::new());
        } else if trimmed.starts_with('}') {
            return titles;
        } else {
            name = trimmed.to_string();
        }
    }
    titles
}

fn directive(line: &str, p: &mut Parameters) {
    let mut words = line.split_whitespace();
    let Some(name) = words.next() else { return };
    let args: Vec<&str> = words.collect();
    let number = |i: usize| atof(args[i]);
    let color = |args: &[&str]| -> Option<[u8; 4]> {
        (args.len() >= 3).then(|| {
            let channel = |i: usize| atof(args[i]).clamp(0.0, 255.0) as u8;
            let alpha = args.get(3).map_or(255, |_| channel(3));
            [channel(0), channel(1), channel(2), alpha]
        })
    };
    match name.to_ascii_lowercase().as_str() {
        "$position" if args.len() >= 2 => (p.x, p.y) = (number(0), number(1)),
        "$effect" if !args.is_empty() => p.effect = number(0).max(0.0) as u32,
        "$color" => p.color1 = color(&args).unwrap_or(p.color1),
        "$color2" => p.color2 = color(&args).unwrap_or(p.color2),
        "$fadein" if !args.is_empty() => p.fadein = number(0),
        "$fadeout" if !args.is_empty() => p.fadeout = number(0),
        "$holdtime" if !args.is_empty() => p.holdtime = number(0),
        "$fxtime" if !args.is_empty() => p.fxtime = number(0),
        _ => {}
    }
}

/// C's `atof` for the plain decimals the file holds: the longest leading
/// number, 0 when there is none, and never infinite or NaN.
fn atof(word: &str) -> f64 {
    let end = word
        .char_indices()
        .take_while(|&(i, c)| c.is_ascii_digit() || c == '.' || (i == 0 && (c == '-' || c == '+')))
        .last()
        .map_or(0, |(i, c)| i + c.len_utf8());
    word[..end]
        .parse::<f64>()
        .ok()
        .filter(|v| v.is_finite())
        .unwrap_or(0.0)
}

/// The first message named `name`, ignoring case; one leading `#` is dropped
/// first, as `CHudMessage::MessageAdd` drops it before `TextMessageGet`.
pub fn find<'a>(titles: &'a [Title], name: &str) -> Option<&'a Title> {
    let name = name.strip_prefix('#').unwrap_or(name);
    titles.iter().find(|t| t.name.eq_ignore_ascii_case(name))
}

/// The messages of the game's `scripts/titles.txt`; empty when it has none.
pub fn load(vfs: &crate::source::vfs::Vfs, pak: Option<&vbsp::Packfile>) -> Vec<Title> {
    crate::source::vfs::read_file(vfs, pak, "scripts/titles.txt")
        .map(|bytes| parse(&crate::source::captions::decode(&bytes)))
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "// header\r\n\
        $position -1 -1\r\n$effect 2\r\n$color 100 100 100\r\n$color2 240 110 0 77\r\n\
        $fadein 0.01\r\n$fxtime 0.25\r\n$holdtime 3.5\r\n$fadeout 1.5\r\n\
        \r\nFIRST\r\n{\r\nLine one\r\n  Line two\r\n}\r\n\
        $holdtime 9\r\n$position 0.1 0.8\r\n$bogus 1 2\r\n\
        Second Name\r\n{\r\n#HL2_Token\r\n}\r\n";

    #[test]
    fn directives_persist_and_bodies_join_their_lines() {
        let titles = parse(SAMPLE);
        assert_eq!(titles.len(), 2);
        let first = &titles[0];
        assert_eq!(first.name, "FIRST");
        assert_eq!(first.text, "Line one\n  Line two");
        assert_eq!((first.x, first.y, first.effect), (-1.0, -1.0, 2));
        assert_eq!(first.color1, [100, 100, 100, 255]);
        assert_eq!(first.color2, [240, 110, 0, 77]);
        assert_eq!(
            (first.fadein, first.fadeout, first.holdtime, first.fxtime),
            (0.01, 1.5, 3.5, 0.25)
        );
        let second = &titles[1];
        assert_eq!(second.name, "Second Name");
        assert_eq!(second.text, "#HL2_Token");
        // Changed directives apply, the rest persist.
        assert_eq!((second.x, second.y, second.holdtime), (0.1, 0.8, 9.0));
        assert_eq!(
            (second.effect, second.fadein, second.color1[0]),
            (2, 0.01, 100)
        );
    }

    #[test]
    fn a_message_before_any_directive_has_the_defaults() {
        let titles = parse("EARLY\n{\nhi\n}\n");
        let t = &titles[0];
        assert_eq!(
            (t.x, t.y, t.effect, t.fadein, t.holdtime),
            (0.0, 0.0, 0, 0.0, 0.0)
        );
        assert_eq!(t.color1, [0, 0, 0, 255]);
        assert_eq!(t.text, "hi");
    }

    #[test]
    fn lookups_ignore_case_and_one_hash_and_find_the_first() {
        let titles = parse("A\n{\none\n}\na\n{\ntwo\n}\n");
        assert_eq!(find(&titles, "#a").unwrap().text, "one");
        assert_eq!(find(&titles, "A").unwrap().text, "one");
        assert!(find(&titles, "##a").is_none());
        assert!(find(&titles, "b").is_none());
    }

    #[test]
    fn short_directives_are_ignored() {
        let titles = parse("$position 0.5\n$color 1 2\n$fadein\nX\n{\nt\n}\n");
        let t = &titles[0];
        assert_eq!((t.x, t.y, t.fadein), (0.0, 0.0, 0.0));
        assert_eq!(t.color1, [0, 0, 0, 255]);
    }

    /// Half-Life 2's own file: `CHAPTER1_TITLE` is in the chapter block with
    /// the values the file sets above it.
    #[test]
    fn reads_half_life_2_titles() {
        let map = std::path::Path::new(
            "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_01.bsp",
        );
        if !map.exists() {
            return;
        }
        let vfs = crate::source::vfs::Vfs::for_map(map, &[]);
        let titles = load(&vfs, None);
        let chapter = find(&titles, "#CHAPTER1_TITLE").expect("CHAPTER1_TITLE");
        assert_eq!(chapter.text, "#HL2_Chapter1_Title");
        assert_eq!(chapter.fadein, 0.01);
        assert_eq!(chapter.holdtime, 3.5);
        assert_eq!((chapter.x, chapter.y), (-1.0, 0.58));
        assert_eq!(chapter.effect, 2);
        assert_eq!(chapter.color1, [100, 100, 100, 255]);
        assert_eq!(chapter.color2, [240, 110, 0, 255]);
        assert_eq!(chapter.fadeout, 1.5);
        assert_eq!(chapter.fxtime, 0.25);
    }
}
