//! The plugin's config.yml, the same file on every platform (Java's `Settings`).

use std::time::Duration;

use yaml_rust2::{Yaml, YamlLoader};

use crate::activities::Activities;

/// The bundled config.yml: the Java plugin's own file, so every platform ships the same defaults and comments.
pub const BUNDLED_CONFIG: &str = include_str!("../../../core/src/main/resources/config.yml");

pub struct Mysql {
    pub host: String,
    pub port: i32,
    pub database: String,
    pub user: String,
    pub password: String,
    pub ssl: bool,
}

pub struct Redis {
    pub enabled: bool,
    pub host: String,
    pub port: i32,
    pub password: String,
    pub database: i32,
    pub ssl: bool,
    pub proxy_id: String,
    pub namespace: String,
}

/// `private-messages.rate-limit`: at most `messages` private messages per sender within any `window`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RateLimit {
    pub messages: usize,
    pub window: Duration,
}

impl RateLimit {
    pub const NONE: RateLimit = RateLimit { messages: 0, window: Duration::ZERO };

    pub fn enabled(&self) -> bool {
        self.messages > 0 && !self.window.is_zero()
    }
}

impl std::fmt::Display for RateLimit {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.enabled() { write!(f, "{} per {}s", self.messages, self.window.as_secs()) } else { f.write_str("off") }
    }
}

pub struct Settings {
    pub storage: String,
    pub sqlite_file: String,
    pub mysql: Mysql,
    pub redis: Redis,
    pub request_expiry: Duration,
    pub max_friends: usize,
    pub private_messages: bool,
    pub rate_limit: RateLimit,
    pub activities: Activities,
    pub warnings: Vec<String>,
}

/// Dotted-path lookups with defaults, as Bukkit's `FileConfiguration` does them.
pub trait Source {
    fn string(&self, path: &str, def: &str) -> String;
    fn number(&self, path: &str, def: i32) -> i32;
    fn flag(&self, path: &str, def: bool) -> bool;
    /// The raw YAML list at `path` (e.g. a list of maps), or empty if absent.
    fn list(&self, path: &str) -> Vec<Yaml>;
}

impl Settings {
    pub fn read(s: &dyn Source) -> Result<Settings, String> {
        let storage = s.string("storage.type", "sqlite").to_lowercase();
        if storage != "sqlite" && storage != "mysql" {
            return Err(format!("storage.type must be sqlite or mysql, not '{storage}'"));
        }
        let mut warnings = Vec::new();
        let mut rules = Vec::new();
        for rule in s.list("presence.rules") {
            if matches!(rule, Yaml::Hash(_)) {
                rules.push(rule);
            } else {
                warnings.push(format!(
                    "presence.rules: '{}' is not a pattern/game/mode entry; skipped",
                    scalar(&rule).unwrap_or_default()
                ));
            }
        }
        let activities = Activities::parse(&rules, &mut |w| warnings.push(w));
        let messages = s.number("private-messages.rate-limit.messages", 3);
        let seconds = s.number("private-messages.rate-limit.seconds", 3);
        let rate_limit = match (usize::try_from(messages), u64::try_from(seconds)) {
            (Ok(m), Ok(secs)) if m > 0 && secs > 0 => RateLimit { messages: m, window: Duration::from_secs(secs) },
            _ => RateLimit::NONE,
        };
        Ok(Settings {
            storage,
            sqlite_file: s.string("sqlite.file", "friends.db"),
            mysql: Mysql {
                host: s.string("mysql.host", "localhost"),
                port: s.number("mysql.port", 3306),
                database: s.string("mysql.database", "friends"),
                user: s.string("mysql.user", "root"),
                password: s.string("mysql.password", ""),
                ssl: s.flag("mysql.use-ssl", false),
            },
            redis: Redis {
                enabled: s.flag("redis.enabled", false),
                host: s.string("redis.host", "localhost"),
                port: s.number("redis.port", 6379),
                password: s.string("redis.password", ""),
                database: s.number("redis.database", 0),
                ssl: s.flag("redis.ssl", false),
                proxy_id: s.string("redis.proxy-id", ""),
                namespace: s.string("redis.namespace", "friends"),
            },
            request_expiry: Duration::from_secs(
                60 * u64::from(s.number("request-expiry-minutes", 5).max(1).unsigned_abs()),
            ),
            max_friends: s.number("max-friends", 5000).max(1).unsigned_abs() as usize,
            private_messages: s.flag("private-messages.enabled", true),
            rate_limit,
            activities,
            warnings,
        })
    }

    pub fn uses_mysql(&self) -> bool {
        self.storage == "mysql"
    }
}

/// A scalar's text, like Java's `toString()` of what SnakeYAML returns (numbers and booleans included).
pub fn scalar(value: &Yaml) -> Option<String> {
    match value {
        Yaml::String(s) | Yaml::Real(s) => Some(s.clone()),
        Yaml::Integer(i) => Some(i.to_string()),
        Yaml::Boolean(b) => Some(b.to_string()),
        _ => None,
    }
}

/// config.yml parsed with yaml-rust2.
pub struct YamlSource {
    root: Yaml,
}

impl YamlSource {
    pub fn parse(text: &str) -> Result<YamlSource, String> {
        let mut docs = YamlLoader::load_from_str(text).map_err(|e| format!("config.yml is not valid YAML: {e}"))?;
        Ok(YamlSource { root: if docs.is_empty() { Yaml::Null } else { docs.swap_remove(0) } })
    }

    fn get(&self, path: &str) -> Option<&Yaml> {
        let mut node = &self.root;
        for key in path.split('.') {
            let Yaml::Hash(map) = node else { return None };
            node = map.get(&Yaml::String(key.to_owned()))?;
        }
        Some(node)
    }
}

impl Source for YamlSource {
    fn string(&self, path: &str, def: &str) -> String {
        self.get(path).and_then(scalar).unwrap_or_else(|| def.to_owned())
    }

    /// Like Bukkit's `getInt`: any number, truncated to an int; anything else gives the default.
    fn number(&self, path: &str, def: i32) -> i32 {
        match self.get(path) {
            Some(Yaml::Integer(i)) => *i as i32,
            Some(Yaml::Real(r)) => r.parse::<f64>().map_or(def, |f| f as i32),
            _ => def,
        }
    }

    fn flag(&self, path: &str, def: bool) -> bool {
        match self.get(path) {
            Some(Yaml::Boolean(b)) => *b,
            _ => def,
        }
    }

    fn list(&self, path: &str) -> Vec<Yaml> {
        match self.get(path) {
            Some(Yaml::Array(items)) => items.clone(),
            _ => Vec::new(),
        }
    }
}
