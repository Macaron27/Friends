//! A small synchronous MySQL/MariaDB client: the `mysql` crate doesn't build for `wasm32-wasip2`, but its protocol
//! crate `mysql_common` does, and `std::net::TcpStream` works there. Covers what Friends needs: password auth
//! (`mysql_native_password`, `caching_sha2_password` including the RSA full-auth path), text queries for DDL and
//! transactions, and cached prepared statements with binary rows. No TLS.

use std::collections::HashMap;
use std::fmt;
use std::io;
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::Arc;
use std::time::Duration;

use mysql_common::constants::{CapabilityFlags, Command};
use mysql_common::io::{ParseBuf, ReadMysqlExt};
use mysql_common::packets::{
    AuthPlugin, AuthSwitchRequest, Column, ComStmtExecuteRequestBuilder, CommonOkPacket, ErrPacket, HandshakePacket,
    HandshakeResponse, OkPacketDeserializer, StmtPacket,
};
use mysql_common::proto::codec::error::PacketCodecError;
use mysql_common::proto::sync_framed::MySyncFramed;
use mysql_common::proto::{Binary, MySerialize};
use mysql_common::row::RowDeserializer;
use mysql_common::value::ServerSide;
pub use mysql_common::value::Value;

const MAX_PACKET: u32 = 16 * 1024 * 1024;

pub struct Opts {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub password: String,
    pub database: String,
    pub connect_timeout: Duration,
    pub io_timeout: Duration,
}

pub enum Param<'a> {
    Null,
    Int(i64),
    Str(&'a str),
    Bool(bool),
}

#[derive(Debug)]
pub enum Error {
    Io(io::Error),
    Server { code: u16, state: String, message: String },
    Protocol(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Io(e) => write!(f, "I/O error: {e}"),
            Error::Server { code, state, message } => write!(f, "MySQL error {code} ({state}): {message}"),
            Error::Protocol(m) => write!(f, "MySQL protocol error: {m}"),
        }
    }
}

impl std::error::Error for Error {}

impl From<io::Error> for Error {
    fn from(e: io::Error) -> Self {
        Error::Io(e)
    }
}

impl From<PacketCodecError> for Error {
    fn from(e: PacketCodecError) -> Self {
        match e {
            PacketCodecError::Io(e) => Error::Io(e),
            other => Error::Protocol(other.to_string()),
        }
    }
}

fn protocol(what: &str) -> Error {
    Error::Protocol(what.to_owned())
}

struct Stmt {
    id: u32,
    params: u16,
}

pub struct Conn {
    stream: MySyncFramed<TcpStream>,
    caps: CapabilityFlags,
    stmts: HashMap<String, Stmt>,
    last_affected: u64,
}

impl Conn {
    pub fn connect(opts: &Opts) -> Result<Conn, Error> {
        let mut last = None;
        let mut tcp = None;
        for addr in (opts.host.as_str(), opts.port).to_socket_addrs()? {
            match TcpStream::connect_timeout(&addr, opts.connect_timeout) {
                Ok(s) => {
                    tcp = Some(s);
                    break;
                }
                Err(e) => last = Some(e),
            }
        }
        let tcp = match (tcp, last) {
            (Some(s), _) => s,
            (None, Some(e)) => return Err(Error::Io(e)),
            (None, None) => return Err(protocol("the host name resolved to no address")),
        };
        // Best effort: a target without socket timeouts still works, it just waits longer on a dead server.
        let _ = tcp.set_read_timeout(Some(opts.io_timeout));
        let _ = tcp.set_write_timeout(Some(opts.io_timeout));
        let _ = tcp.set_nodelay(true);
        let mut conn = Conn {
            stream: MySyncFramed::new(tcp),
            caps: CapabilityFlags::empty(),
            stmts: HashMap::new(),
            last_affected: 0,
        };
        conn.handshake(opts)?;
        Ok(conn)
    }

    fn handshake(&mut self, opts: &Opts) -> Result<(), Error> {
        let payload = self.read_packet()?;
        let hs = ParseBuf(&payload).parse::<HandshakePacket>(())?;
        if hs.protocol_version() != 10 {
            return Err(Error::Protocol(format!("unsupported protocol version {}", hs.protocol_version())));
        }
        if !hs.capabilities().contains(CapabilityFlags::CLIENT_PROTOCOL_41) {
            return Err(protocol("the server doesn't speak protocol 4.1"));
        }
        let mut wanted = CapabilityFlags::CLIENT_PROTOCOL_41
            | CapabilityFlags::CLIENT_SECURE_CONNECTION
            | CapabilityFlags::CLIENT_LONG_PASSWORD
            | CapabilityFlags::CLIENT_LONG_FLAG
            | CapabilityFlags::CLIENT_TRANSACTIONS
            | CapabilityFlags::CLIENT_MULTI_RESULTS
            | CapabilityFlags::CLIENT_PLUGIN_AUTH;
        if !opts.database.is_empty() {
            wanted |= CapabilityFlags::CLIENT_CONNECT_WITH_DB;
        }
        self.caps = hs.capabilities() & wanted;

        // The scramble is 20 bytes (plus a terminator the parser may leave): pad or trim like the server does.
        let mut nonce = hs.scramble_1_ref().to_vec();
        nonce.extend_from_slice(hs.scramble_2_ref().unwrap_or(&[]));
        nonce.resize(20, 0);
        let plugin = match hs.auth_plugin() {
            Some(AuthPlugin::CachingSha2Password) => AuthPlugin::CachingSha2Password,
            _ => AuthPlugin::MysqlNativePassword,
        };
        let pass = password(opts);
        let data = plugin.gen_data(pass, &nonce).map(|d| d.into_owned());
        let response = HandshakeResponse::new(
            data.as_deref(),
            hs.server_version_parsed().unwrap_or((0, 0, 0)),
            Some(opts.user.as_bytes()),
            (!opts.database.is_empty()).then_some(opts.database.as_bytes()),
            Some(plugin.clone()),
            self.caps,
            None,
            MAX_PACKET,
        );
        self.write_struct(&response)?;
        self.continue_auth(plugin, nonce, pass, false)
    }

    fn continue_auth(
        &mut self,
        plugin: AuthPlugin<'static>,
        nonce: Vec<u8>,
        pass: Option<&str>,
        switched: bool,
    ) -> Result<(), Error> {
        let payload = self.read_packet()?;
        match (payload.first(), &plugin) {
            (Some(0x00), _) => Ok(()),
            (Some(0xfe), _) if !switched => self.auth_switch(&payload, pass),
            (Some(0x01), AuthPlugin::CachingSha2Password) => match payload.get(1) {
                Some(0x03) => self.expect_ok(), // fast auth: the server had the password hash cached
                Some(0x04) => {
                    // Full auth over a plain connection: the password, NUL-terminated and XORed with the nonce,
                    // encrypted with the server's RSA public key.
                    self.write_raw(&[0x02])?;
                    let key = self.read_packet()?;
                    let key = key.get(1..).ok_or_else(|| protocol("empty public key packet"))?;
                    let mut secret = pass.unwrap_or("").as_bytes().to_vec();
                    secret.push(0);
                    for (i, b) in secret.iter_mut().enumerate() {
                        *b ^= nonce[i % nonce.len()];
                    }
                    let encrypted = mysql_common::crypto::encrypt(&secret, key);
                    self.write_raw(&encrypted)?;
                    self.expect_ok()
                }
                _ => Err(protocol("unexpected caching_sha2_password packet")),
            },
            _ => Err(protocol("unexpected packet during authentication")),
        }
    }

    fn auth_switch(&mut self, payload: &[u8], pass: Option<&str>) -> Result<(), Error> {
        let request = ParseBuf(payload).parse::<AuthSwitchRequest>(())?;
        let plugin = match request.auth_plugin() {
            AuthPlugin::MysqlNativePassword => AuthPlugin::MysqlNativePassword,
            AuthPlugin::CachingSha2Password => AuthPlugin::CachingSha2Password,
            other => {
                return Err(Error::Protocol(format!(
                    "unsupported authentication plugin {}",
                    String::from_utf8_lossy(other.as_bytes())
                )));
            }
        };
        let nonce = request.plugin_data().to_vec();
        match plugin.gen_data(pass, &nonce) {
            Some(data) => self.write_struct(&data.into_owned())?,
            None => self.write_raw(&[])?,
        }
        self.continue_auth(plugin, nonce, pass, true)
    }

    /// Text protocol (COM_QUERY): DDL, START TRANSACTION / COMMIT / ROLLBACK. Discards any result set.
    pub fn query_drop(&mut self, sql: &str) -> Result<(), Error> {
        self.command(Command::COM_QUERY, sql.as_bytes())?;
        if self.result_header()?.is_some() {
            while self.next_row()?.is_some() {}
        }
        Ok(())
    }

    /// Prepared statement (prepared once per SQL string per connection). Returns the affected rows.
    pub fn exec(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64, Error> {
        self.execute(sql, params)?;
        match self.result_header()? {
            None => Ok(self.last_affected),
            Some(_) => {
                while self.next_row()?.is_some() {}
                Ok(0)
            }
        }
    }

    /// Prepared statement returning its rows, each value in column order.
    pub fn query(&mut self, sql: &str, params: &[Param<'_>]) -> Result<Vec<Vec<Value>>, Error> {
        self.execute(sql, params)?;
        let Some(columns) = self.result_header()? else { return Ok(Vec::new()) };
        let mut rows = Vec::new();
        while let Some(packet) = self.next_row()? {
            let row = ParseBuf(&packet).parse::<RowDeserializer<ServerSide, Binary>>(columns.clone())?;
            rows.push(row.into_inner().unwrap());
        }
        Ok(rows)
    }

    pub fn ping(&mut self) -> Result<(), Error> {
        self.command(Command::COM_PING, &[])?;
        self.expect_ok()
    }

    fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<(), Error> {
        let (id, expected) = self.prepare(sql)?;
        if usize::from(expected) != params.len() {
            return Err(Error::Protocol(format!("{sql}: {expected} parameters expected, {} given", params.len())));
        }
        let values: Vec<Value> = params
            .iter()
            .map(|p| match p {
                Param::Null => Value::NULL,
                Param::Int(i) => Value::Int(*i),
                Param::Str(s) => Value::Bytes(s.as_bytes().to_vec()),
                Param::Bool(b) => Value::Int(i64::from(*b)),
            })
            .collect();
        let (request, as_long_data) = ComStmtExecuteRequestBuilder::new(id).build(&values);
        if as_long_data {
            return Err(protocol("a parameter is too large to send inline"));
        }
        let mut buf = Vec::new();
        request.serialize(&mut buf);
        self.stream.codec_mut().reset_seq_id();
        self.write_raw(&buf)
    }

    fn prepare(&mut self, sql: &str) -> Result<(u32, u16), Error> {
        if let Some(s) = self.stmts.get(sql) {
            return Ok((s.id, s.params));
        }
        self.command(Command::COM_STMT_PREPARE, sql.as_bytes())?;
        let payload = self.read_packet()?;
        let stmt = ParseBuf(&payload).parse::<StmtPacket>(())?;
        // Parameter and column definitions follow, each list ended by an EOF packet; execute resends the columns.
        for count in [stmt.num_params(), stmt.num_columns()] {
            if count > 0 {
                for _ in 0..count {
                    self.read_packet()?;
                }
                self.read_packet()?;
            }
        }
        self.stmts.insert(sql.to_owned(), Stmt { id: stmt.statement_id(), params: stmt.num_params() });
        Ok((stmt.statement_id(), stmt.num_params()))
    }

    /// After a command: `None` for an OK packet (affected rows kept in `last_affected`), else the result set's
    /// columns, with the stream positioned at its first row.
    fn result_header(&mut self) -> Result<Option<Arc<[Column]>>, Error> {
        let payload = self.read_packet()?;
        match payload.first() {
            Some(0x00) => {
                let ok = ParseBuf(&payload).parse::<OkPacketDeserializer<CommonOkPacket>>(self.caps)?.into_inner();
                self.last_affected = ok.affected_rows();
                Ok(None)
            }
            Some(0xfb) => Err(protocol("the server asked for a local file")),
            Some(_) => {
                let count = (&payload[..]).read_lenenc_int()?;
                let count = usize::try_from(count).map_err(|_| protocol("column count out of range"))?;
                if count > 4096 {
                    return Err(protocol("too many columns"));
                }
                let mut columns = Vec::with_capacity(count);
                for _ in 0..count {
                    let packet = self.read_packet()?;
                    columns.push(ParseBuf(&packet).parse::<Column>(())?);
                }
                self.read_packet()?; // EOF after the column definitions
                Ok(Some(columns.into()))
            }
            None => Err(protocol("empty response")),
        }
    }

    /// The next row packet, or `None` at the EOF packet that ends the result set.
    fn next_row(&mut self) -> Result<Option<Vec<u8>>, Error> {
        let payload = self.read_packet()?;
        if payload.first() == Some(&0xfe) && payload.len() < 9 {
            return Ok(None);
        }
        Ok(Some(payload))
    }

    fn expect_ok(&mut self) -> Result<(), Error> {
        let payload = self.read_packet()?;
        match payload.first() {
            Some(0x00) => Ok(()),
            _ => Err(protocol("expected an OK packet")),
        }
    }

    fn command(&mut self, command: Command, data: &[u8]) -> Result<(), Error> {
        let mut buf = Vec::with_capacity(data.len() + 1);
        buf.push(command as u8);
        buf.extend_from_slice(data);
        self.stream.codec_mut().reset_seq_id();
        self.write_raw(&buf)
    }

    fn write_struct<T: MySerialize>(&mut self, value: &T) -> Result<(), Error> {
        let mut buf = Vec::new();
        value.serialize(&mut buf);
        self.write_raw(&buf)
    }

    fn write_raw(&mut self, data: &[u8]) -> Result<(), Error> {
        self.stream.send(&mut &*data)?;
        Ok(())
    }

    /// The next packet; an ERR packet becomes `Error::Server` (MariaDB progress reports are skipped).
    fn read_packet(&mut self) -> Result<Vec<u8>, Error> {
        loop {
            let mut buf = Vec::new();
            if !self.stream.next_packet(&mut buf)? {
                return Err(Error::Io(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "the server closed the connection",
                )));
            }
            if buf.first() != Some(&0xff) {
                return Ok(buf);
            }
            match ParseBuf(&buf).parse::<ErrPacket>(self.caps)? {
                ErrPacket::Error(e) => {
                    return Err(Error::Server {
                        code: e.error_code(),
                        state: e
                            .sql_state_ref()
                            .map(|s| String::from_utf8_lossy(&s.as_bytes()).into_owned())
                            .unwrap_or_default(),
                        message: e.message_str().into_owned(),
                    });
                }
                ErrPacket::Progress(_) => continue,
            }
        }
    }
}

fn password(opts: &Opts) -> Option<&str> {
    (!opts.password.is_empty()).then_some(opts.password.as_str())
}
