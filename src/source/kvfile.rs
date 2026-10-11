//! Loading a KeyValues file the way the engine's `KeyValues::LoadFromBuffer`
//! does: `[...]` conditionals drop the key they follow, `#include` appends the
//! top-level keys of another file and `#base` merges another file underneath.
//!
//! The plain [`keyvalues`](super::keyvalues) parser ignores conditionals, which
//! is right for scripts that never use them. VGUI layouts (`scripts/screens`)
//! and schemes use them: `"xpos" "370" [!$X360]`.
//!
//! Semantics are those of the Source SDK 2013 `tier1/KeyValues.cpp`:
//! - A conditional follows a leaf's value, or sits between a block's name and
//!   its `{`. A false one removes the whole key.
//! - `#base` files are resolved relative to the including file. Their
//!   top-level keys merge into ours: a key we already have (compared
//!   case-sensitively, the first one) keeps its own value and takes over only
//!   the base's children it lacks, recursively; any other base key is
//!   appended. Several `#base` files merge in order.
//! - `#include` appends the included file's top-level keys after ours,
//!   before the `#base` merge.
//!
//! One deviation, deliberate: the SDK's `EvaluateConditional` knows only a
//! single `$SYMBOL` with an optional leading `!`, and reads a conditional
//! containing a space as ordinary tokens. INFRA ships conditionals such as
//! `[!$INFRA_PLAYTEST_MODE || !$RUSSIAN]` and `[$A && $B]`, so those are read
//! as boolean expressions (`!`, `&&`, `||`, parentheses) over the symbols.

use crate::source::keyvalues::{Block, Value};
use crate::source::vfs::Vfs;

/// Include/base nesting that is followed before a file is taken to loop.
const MAX_DEPTH: usize = 8;

/// The symbols that are true on the platform being modelled, Windows PC.
/// Every other symbol, `$X360`, `$OSX`, `$LINUX`, `$POSIX`, `$DECK` and a
/// game's own such as `$INFRA_PLAYTEST_MODE` or `$RUSSIAN`, is false: the
/// English retail build.
const TRUE_SYMBOLS: &[&str] = &["win32", "windows"];

/// Load `path` from the search path with its conditionals evaluated and its
/// `#include`/`#base` files merged; `None` when the file does not exist.
pub fn load(vfs: &Vfs, path: &str) -> Option<Block> {
    load_at(vfs, path, 0)
}

fn load_at(vfs: &Vfs, path: &str, depth: usize) -> Option<Block> {
    let bytes = vfs.open(path)?;
    let text = crate::source::captions::decode(&bytes);
    let dir = path.rsplit_once(['/', '\\']).map_or("", |(dir, _)| dir);
    Some(parse_with(&text, &mut |file| {
        if depth >= MAX_DEPTH {
            return None;
        }
        let full = if dir.is_empty() {
            file.to_string()
        } else {
            format!("{dir}/{file}")
        };
        load_at(vfs, &full, depth + 1)
    }))
}

/// Parse text; `resolve` loads the file an `#include` or `#base` names,
/// relative to the including file.
pub fn parse_with(text: &str, resolve: &mut dyn FnMut(&str) -> Option<Block>) -> Block {
    let tokens = tokenize(text);
    let mut cursor = 0;
    let mut root: Block = Vec::new();
    let mut included = Vec::new();
    let mut bases = Vec::new();
    while cursor < tokens.len() {
        let Token::Text(key, _) = &tokens[cursor] else {
            // A stray brace or conditional where a key belongs.
            cursor += 1;
            continue;
        };
        cursor += 1;
        if key.is_empty() {
            break;
        }
        if key.eq_ignore_ascii_case("#include") || key.eq_ignore_ascii_case("#base") {
            let is_base = key.eq_ignore_ascii_case("#base");
            if let Some(Token::Text(file, _)) = tokens.get(cursor) {
                cursor += 1;
                if let Some(loaded) = resolve(file) {
                    if is_base {
                        bases.push(loaded);
                    } else {
                        included.push(loaded);
                    }
                }
            }
            continue;
        }
        let mut accepted = true;
        if let Some(Token::Condition(condition)) = tokens.get(cursor) {
            accepted = evaluate(condition);
            cursor += 1;
        }
        if matches!(tokens.get(cursor), Some(Token::Open)) {
            cursor += 1;
            let inner = block(&tokens, &mut cursor);
            if accepted {
                root.push((key.clone(), Value::Block(inner)));
            }
        } else {
            // "LoadFromBuffer: missing {": the engine goes on after the token.
            cursor += 1;
        }
    }
    for file in included {
        root.extend(file);
    }
    for base in &bases {
        merge(&mut root, base);
    }
    root
}

/// `RecursiveLoadFromBuffer`: the pairs of one block, up to its `}`.
fn block(tokens: &[Token], cursor: &mut usize) -> Block {
    let mut pairs = Vec::new();
    loop {
        let name = match tokens.get(*cursor) {
            Some(Token::Text(name, _)) if !name.is_empty() => name.clone(),
            Some(Token::Close) => {
                *cursor += 1;
                return pairs;
            }
            // End of file, an empty key or a misplaced token: the engine stops.
            _ => return pairs,
        };
        *cursor += 1;
        let mut accepted = true;
        if let Some(Token::Condition(condition)) = tokens.get(*cursor) {
            accepted = evaluate(condition);
            *cursor += 1;
        }
        let value = match tokens.get(*cursor) {
            Some(Token::Open) => {
                *cursor += 1;
                Value::Block(block(tokens, cursor))
            }
            Some(Token::Text(text, _)) => {
                *cursor += 1;
                // A conditional after the value.
                if let Some(Token::Condition(condition)) = tokens.get(*cursor) {
                    accepted = evaluate(condition);
                    *cursor += 1;
                }
                Value::Text(text.clone())
            }
            Some(Token::Close) => {
                *cursor += 1;
                return pairs;
            }
            _ => return pairs,
        };
        if accepted {
            pairs.push((name, value));
        }
    }
}

/// `RecursiveMergeKeyValues`: `base` is what `target` is based on. Our values
/// win; base children we have no key for are appended.
fn merge(target: &mut Block, base: &Block) {
    for (name, base_value) in base {
        match target.iter_mut().find(|(key, _)| key == name) {
            Some((_, Value::Block(ours))) => {
                if let Value::Block(theirs) = base_value {
                    merge(ours, theirs);
                }
            }
            Some(_) => {}
            None => target.push((name.clone(), base_value.clone())),
        }
    }
}

#[derive(Debug, PartialEq)]
enum Token {
    /// A string and whether it was quoted.
    Text(String, bool),
    Open,
    Close,
    /// The inside of a `[...]`.
    Condition(String),
}

fn tokenize(text: &str) -> Vec<Token> {
    let bytes = text.as_bytes();
    let mut tokens = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        let c = bytes[i];
        if c.is_ascii_whitespace() {
            i += 1;
        } else if c == b'/' && bytes.get(i + 1) == Some(&b'/') {
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
        } else if c == b'"' {
            i += 1;
            let start = i;
            while i < bytes.len() && bytes[i] != b'"' {
                i += 1;
            }
            tokens.push(Token::Text(
                String::from_utf8_lossy(&bytes[start..i.min(bytes.len())]).into_owned(),
                true,
            ));
            i += 1;
        } else if c == b'{' {
            tokens.push(Token::Open);
            i += 1;
        } else if c == b'}' {
            tokens.push(Token::Close);
            i += 1;
        } else if c == b'[' {
            // Read to the `]` even across spaces; see the module comment.
            let start = i + 1;
            while i < bytes.len() && bytes[i] != b']' {
                i += 1;
            }
            tokens.push(Token::Condition(
                String::from_utf8_lossy(&bytes[start..i.min(bytes.len())]).into_owned(),
            ));
            i += 1;
        } else {
            let start = i;
            while i < bytes.len()
                && !bytes[i].is_ascii_whitespace()
                && !matches!(bytes[i], b'{' | b'}' | b'"')
            {
                i += 1;
            }
            tokens.push(Token::Text(
                String::from_utf8_lossy(&bytes[start..i]).into_owned(),
                false,
            ));
        }
    }
    tokens
}

/// Whether a conditional (without its brackets) holds.
pub fn evaluate(condition: &str) -> bool {
    let mut tokens = Vec::new();
    let bytes = condition.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b' ' | b'\t' | b'\r' | b'\n' => i += 1,
            b'!' | b'(' | b')' => {
                tokens.push(&condition[i..=i]);
                i += 1;
            }
            b'&' | b'|' => {
                let end = (i + 2).min(bytes.len());
                tokens.push(&condition[i..end]);
                i = end;
            }
            _ => {
                let start = i;
                while i < bytes.len() && !b" \t\r\n!()&|".contains(&bytes[i]) {
                    i += 1;
                }
                tokens.push(&condition[start..i]);
            }
        }
    }
    let mut cursor = 0;
    or(&tokens, &mut cursor)
}

fn or(tokens: &[&str], cursor: &mut usize) -> bool {
    let mut value = and(tokens, cursor);
    while tokens.get(*cursor) == Some(&"||") {
        *cursor += 1;
        // No short circuit: the right side must be consumed either way.
        let right = and(tokens, cursor);
        value |= right;
    }
    value
}

fn and(tokens: &[&str], cursor: &mut usize) -> bool {
    let mut value = unary(tokens, cursor);
    while tokens.get(*cursor) == Some(&"&&") {
        *cursor += 1;
        let right = unary(tokens, cursor);
        value &= right;
    }
    value
}

fn unary(tokens: &[&str], cursor: &mut usize) -> bool {
    let Some(&token) = tokens.get(*cursor) else {
        return false;
    };
    *cursor += 1;
    match token {
        "!" => !unary(tokens, cursor),
        "(" => {
            let value = or(tokens, cursor);
            if tokens.get(*cursor) == Some(&")") {
                *cursor += 1;
            }
            value
        }
        symbol => symbol
            .strip_prefix('$')
            .is_some_and(|name| TRUE_SYMBOLS.contains(&name.to_ascii_lowercase().as_str())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn none(_: &str) -> Option<Block> {
        None
    }

    fn text<'a>(block: &'a Block, key: &str) -> Vec<&'a str> {
        block
            .iter()
            .filter(|(k, _)| k == key)
            .filter_map(|(_, v)| match v {
                Value::Text(t) => Some(t.as_str()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn conditionals_follow_the_platform_and_unknown_symbols_are_false() {
        assert!(evaluate("!$X360"));
        assert!(!evaluate("$X360"));
        assert!(evaluate("$WIN32"));
        assert!(evaluate("$windows"));
        assert!(!evaluate("$INFRA_PLAYTEST_MODE"));
        assert!(evaluate("!$INFRA_PLAYTEST_MODE || !$RUSSIAN"));
        assert!(!evaluate("$INFRA_PLAYTEST_MODE && $RUSSIAN"));
        assert!(evaluate("$WIN32 && !$RUSSIAN"));
        assert!(evaluate("$RUSSIAN || ($WIN32 && !$X360)"));
        assert!(!evaluate("!($WIN32 || $RUSSIAN)"));
    }

    #[test]
    fn a_false_conditional_removes_a_leaf_or_a_block() {
        let parsed = parse_with(
            r#"
            "root"
            {
                "xpos" "370" [!$INFRA_PLAYTEST_MODE || !$RUSSIAN]
                "xpos" "345" [$INFRA_PLAYTEST_MODE && $RUSSIAN]
                "gone" [$X360] { "a" "1" }
                "kept" [$WIN32] { "a" "2" }
            }
            "#,
            &mut none,
        );
        let Value::Block(root) = &parsed[0].1 else {
            panic!("block")
        };
        assert_eq!(text(root, "xpos"), ["370"]);
        let names: Vec<_> = root.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(names, ["xpos", "kept"]);
    }

    #[test]
    fn base_keys_merge_under_ours_without_overriding() {
        let mut resolve = |file: &str| {
            assert_eq!(file, "bases/a.res");
            Some(parse_with(
                r#"
                "main" { "keep" "base" "extra" "x" "sub" { "p" "1" "q" "2" } }
                "other.res" { "bg" { "w" "9" } }
                "#,
                &mut none,
            ))
        };
        let parsed = parse_with(
            r#"
            #base "bases/a.res"
            "main" { "keep" "ours" "sub" { "p" "ours" } }
            "#,
            &mut resolve,
        );
        let names: Vec<_> = parsed.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(names, ["main", "other.res"]);
        let Value::Block(main) = &parsed[0].1 else {
            panic!("block")
        };
        assert_eq!(text(main, "keep"), ["ours"]);
        assert_eq!(text(main, "extra"), ["x"]);
        let Some((_, Value::Block(sub))) = main.iter().find(|(k, _)| k == "sub") else {
            panic!("sub")
        };
        assert_eq!(text(sub, "p"), ["ours"]);
        assert_eq!(text(sub, "q"), ["2"]);
    }

    #[test]
    fn include_appends_after_our_keys() {
        let mut resolve = |_: &str| Some(parse_with(r#""inc" { "a" "1" }"#, &mut none));
        let parsed = parse_with("#include \"x.res\"\n\"own\" { }", &mut resolve);
        let names: Vec<_> = parsed.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(names, ["own", "inc"]);
    }
}
