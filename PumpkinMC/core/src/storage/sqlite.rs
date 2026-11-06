//! SQLite through rusqlite (SQLite compiled from source, so the same code runs in Pumpkin's WASI sandbox).

use std::path::Path;

use rusqlite::types::{Value, ValueRef};
use rusqlite::{Connection, params_from_iter};

use super::{Cell, Db, Param, Storage, StorageError};

struct Sqlite(Connection);

fn error(e: rusqlite::Error) -> StorageError {
    StorageError(e.to_string())
}

fn values(params: &[Param<'_>]) -> Vec<Value> {
    params
        .iter()
        .map(|p| match p {
            Param::Null => Value::Null,
            Param::Int(i) => Value::Integer(*i),
            Param::Str(s) => Value::Text((*s).to_owned()),
            // Like sqlite-jdbc's setBoolean.
            Param::Bool(b) => Value::Integer(i64::from(*b)),
        })
        .collect()
}

impl Db for Sqlite {
    fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64, StorageError> {
        let mut stmt = self.0.prepare_cached(sql).map_err(error)?;
        let n = stmt.execute(params_from_iter(values(params))).map_err(error)?;
        Ok(n as u64)
    }

    fn query(&mut self, sql: &str, params: &[Param<'_>]) -> Result<Vec<Vec<Cell>>, StorageError> {
        let mut stmt = self.0.prepare_cached(sql).map_err(error)?;
        let columns = stmt.column_count();
        let mut rows = stmt.query(params_from_iter(values(params))).map_err(error)?;
        let mut out = Vec::new();
        while let Some(row) = rows.next().map_err(error)? {
            let mut cells = Vec::with_capacity(columns);
            for i in 0..columns {
                cells.push(match row.get_ref(i).map_err(error)? {
                    ValueRef::Null => Cell::Null,
                    ValueRef::Integer(n) => Cell::Int(n),
                    ValueRef::Real(f) => Cell::Int(f as i64),
                    ValueRef::Text(b) | ValueRef::Blob(b) => Cell::Text(String::from_utf8_lossy(b).into_owned()),
                });
            }
            out.push(cells);
        }
        Ok(out)
    }

    fn batch(&mut self, sql: &str) -> Result<(), StorageError> {
        self.0.execute_batch(sql).map_err(error)
    }
}

impl Storage {
    /// Opens (or creates) the database file. WAL with synchronous=NORMAL like the Java plugin: one fsync per
    /// checkpoint instead of per commit, still crash-safe.
    pub fn sqlite(file: &Path) -> Result<Storage, StorageError> {
        let open = || -> rusqlite::Result<Connection> {
            let c = Connection::open(file)?;
            // WASI has no shared memory for WAL's index: with exclusive locking SQLite keeps it in the heap. Only
            // this server opens the file, so nothing else is locked out (a database copied from a Paper server in
            // WAL mode opens the same way).
            #[cfg(target_os = "wasi")]
            c.execute_batch("PRAGMA locking_mode=EXCLUSIVE")?;
            c.query_row("PRAGMA journal_mode=WAL", [], |_| Ok(()))?;
            c.execute_batch("PRAGMA synchronous=NORMAL")?;
            Ok(c)
        };
        let conn = open().map_err(|e| StorageError(format!("SQLite could not open {} ({e})", file.display())))?;
        Storage::new(Box::new(Sqlite(conn)), false)
    }
}
