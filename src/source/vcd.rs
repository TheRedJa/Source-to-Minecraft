//! Choreography scenes (`.vcd`), the text files `logic_choreographed_scene`
//! plays: who says which line when, and when the scene fires its
//! `OnTrigger<n>` outputs.
//!
//! The syntax is KeyValues-like but not KeyValues: an event is
//! `event <type> "<name>" { ... }`, `time` takes two bare numbers, and flags
//! such as `fixedlength` stand alone. Only `speak` and `firetrigger` events
//! are kept; every other event and nested block (`flexanimations`,
//! `event_ramp`, `tags`) is skipped by its braces. Every event still counts
//! towards the scene's length.

/// Times past this are garbage, not a scene anyone authored.
const MAX_TIME: f64 = 86_400.0;
/// `OnTrigger1` to `OnTrigger16` exist on a scene entity.
const MAX_TRIGGER: i64 = 16;

#[derive(Debug, Clone, PartialEq)]
pub struct Scene {
    /// Seconds: the latest end, or start for an event without an end.
    pub length: f64,
    /// Kept events in start order.
    pub events: Vec<Event>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    Speak {
        /// Lowercase actor name.
        actor: String,
        start: f64,
        end: f64,
        /// The soundscript, as written.
        script: String,
        /// `cctoken`, empty when the event names none.
        cctoken: String,
        /// `cctype "cc_disabled"`: the line shows no caption.
        caption_disabled: bool,
    },
    FireTrigger {
        start: f64,
        trigger: u8,
    },
}

impl Event {
    pub fn start(&self) -> f64 {
        match self {
            Event::Speak { start, .. } | Event::FireTrigger { start, .. } => *start,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
struct Token {
    text: String,
    quoted: bool,
}

impl Token {
    fn is(&self, word: &str) -> bool {
        !self.quoted && self.text.eq_ignore_ascii_case(word)
    }
}

/// Split into tokens, keeping whether each was quoted so that a quoted
/// `"{"` or `"event"` is never read as syntax. `//` comments run to the end
/// of the line.
fn tokenize(text: &str) -> Vec<Token> {
    let mut tokens = Vec::new();
    let mut chars = text.char_indices().peekable();
    while let Some(&(i, c)) = chars.peek() {
        if c.is_whitespace() {
            chars.next();
        } else if text[i..].starts_with("//") {
            while chars.next_if(|&(_, c)| c != '\n').is_some() {}
        } else if c == '"' {
            chars.next();
            let mut value = String::new();
            for (_, c) in chars.by_ref() {
                if c == '"' {
                    break;
                }
                value.push(c);
            }
            tokens.push(Token {
                text: value,
                quoted: true,
            });
        } else if c == '{' || c == '}' {
            chars.next();
            tokens.push(Token {
                text: c.to_string(),
                quoted: false,
            });
        } else {
            let mut value = String::new();
            while let Some((_, c)) =
                chars.next_if(|&(_, c)| !c.is_whitespace() && !matches!(c, '{' | '}' | '"'))
            {
                value.push(c);
            }
            tokens.push(Token {
                text: value,
                quoted: false,
            });
        }
    }
    tokens
}

/// Parse a scene file. Unknown syntax is skipped, never fatal: a stray `}`
/// closes nothing and an unterminated block ends at the end of the file.
pub fn parse(text: &str) -> Scene {
    let tokens = tokenize(text);
    let mut parser = Parser {
        tokens: &tokens,
        cursor: 0,
        length: 0.0,
        events: Vec::new(),
    };
    while parser.cursor < parser.tokens.len() {
        parser.container("");
        // A `}` with no block open.
        parser.cursor += 1;
    }
    let mut events = parser.events;
    events.sort_by(|a, b| a.start().total_cmp(&b.start()));
    Scene {
        length: parser.length,
        events,
    }
}

struct Parser<'a> {
    tokens: &'a [Token],
    cursor: usize,
    length: f64,
    events: Vec<Event>,
}

impl Parser<'_> {
    fn peek(&self) -> Option<&Token> {
        self.tokens.get(self.cursor)
    }

    fn next(&mut self) -> Option<&Token> {
        let token = self.tokens.get(self.cursor);
        self.cursor += 1;
        token
    }

    /// Consume a `{` if it is next.
    fn open(&mut self) -> bool {
        if self.peek().is_some_and(|t| t.is("{")) {
            self.cursor += 1;
            true
        } else {
            false
        }
    }

    /// Skip to just past the `}` closing a block whose `{` was consumed.
    fn skip_block(&mut self) {
        let mut depth = 1;
        while let Some(token) = self.next() {
            if token.is("{") {
                depth += 1;
            } else if token.is("}") {
                depth -= 1;
                if depth == 0 {
                    return;
                }
            }
        }
    }

    /// The file, an actor or a channel: read until the closing `}`, which is
    /// left for the caller.
    fn container(&mut self, actor: &str) {
        while let Some(token) = self.peek() {
            if token.is("}") {
                return;
            }
            let token = token.clone();
            self.cursor += 1;
            if token.is("{") {
                self.skip_block();
            } else if token.is("actor") || token.is("channel") {
                let name = match self.next() {
                    Some(name) if !name.is("{") && !name.is("}") => name.text.clone(),
                    _ => continue,
                };
                let actor = if token.is("actor") {
                    name.to_ascii_lowercase()
                } else {
                    actor.to_string()
                };
                if self.open() {
                    self.container(&actor);
                    self.cursor += 1;
                }
            } else if token.is("event") {
                let kind = self.next().map(|t| t.text.to_ascii_lowercase());
                // The event's name.
                self.next();
                if let Some(kind) = kind
                    && self.open()
                {
                    self.event(&kind, actor);
                }
            }
        }
    }

    /// An event body, just past its `{`, read through its closing `}`.
    fn event(&mut self, kind: &str, actor: &str) {
        let mut time: Option<(f64, f64)> = None;
        let mut param = String::new();
        let mut cctoken = String::new();
        let mut caption_disabled = false;
        while let Some(token) = self.next() {
            let token = token.clone();
            if token.is("}") {
                break;
            } else if token.is("{") {
                self.skip_block();
            } else if token.is("time") {
                let start = self.number();
                let end = self.number();
                time = start.zip(end);
            } else if token.is("param") {
                param = self.value();
            } else if token.is("cctoken") {
                cctoken = self.value();
            } else if token.is("cctype") {
                caption_disabled = self.value().eq_ignore_ascii_case("cc_disabled");
            }
        }
        let Some((start, end)) = time else { return };
        let valid = |t: f64| t.is_finite() && t.abs() <= MAX_TIME;
        if !valid(start) || !valid(end) {
            return;
        }
        let start = start.max(0.0);
        let latest = if end < 0.0 { start } else { end.max(start) };
        self.length = self.length.max(latest);
        match kind {
            "speak" if !param.trim().is_empty() => self.events.push(Event::Speak {
                actor: actor.to_string(),
                start,
                end: latest,
                script: param.trim().to_string(),
                cctoken: cctoken.trim().to_string(),
                caption_disabled,
            }),
            "firetrigger" => {
                if let Ok(trigger) = param.trim().parse::<i64>()
                    && (1..=MAX_TRIGGER).contains(&trigger)
                {
                    self.events.push(Event::FireTrigger {
                        start,
                        trigger: trigger as u8,
                    });
                }
            }
            _ => {}
        }
    }

    /// A value token, unless the next token is syntax.
    fn value(&mut self) -> String {
        match self.peek() {
            Some(t) if !t.is("{") && !t.is("}") => {
                let text = t.text.clone();
                self.cursor += 1;
                text
            }
            _ => String::new(),
        }
    }

    /// A number, consumed only when it is one.
    fn number(&mut self) -> Option<f64> {
        let value = self.peek()?.text.parse::<f64>().ok()?;
        self.cursor += 1;
        Some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"// Choreo version 1
event firetrigger "global"
{
  time 9.5 -1.000000
  param "16"
}
actor "Player"
{
  channel "monologue"
  {
    event speak "Ignored name"
    {
      time 0.04 1.89
      param "Player.Monologue.Furnace.Cellar"
      fixedlength
      cctype "cc_master"
      cctoken ""
      tags
      {
        "cue music" 0.392523
      }
    }
    event flexanimation "blink"
    {
      time 1.1 12.5
      param ""
      flexanimations samples_use_time defaultcurvetype=curve_catmullrom_normalize_x_to_curve_catmullrom_normalize_x
      {
        "blink"
        {
          0.0823 1.0000
        }
      }
      event_ramp
      {
        0.1 1.0 "curve_easein_to_curve_easeout"
      }
    }
  }
  event speak "Line.Two"
  {
    time 3 4
    param "Line.Two"
    cctype "cc_disabled"
    cctoken "Custom.Token"
  }
  channel "trigger"
  {
    event firetrigger "first"
    {
      time 1.5 -1
      param "2"
    }
    event firetrigger "out of range"
    {
      time 2 -1
      param "17"
    }
  }
}
mapname "C:\Users\someone\maps\x.bsp"
scalesettings
{
  "CChoreoView" "68"
}
fps 60
snap off
"#;

    #[test]
    fn keeps_speak_and_firetrigger_events_in_start_order() {
        let scene = parse(SAMPLE);
        assert_eq!(scene.length, 12.5, "every event counts towards the length");
        assert_eq!(
            scene.events,
            [
                Event::Speak {
                    actor: "player".into(),
                    start: 0.04,
                    end: 1.89,
                    script: "Player.Monologue.Furnace.Cellar".into(),
                    cctoken: String::new(),
                    caption_disabled: false,
                },
                Event::FireTrigger {
                    start: 1.5,
                    trigger: 2
                },
                Event::Speak {
                    actor: "player".into(),
                    start: 3.0,
                    end: 4.0,
                    script: "Line.Two".into(),
                    cctoken: "Custom.Token".into(),
                    caption_disabled: true,
                },
                Event::FireTrigger {
                    start: 9.5,
                    trigger: 16
                },
            ]
        );
    }

    #[test]
    fn broken_files_are_read_as_far_as_they_go() {
        let scene =
            parse("} actor \"a\" { event speak \"x\" { time 1 nan param \"x\" } event speak");
        assert!(scene.events.is_empty());
        assert_eq!(scene.length, 0.0);
        let scene =
            parse("actor \"a\" { channel \"c\" { event speak \"x\" { time 2 3 param \"X\" ");
        assert_eq!(
            scene.events.len(),
            1,
            "an unterminated block ends with the file"
        );
    }

    /// Every loose INFRA scene parses, and almost all of them say something.
    #[test]
    fn parses_every_infra_scene() {
        let dir =
            std::path::Path::new("/mnt/games/SteamLibrary/steamapps/common/infra/infra/scenes");
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        let (mut files, mut empty) = (0, Vec::new());
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().is_none_or(|e| e != "vcd") {
                continue;
            }
            files += 1;
            let text = String::from_utf8_lossy(&std::fs::read(&path).unwrap()).into_owned();
            let scene = parse(&text);
            let speaks = text.matches("event speak").count();
            let kept = scene
                .events
                .iter()
                .filter(|e| matches!(e, Event::Speak { .. }))
                .count();
            assert_eq!(kept, speaks, "{}", path.display());
            if scene.events.is_empty() {
                empty.push(path);
            }
        }
        assert!(files > 400, "found {files} scenes");
        assert!(empty.len() < 10, "scenes without events: {empty:?}");
    }
}
