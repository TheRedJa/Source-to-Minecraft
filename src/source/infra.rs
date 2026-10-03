//! What INFRA's game code adds to a map's logic on its own.
//!
//! INFRA's `server.dll` creates two `game_text` entities in every map,
//! `@chapter_title_text` and `@chapter_subtitle_text`, and runs
//! `scripts/vscripts/chapter_titles.nut`. The script's table names, per map,
//! the chapter's title and subtitle tokens, whether the title shows on spawn
//! and after how long; its `DisplayChapterTitle` then sets both texts' colours,
//! heights and texts through `EntFire`, and on spawn queues `Display` and
//! `Kill`. Maps such as `infra_c4_m2_furnace` instead fire `Display` from their
//! own `logic_auto` on a level change.
//!
//! The table is read as data; the script's `EntFire` sequence is reproduced
//! here, as it is fixed text in the script and the mod runs no VScript.

/// One map's row of `CHAPTER_TITLES`.
#[derive(Debug, Clone, PartialEq)]
pub struct ChapterTitle {
    /// Localization tokens, `#` included, as the script writes them.
    pub title: String,
    pub subtitle: String,
    pub display_on_spawn: bool,
    /// Seconds after spawn.
    pub display_delay: f64,
}

pub const SCRIPT: &str = "scripts/vscripts/chapter_titles.nut";

/// The row for `map` in the script's `CHAPTER_TITLES` table, compared
/// ignoring ASCII case as the script compares `GetMapName()`.
pub fn chapter_title(script: &str, map: &str) -> Option<ChapterTitle> {
    let table = script.split("CHAPTER_TITLES").nth(1)?;
    let table = &table[..table.find(']')?];
    for row in table.split('{').skip(1) {
        let row = &row[..row.find('}')?];
        let field = |name: &str| -> Option<&str> {
            row.split(',').find_map(|pair| {
                let (key, value) = pair.split_once('=')?;
                (key.trim() == name).then(|| value.trim())
            })
        };
        let unquote = |value: &str| value.trim_matches('"').to_string();
        if !field("map").is_some_and(|m| unquote(m).eq_ignore_ascii_case(map)) {
            continue;
        }
        return Some(ChapterTitle {
            title: unquote(field("title_text")?),
            subtitle: unquote(field("subtitle_text")?),
            display_on_spawn: field("displayOnSpawn")? == "true",
            display_delay: field("displaydelay")?.parse().ok()?,
        });
    }
    None
}

/// An input the game queues as the map spawns, as `EntFire` does.
#[derive(Debug, Clone, PartialEq)]
pub struct EngineEvent {
    pub target: &'static str,
    pub input: &'static str,
    pub parameter: String,
    pub delay: f64,
}

pub const TITLE_ENTITY: &str = "@chapter_title_text";
pub const SUBTITLE_ENTITY: &str = "@chapter_subtitle_text";

/// The two `game_text` entities INFRA creates, as keyvalues. `server.dll`
/// holds the messages `chapter_title`/`chapter_subtitle` and the colours
/// `255 255 255` and `205 205 205` next to the names; the rest is Portal 2's
/// own `@chapter_title_text` and `@chapter_subtitle_text` (`sp_a1_wakeup`),
/// whose keyvalues carry those same strings and which INFRA's engine branch
/// shares.
pub fn chapter_entities() -> Vec<Vec<(String, String)>> {
    let text = |name: &str, message: &str, channel: &str, y: &str| {
        [
            ("classname", "game_text"),
            ("targetname", name),
            ("message", message),
            ("spawnflags", "1"),
            ("x", "-1"),
            ("y", y),
            ("effect", "2"),
            ("color", "255 255 255"),
            ("color2", "205 205 205"),
            ("fadein", ".06"),
            ("fadeout", "0.5"),
            ("holdtime", "5"),
            ("fxtime", ".5"),
            ("channel", channel),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
    };
    vec![
        text(TITLE_ENTITY, "chapter_title", "2", ".55"),
        text(SUBTITLE_ENTITY, "chapter_subtitle", "3", ".6"),
    ]
}

/// `DisplayChapterTitle` for one row: the `EntFire` calls, in order.
pub fn chapter_events(title: &ChapterTitle) -> Vec<EngineEvent> {
    let at = |target, input, parameter: &str, delay| EngineEvent {
        target,
        input,
        parameter: parameter.to_string(),
        delay,
    };
    let mut events = vec![
        at(TITLE_ENTITY, "SetTextColor", "210 210 210 128", 0.0),
        at(TITLE_ENTITY, "SetTextColor2", "50 90 116 255", 0.0),
        at(TITLE_ENTITY, "SetPosY", "0.32", 0.0),
        at(TITLE_ENTITY, "SetText", &title.title, 0.0),
        at(SUBTITLE_ENTITY, "SetTextColor", "210 210 210 128", 0.0),
        at(SUBTITLE_ENTITY, "SetTextColor2", "50 90 116 255", 0.0),
        at(SUBTITLE_ENTITY, "SetPosY", "0.35", 0.0),
        at(SUBTITLE_ENTITY, "settext", &title.subtitle, 0.0),
    ];
    if title.display_on_spawn {
        let delay = title.display_delay;
        events.extend([
            at(TITLE_ENTITY, "display", "", delay),
            at(TITLE_ENTITY, "kill", "", delay + 5.6),
            at(SUBTITLE_ENTITY, "display", "", delay),
            at(SUBTITLE_ENTITY, "kill", "", delay + 5.6),
        ]);
    }
    events
}

#[cfg(test)]
mod tests {
    use super::*;

    const SCRIPT_TEXT: &str = r##"CHAPTER_TITLES <-
[
	{ map = "infra_c3_m1_tunnel", title_text = "#infra_chapter_3_title", subtitle_text = "#infra_chapter_3_subtitle", displayOnSpawn = true,	displaydelay = 2.5 },
	{ map = "infra_c4_m2_furnace", title_text = "#infra_chapter_4_title", subtitle_text = "#infra_chapter_4_subtitle", displayOnSpawn = false, displaydelay = 2.5 },
]
function DisplayChapterTitle() { }"##;

    #[test]
    fn reads_the_row_of_the_map() {
        let row = chapter_title(SCRIPT_TEXT, "INFRA_C4_M2_FURNACE").unwrap();
        assert_eq!(row.title, "#infra_chapter_4_title");
        assert_eq!(row.subtitle, "#infra_chapter_4_subtitle");
        assert!(!row.display_on_spawn);
        assert_eq!(row.display_delay, 2.5);
        assert!(chapter_title(SCRIPT_TEXT, "infra_c6_m2_metro").is_none());
    }

    #[test]
    fn displays_on_spawn_only_when_the_row_says_so() {
        let shown = chapter_title(SCRIPT_TEXT, "infra_c3_m1_tunnel").unwrap();
        let events = chapter_events(&shown);
        assert_eq!(events.len(), 12);
        assert_eq!(events[8].input, "display");
        assert_eq!(events[9].delay, 2.5 + 5.6);
        let quiet = chapter_title(SCRIPT_TEXT, "infra_c4_m2_furnace").unwrap();
        assert_eq!(chapter_events(&quiet).len(), 8);
    }
}
