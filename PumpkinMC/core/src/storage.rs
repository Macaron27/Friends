//! Storage for SQLite or MySQL/MariaDB (Java's `Storage`): the same tables and SQL, so a database written by the
//! Paper, Velocity or BungeeCord plugin works here and back. Friendships are stored once per direction, so each side
//! keeps its own best/nickname flags and a player's list is one primary-key range scan.

pub mod mysql;
pub mod mysql_wire;
pub mod sqlite;

use std::fmt;

use uuid::Uuid;

use crate::model::{Friend, Millis, PlayerRow, Status};
pub use mysql_wire::Param;

#[derive(Debug)]
pub struct StorageError(pub String);

impl fmt::Display for StorageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for StorageError {}

/// A value read from a row, whatever the backend.
#[derive(Clone, Debug, PartialEq)]
pub enum Cell {
    Null,
    Int(i64),
    Text(String),
}

/// What [`Storage`] needs from a database connection.
pub trait Db: Send {
    /// A statement with parameters; returns the affected rows.
    fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64, StorageError>;
    fn query(&mut self, sql: &str, params: &[Param<'_>]) -> Result<Vec<Vec<Cell>>, StorageError>;
    /// A statement without parameters or results: DDL, transaction control.
    fn batch(&mut self, sql: &str) -> Result<(), StorageError>;
}

/// A player's settings, friends (with each friend's cached name, prefix and last-seen time) and ignore list
/// (id to name).
pub struct Loaded {
    pub notifications: bool,
    pub status: Status,
    pub friends: Vec<Friend>,
    pub ignored: Vec<(Uuid, String)>,
}

pub struct Storage {
    db: Box<dyn Db>,
    mysql: bool,
}

fn text(row: &[Cell], i: usize) -> Result<String, StorageError> {
    match row.get(i) {
        Some(Cell::Text(s)) => Ok(s.clone()),
        Some(Cell::Int(n)) => Ok(n.to_string()),
        other => Err(StorageError(format!("column {i}: expected text, got {other:?}"))),
    }
}

fn opt_text(row: &[Cell], i: usize) -> Result<Option<String>, StorageError> {
    match row.get(i) {
        Some(Cell::Null) => Ok(None),
        _ => text(row, i).map(Some),
    }
}

/// JDBC's getLong: NULL reads as 0.
fn long(row: &[Cell], i: usize) -> Result<i64, StorageError> {
    match row.get(i) {
        Some(Cell::Int(n)) => Ok(*n),
        Some(Cell::Null) => Ok(0),
        Some(Cell::Text(s)) => s.trim().parse().map_err(|_| StorageError(format!("column {i}: '{s}' is not a number"))),
        None => Err(StorageError(format!("column {i} missing"))),
    }
}

fn boolean(row: &[Cell], i: usize) -> Result<bool, StorageError> {
    Ok(long(row, i)? != 0)
}

fn uuid(row: &[Cell], i: usize) -> Result<Uuid, StorageError> {
    let s = text(row, i)?;
    Uuid::parse_str(&s).map_err(|_| StorageError(format!("'{s}' is not a UUID")))
}

impl Storage {
    pub fn new(db: Box<dyn Db>, mysql: bool) -> Result<Storage, StorageError> {
        let mut storage = Storage { db, mysql };
        storage.create_tables()?;
        Ok(storage)
    }

    fn create_tables(&mut self) -> Result<(), StorageError> {
        // MySQL has no CREATE INDEX IF NOT EXISTS, SQLite has no inline INDEX.
        let index = if self.mysql { ",\n  INDEX idx_friends_players_name (name_lower)" } else { "" };
        self.db.batch(&format!(
            "CREATE TABLE IF NOT EXISTS friends_players (
  uuid CHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(16) NOT NULL,
  name_lower VARCHAR(16) NOT NULL,
  prefix TEXT,
  last_seen BIGINT NOT NULL,
  notifications BOOLEAN NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'ONLINE'{index}
)"
        ))?;
        if !self.mysql {
            self.db.batch("CREATE INDEX IF NOT EXISTS idx_friends_players_name ON friends_players (name_lower)")?;
        }
        self.db.batch(
            "CREATE TABLE IF NOT EXISTS friends_friendships (
  player CHAR(36) NOT NULL,
  friend CHAR(36) NOT NULL,
  since BIGINT NOT NULL,
  best BOOLEAN NOT NULL DEFAULT 0,
  nickname VARCHAR(16),
  PRIMARY KEY (player, friend)
)",
        )?;
        self.db.batch(
            "CREATE TABLE IF NOT EXISTS friends_ignores (
  player CHAR(36) NOT NULL,
  ignored CHAR(36) NOT NULL,
  since BIGINT NOT NULL,
  PRIMARY KEY (player, ignored)
)",
        )
    }

    fn in_transaction<T>(
        &mut self,
        work: impl FnOnce(&mut dyn Db) -> Result<T, StorageError>,
    ) -> Result<T, StorageError> {
        self.db.batch(if self.mysql { "START TRANSACTION" } else { "BEGIN" })?;
        match work(self.db.as_mut()) {
            Ok(v) => {
                self.db.batch("COMMIT")?;
                Ok(v)
            }
            Err(e) => {
                let _ = self.db.batch("ROLLBACK");
                Err(e)
            }
        }
    }

    /// Upserts name/prefix and bumps last_seen; settings columns are left alone.
    pub fn save_player(
        &mut self,
        id: Uuid,
        name: &str,
        prefix: Option<&str>,
        seen: Millis,
    ) -> Result<(), StorageError> {
        let id = id.to_string();
        let lower = name.to_lowercase();
        if self.mysql {
            self.db.execute(
                "INSERT INTO friends_players (uuid, name, name_lower, prefix, last_seen) VALUES (?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower),
  prefix = VALUES(prefix), last_seen = VALUES(last_seen)",
                &[Param::Str(&id), Param::Str(name), Param::Str(&lower), prefix_param(prefix), Param::Int(seen)],
            )?;
            return Ok(());
        }
        // Old SQLite has no UPSERT: update, and insert if nothing was there.
        self.in_transaction(|db| {
            let updated = db.execute(
                "UPDATE friends_players SET name = ?, name_lower = ?, prefix = ?, last_seen = ? WHERE uuid = ?",
                &[Param::Str(name), Param::Str(&lower), prefix_param(prefix), Param::Int(seen), Param::Str(&id)],
            )?;
            if updated == 0 {
                db.execute(
                    "INSERT INTO friends_players (uuid, name, name_lower, prefix, last_seen) VALUES (?, ?, ?, ?, ?)",
                    &[Param::Str(&id), Param::Str(name), Param::Str(&lower), prefix_param(prefix), Param::Int(seen)],
                )?;
            }
            Ok(())
        })
    }

    pub fn touch(&mut self, id: Uuid, seen: Millis) -> Result<(), StorageError> {
        self.db.execute(
            "UPDATE friends_players SET last_seen = ? WHERE uuid = ?",
            &[Param::Int(seen), Param::Str(&id.to_string())],
        )?;
        Ok(())
    }

    /// Most recent holder of a name (names can be reused after a rename).
    pub fn player_by_name(&mut self, name: &str) -> Result<Option<PlayerRow>, StorageError> {
        let rows = self.db.query(
            "SELECT uuid, name, prefix, last_seen FROM friends_players WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1",
            &[Param::Str(&name.to_lowercase())],
        )?;
        rows.first().map(|r| player_row(r)).transpose()
    }

    pub fn player_by_id(&mut self, id: Uuid) -> Result<Option<PlayerRow>, StorageError> {
        let rows = self.db.query(
            "SELECT uuid, name, prefix, last_seen FROM friends_players WHERE uuid = ?",
            &[Param::Str(&id.to_string())],
        )?;
        rows.first().map(|r| player_row(r)).transpose()
    }

    /// Settings, the full friend list and the ignore list: three queries.
    pub fn load(&mut self, id: Uuid) -> Result<Loaded, StorageError> {
        let key = id.to_string();
        let mut notifications = true;
        let mut status = Status::Online;
        if let Some(row) = self
            .db
            .query("SELECT notifications, status FROM friends_players WHERE uuid = ?", &[Param::Str(&key)])?
            .first()
        {
            notifications = boolean(row, 0)?;
            let name = text(row, 1)?;
            status = Status::from_name(&name).ok_or_else(|| StorageError(format!("unknown status '{name}'")))?;
        }
        let mut friends = Vec::new();
        for row in self.db.query(
            "SELECT f.friend, f.since, f.best, f.nickname, p.name, p.prefix, p.last_seen
FROM friends_friendships f LEFT JOIN friends_players p ON p.uuid = f.friend
WHERE f.player = ?",
            &[Param::Str(&key)],
        )? {
            let friend = uuid(&row, 0)?;
            friends.push(Friend {
                id: friend,
                name: opt_text(&row, 4)?.unwrap_or_else(|| friend.to_string()),
                prefix: opt_text(&row, 5)?,
                since: long(&row, 1)?,
                best: boolean(&row, 2)?,
                nickname: opt_text(&row, 3)?,
                last_seen: long(&row, 6)?,
            });
        }
        let mut ignored = Vec::new();
        for row in self.db.query(
            "SELECT i.ignored, p.name FROM friends_ignores i LEFT JOIN friends_players p ON p.uuid = i.ignored
WHERE i.player = ?",
            &[Param::Str(&key)],
        )? {
            let other = uuid(&row, 0)?;
            ignored.push((other, opt_text(&row, 1)?.unwrap_or_else(|| other.to_string())));
        }
        Ok(Loaded { notifications, status, friends, ignored })
    }

    pub fn count_friends(&mut self, id: Uuid) -> Result<usize, StorageError> {
        let rows = self
            .db
            .query("SELECT COUNT(*) FROM friends_friendships WHERE player = ?", &[Param::Str(&id.to_string())])?;
        let n = rows.first().map(|r| long(r, 0)).transpose()?.unwrap_or(0);
        Ok(usize::try_from(n).unwrap_or(0))
    }

    pub fn add_friendship(&mut self, a: Uuid, b: Uuid, since: Millis) -> Result<(), StorageError> {
        let sql = format!(
            "{} INTO friends_friendships (player, friend, since) VALUES (?, ?, ?)",
            if self.mysql { "INSERT IGNORE" } else { "INSERT OR IGNORE" }
        );
        let (a, b) = (a.to_string(), b.to_string());
        self.in_transaction(|db| {
            for (player, friend) in [(&a, &b), (&b, &a)] {
                db.execute(&sql, &[Param::Str(player), Param::Str(friend), Param::Int(since)])?;
            }
            Ok(())
        })
    }

    /// Removes both directions of each friendship in one transaction.
    pub fn remove_friendships(&mut self, player: Uuid, friends: &[Uuid]) -> Result<(), StorageError> {
        let player = player.to_string();
        self.in_transaction(|db| {
            for friend in friends {
                let friend = friend.to_string();
                db.execute(
                    "DELETE FROM friends_friendships WHERE (player = ? AND friend = ?) OR (player = ? AND friend = ?)",
                    &[Param::Str(&player), Param::Str(&friend), Param::Str(&friend), Param::Str(&player)],
                )?;
            }
            Ok(())
        })
    }

    pub fn set_best(&mut self, player: Uuid, friend: Uuid, best: bool) -> Result<(), StorageError> {
        self.db.execute(
            "UPDATE friends_friendships SET best = ? WHERE player = ? AND friend = ?",
            &[Param::Bool(best), Param::Str(&player.to_string()), Param::Str(&friend.to_string())],
        )?;
        Ok(())
    }

    pub fn set_nickname(&mut self, player: Uuid, friend: Uuid, nickname: Option<&str>) -> Result<(), StorageError> {
        self.db.execute(
            "UPDATE friends_friendships SET nickname = ? WHERE player = ? AND friend = ?",
            &[
                nickname.map_or(Param::Null, Param::Str),
                Param::Str(&player.to_string()),
                Param::Str(&friend.to_string()),
            ],
        )?;
        Ok(())
    }

    pub fn set_notifications(&mut self, id: Uuid, enabled: bool) -> Result<(), StorageError> {
        self.db.execute(
            "UPDATE friends_players SET notifications = ? WHERE uuid = ?",
            &[Param::Bool(enabled), Param::Str(&id.to_string())],
        )?;
        Ok(())
    }

    pub fn set_status(&mut self, id: Uuid, status: Status) -> Result<(), StorageError> {
        self.db.execute(
            "UPDATE friends_players SET status = ? WHERE uuid = ?",
            &[Param::Str(status.name()), Param::Str(&id.to_string())],
        )?;
        Ok(())
    }

    pub fn add_ignore(&mut self, player: Uuid, ignored: Uuid, since: Millis) -> Result<(), StorageError> {
        let sql = format!(
            "{} INTO friends_ignores (player, ignored, since) VALUES (?, ?, ?)",
            if self.mysql { "INSERT IGNORE" } else { "INSERT OR IGNORE" }
        );
        self.db
            .execute(&sql, &[Param::Str(&player.to_string()), Param::Str(&ignored.to_string()), Param::Int(since)])?;
        Ok(())
    }

    pub fn remove_ignore(&mut self, player: Uuid, ignored: Uuid) -> Result<(), StorageError> {
        self.db.execute(
            "DELETE FROM friends_ignores WHERE player = ? AND ignored = ?",
            &[Param::Str(&player.to_string()), Param::Str(&ignored.to_string())],
        )?;
        Ok(())
    }

    /// Whether `player` ignores `other`.
    pub fn ignores(&mut self, player: Uuid, other: Uuid) -> Result<bool, StorageError> {
        let rows = self.db.query(
            "SELECT 1 FROM friends_ignores WHERE player = ? AND ignored = ?",
            &[Param::Str(&player.to_string()), Param::Str(&other.to_string())],
        )?;
        Ok(!rows.is_empty())
    }
}

fn prefix_param(p: Option<&str>) -> Param<'_> {
    p.map_or(Param::Null, Param::Str)
}

fn player_row(row: &[Cell]) -> Result<PlayerRow, StorageError> {
    Ok(PlayerRow { id: uuid(row, 0)?, name: text(row, 1)?, prefix: opt_text(row, 2)?, last_seen: long(row, 3)? })
}
