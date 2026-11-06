//! `presence.rules`: server-name patterns, each mapped to a [`PlayerActivity`]. A pattern is a regex that must match
//! the whole server name, ignoring case; the first matching rule wins. A standalone server has no server name (like
//! Paper), so the rules are read and checked but never match there.

use regex::Regex;
use yaml_rust2::Yaml;

use crate::model::PlayerActivity;
use crate::settings::scalar;

pub struct Activities {
    rules: Vec<(Regex, PlayerActivity)>,
}

impl Activities {
    /// Rules as YAML maps (`pattern`, `game`, optional `mode`). A rule without a valid pattern or a game is skipped
    /// and reported to `warn`: a typo in a cosmetic setting never disables Friends.
    pub fn parse(rules: &[Yaml], warn: &mut dyn FnMut(String)) -> Activities {
        let mut out = Vec::new();
        for (i, rule) in rules.iter().enumerate() {
            let field = |name: &str| match rule {
                Yaml::Hash(map) => map
                    .get(&Yaml::String(name.to_owned()))
                    .and_then(scalar)
                    .map(|s| s.trim().to_owned())
                    .filter(|s| !s.is_empty()),
                _ => None,
            };
            let place = format!("presence.rules[{i}]");
            let (Some(pattern), Some(game)) = (field("pattern"), field("game")) else {
                warn(format!("{place} needs both a pattern and a game; skipped"));
                continue;
            };
            match Regex::new(&format!("(?i)^(?:{pattern})$")) {
                Ok(regex) => out.push((regex, PlayerActivity { game, mode: field("mode") })),
                Err(e) => {
                    let why = e.to_string();
                    let why = why.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("invalid").trim().to_owned();
                    warn(format!("{place}: '{pattern}' is not a valid regex ({why}); skipped"));
                }
            }
        }
        Activities { rules: out }
    }

    /// The activity on `server`, or none if there is no server or no rule matches.
    pub fn match_server(&self, server: Option<&str>) -> Option<PlayerActivity> {
        let server = server?;
        self.rules.iter().find(|(regex, _)| regex.is_match(server)).map(|(_, a)| a.clone())
    }

    pub fn len(&self) -> usize {
        self.rules.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }
}
