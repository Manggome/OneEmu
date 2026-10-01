//! Per-game tuning table (`quirks.toml`).
//!
//! The built-in table is compiled into the core; a `quirks.toml` in `<system>/wipi/` is read first, so
//! users can add or override entries without a new build. An entry matches when any of its `aid`,
//! `pid` / `id` or `title` fields equals the loaded game's. Every field is optional; the matching
//! "auto" core options fall back to these values, explicit option values win.

use std::path::Path;

use serde::Deserialize;

pub const BUILTIN: &str = include_str!("../quirks.toml");

#[derive(Deserialize, Default, Clone, Debug)]
pub struct Quirk {
    pub name: Option<String>,
    pub aid: Option<String>,
    pub pid: Option<String>,
    pub id: Option<String>,
    pub title: Option<String>,
    pub carrier: Option<String>,
    /// "standard" | "numpad"
    pub pad_profile: Option<String>,
    /// Game-time speed in percent (100 = real phone speed).
    pub speed: Option<u32>,
    pub cpu_budget_ms: Option<u32>,
    /// "jit" | "interpreter": games the JIT gets wrong run on the interpreter.
    pub cpu: Option<String>,
    /// "YYYY-MM-DD": the date the game sees (for date-locked events / broken date APIs).
    pub fixed_date: Option<String>,
    pub midi_polyphony: Option<usize>,
    pub midi_volume: Option<u32>,
    pub pcm_volume: Option<u32>,
    pub key_repeat_ms: Option<u32>,
    pub note: Option<String>,
}

#[derive(Deserialize, Default)]
struct QuirkFile {
    #[serde(default)]
    game: Vec<Quirk>,
}

fn parse(text: &str, source: &str) -> Vec<Quirk> {
    match toml::from_str::<QuirkFile>(text) {
        Ok(f) => f.game,
        Err(e) => {
            tracing::warn!("{source}: invalid quirks table: {e}");
            Vec::new()
        }
    }
}

fn eq(a: &Option<String>, b: Option<&str>) -> bool {
    matches!((a.as_deref(), b), (Some(x), Some(y)) if !x.is_empty() && x.eq_ignore_ascii_case(y.trim()))
}

pub fn find(user_file: Option<&Path>, aid: Option<&str>, id: Option<&str>, title: Option<&str>) -> Option<Quirk> {
    let mut tables = Vec::new();
    if let Some(path) = user_file
        && let Ok(text) = std::fs::read_to_string(path)
    {
        tables.push(parse(&text, &path.display().to_string()));
    }
    tables.push(parse(BUILTIN, "built-in quirks.toml"));

    tables.into_iter().flatten().find(|q| {
        eq(&q.aid, aid) || eq(&q.pid, id) || eq(&q.id, id) || eq(&q.title, title)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_table_parses() {
        let _ = parse(BUILTIN, "builtin");
    }

    #[test]
    fn matching() {
        let table = parse(
            r#"
[[game]]
title = "테스트 게임"
pad_profile = "numpad"
speed = 90

[[game]]
aid = "ABC123"
fixed_date = "2010-05-01"
"#,
            "test",
        );
        assert_eq!(table.len(), 2);
        assert!(eq(&table[0].title, Some("테스트 게임")));
        assert!(eq(&table[1].aid, Some("abc123")));
        assert!(!eq(&table[1].pid, Some("abc123")));
    }
}
