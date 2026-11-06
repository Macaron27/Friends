//! MySQL/MariaDB through [`super::mysql_wire`]: one connection (all calls come from the plugin's single thread),
//! reopened after it breaks. Like HikariCP in the Java plugin, a connection idle for more than 500 ms is checked
//! (a ping) before use, so a restarted server or a connection dropped after `wait_timeout` goes unnoticed.

use std::time::{Duration, Instant};

use super::mysql_wire::{self, Conn, Opts, Value};
use super::{Cell, Db, Param, Storage, StorageError};

const PING_AFTER_IDLE: Duration = Duration::from_millis(500);

struct Mysql {
    opts: Opts,
    conn: Option<Conn>,
    used: Instant,
}

impl Mysql {
    fn conn(&mut self) -> Result<&mut Conn, StorageError> {
        if self.used.elapsed() >= PING_AFTER_IDLE
            && let Some(c) = self.conn.as_mut()
            && c.ping().is_err()
        {
            self.conn = None;
        }
        self.used = Instant::now();
        if self.conn.is_none() {
            self.conn = Some(Conn::connect(&self.opts).map_err(|e| StorageError(e.to_string()))?);
        }
        self.conn.as_mut().ok_or_else(|| StorageError("no connection".into()))
    }

    fn run<T>(&mut self, f: impl FnOnce(&mut Conn) -> Result<T, mysql_wire::Error>) -> Result<T, StorageError> {
        let result = f(self.conn()?);
        if let Err(e) = &result {
            // A server error leaves the connection usable; anything else may have broken it.
            if !matches!(e, mysql_wire::Error::Server { .. }) {
                self.conn = None;
            }
        }
        result.map_err(|e| StorageError(e.to_string()))
    }
}

fn cell(v: Value) -> Cell {
    match v {
        Value::NULL => Cell::Null,
        Value::Int(i) => Cell::Int(i),
        Value::UInt(u) => Cell::Int(i64::try_from(u).unwrap_or(i64::MAX)),
        Value::Bytes(b) => Cell::Text(String::from_utf8_lossy(&b).into_owned()),
        Value::Float(f) => Cell::Int(f as i64),
        Value::Double(f) => Cell::Int(f as i64),
        other => Cell::Text(format!("{other:?}")),
    }
}

impl Db for Mysql {
    fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64, StorageError> {
        self.run(|c| c.exec(sql, params))
    }

    fn query(&mut self, sql: &str, params: &[Param<'_>]) -> Result<Vec<Vec<Cell>>, StorageError> {
        let rows = self.run(|c| c.query(sql, params))?;
        Ok(rows.into_iter().map(|r| r.into_iter().map(cell).collect()).collect())
    }

    fn batch(&mut self, sql: &str) -> Result<(), StorageError> {
        self.run(|c| c.query_drop(sql))
    }
}

impl Storage {
    pub fn mysql(
        host: &str,
        port: i32,
        database: &str,
        user: &str,
        password: &str,
        ssl: bool,
    ) -> Result<Storage, StorageError> {
        if ssl {
            return Err(StorageError(
                "mysql.use-ssl: true is not supported on Pumpkin yet (no TLS in the plugin sandbox); \
                 set it to false on a trusted network, or use storage.type: sqlite"
                    .into(),
            ));
        }
        let port = u16::try_from(port).map_err(|_| StorageError(format!("mysql.port {port} is out of range")))?;
        let opts = Opts {
            host: host.to_owned(),
            port,
            user: user.to_owned(),
            password: password.to_owned(),
            database: database.to_owned(),
            connect_timeout: Duration::from_secs(10),
            io_timeout: Duration::from_secs(30),
        };
        let conn = Conn::connect(&opts).map_err(|e| StorageError(format!("MySQL at {host}:{port}: {e}")))?;
        Storage::new(Box::new(Mysql { opts, conn: Some(conn), used: Instant::now() }), true)
    }
}
