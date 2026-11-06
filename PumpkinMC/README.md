# Friends for Pumpkin

The Paper plugin's behaviour as a [Pumpkin](https://github.com/Pumpkin-MC/Pumpkin) plugin: Pumpkin is a Minecraft
server written in Rust, and its plugins are WebAssembly components (`wasm32-wasip2`) built against
[`pumpkin-plugin-api`](https://crates.io/crates/pumpkin-plugin-api) (Wasm API v0.1, what Pumpkin loads by default).
Tested on the Pumpkin nightly of 2026-11-06 (`0.2.1+26.3-26.60`, Minecraft 26.3).

- `core/` (`friends-core`): the platform-agnostic port of `com.friends.core`: rules, caches, messages, commands,
  config.yml (with upgrades), SQLite and MySQL/MariaDB storage. Nothing in it knows about Pumpkin.
- `plugin/` (`friends-pumpkin`): the Pumpkin glue (the counterpart of `PaperFriends`), built to `friends_pumpkin.wasm`.
- `tools/probe/`: a test plugin that uses the API (end-to-end tests only).

## Build

Needs the Rust toolchain pinned in `rust-toolchain.toml` (with the `wasm32-wasip2` target) and
[wasi-sdk](https://github.com/WebAssembly/wasi-sdk) to compile SQLite's C sources to WebAssembly (`CC_wasm32_wasip2`,
`AR_wasm32_wasip2` and `CFLAGS_wasm32_wasip2=--sysroot=...`, see `.github/workflows/build.yml`). Or use the dev image,
which has everything:

```bash
docker build -t friends-pumpkin-dev -f PumpkinMC/tools/dev.Dockerfile PumpkinMC/tools
docker run --rm -v "$PWD:/repo" -w /repo/PumpkinMC friends-pumpkin-dev cargo plugin
# -> PumpkinMC/target/wasm32-wasip2/release/friends_pumpkin.wasm
```

Run it from the repository root: the plugin embeds `core/src/main/resources/config.yml`, the same file every platform
ships. Releases attach the built file as `friends-pumpkin.wasm`.

## Install

Put `friends_pumpkin.wasm` in the server's `plugins/` folder. The plugin asks for three permissions, which Pumpkin
asks the console to confirm on first load (or pre-approve them in `pumpkin.toml`):

```toml
[plugins]
allowed_permissions = ["fs.write.data", "network.dns", "network.tcp.connect"]
```

`fs.write.data` holds `config.yml` and the SQLite database; `network.dns` and `network.tcp.connect` are for MySQL only
(with SQLite you can put those two in `blocked_permissions` instead).

## Config and data

`plugins/data/friends/config.yml`, the same file and keys as on Paper (`plugins/Friends/config.yml`): written on first
start, and new settings are added with their comments on upgrades, exactly like the Java plugins. The database uses
the same tables and SQL (`friends_players`, `friends_friendships`, `friends_ignores`), so:

- **Moving from Paper:** copy `plugins/Friends/config.yml` and `friends.db` (with `friends.db-wal` if present) to
  `plugins/data/friends/`. Paths in the config are inside that folder (the plugin is sandboxed to it).
- **MySQL/MariaDB:** `storage.type: mysql` with the same database the Java plugins use (MySQL 8 with
  `caching_sha2_password` or `mysql_native_password`, MariaDB 10.5+). As on Paper, run Friends either on the proxies
  or on standalone servers, not both.

As on Paper, the `redis` section is ignored (it's for multi-proxy networks) and `presence.rules` never match (a
standalone server has no backend server name).

## Commands and permissions

| Paper | Pumpkin |
|---|---|
| `/friend` (`/f`, `/friends`), `/fl`, `/status` | the same, same arguments and messages |
| `/msg` (`/tell`, `/w`, `/whisper`), `/r` (`/reply`) | the same. Vanilla owns `/msg`, `/tell` and `/w` on Pumpkin, so Friends takes them over before they run (players without `friends:use` keep vanilla's); `/whisper`, `/r` and `/reply` are its own commands. `private-messages.enabled: false` leaves all of them alone. |
| permission `friends.use` (default true) | `friends:use` (default allow): Pumpkin requires plugin permissions in the plugin's namespace |
| `PlayerJoinEvent` / `PlayerQuitEvent` listeners | `PlayerJoinEvent` / `PlayerLeaveEvent` handlers (non-blocking: logins never wait for the database) |

## API for other plugins (IPC)

Pumpkin plugins talk to each other with byte messages, so `FriendsAPI` is JSON over IPC: send
`ipc::send_ipc_message("friends", request)` and parse the reply. Requests mirror `FriendsAPI`
(UUIDs as strings, times as epoch milliseconds); actions answer the name of the `Result` constant (`"SUCCESS"`...):

| `op` | arguments | reply |
|---|---|---|
| `isLoaded`, `getStatus`, `getServer`, `getActivity` | `player` | `true`/`false`; `"ONLINE"`... or `null`; `null` (no server name here); `null` |
| `getFriends` (memory), `loadFriends` (database) | `player` | `[{"id","name","since","best","nickname","lastSeen"}]` |
| `areFriends` | `a`, `b` | `true`/`false` |
| `getIncomingRequests`, `getOutgoingRequests` | `player` | `[{"sender","senderName","target","targetName","expiresAt"}]` |
| `sendRequest` | `sender`, `target` | `"SUCCESS"`, `"BECAME_FRIENDS"`, ... |
| `acceptRequest`, `denyRequest` | `player`, `sender` | result |
| `removeFriend` | `player`, `friend` | result |
| `setBestFriend` / `setNickname` | `player`, `friend`, `best` / `nickname` (or null) | result |
| `setStatus` | `player`, `status` | result |
| `sendMessage` | `sender`, `receiver`, `message` | result |
| `subscribe` / `unsubscribe` | | `true` |

After `subscribe`, Friends sends the plugin its events, like the Bukkit ones: `{"event": "FriendRequestSendEvent",
"playerId", "playerName", "targetId", "targetName", "cancelled": false}`, also `FriendAddEvent`, `FriendAddedEvent`
(+ `since`), `FriendRemoveEvent`, `FriendRemovedEvent`, `FriendMessageEvent` (+ `message`) and
`FriendStatusChangeEvent` (`playerId`, `playerName`, `oldStatus`, `newStatus`). Answer `{"cancelled": true}` to cancel
one of the `...Event`s that aren't "done" events, `{"message": "..."}` to rewrite a private message, anything else (e.g.
`{}`) to let it be. Subscribers are asked in turn, each seeing the verdict so far. A plugin may call Friends back while
handling an event. `tools/probe` is a working example.

## Not like Paper (yet)

- **Player names aren't tab-completed** (subcommands and `/status` values are). Pumpkin's Wasm host crashes the whole
  server when a player tab-completes an argument that has a plugin suggestion handler (it blocks on a Tokio runtime
  from the tick thread; seen on the 2026-11-06 nightly), so Friends registers none. `FriendCommand::suggest` is ported
  and tested, ready to wire up once that's fixed.
- **`mysql.use-ssl: true` is refused** at startup: there's no TLS in the plugin sandbox yet. Use it on a trusted
  network, or SQLite.
- **No rank prefixes**: there's no LuckPerms on Pumpkin, so names show without a prefix (as on Paper without LuckPerms).
  Prefixes another server stored in the database still display.
- **SQLite is opened with exclusive locking** (WASI has no shared memory for WAL's index): other programs can't read
  `friends.db` while the server runs.
- The config is read as YAML 1.2: `yes`/`no`/`on`/`off` aren't booleans (use `true`/`false`).
- A plugin's calls are serialized by Pumpkin: a slow MySQL server delays Friends' commands (never logins, which don't
  wait for it).

## Tests

As for the Java modules, test sources stay local (`core/tests/`, not in git). With them present:

```bash
cargo test -p friends-core --target x86_64-unknown-linux-gnu   # natively
cargo test -p friends-core --target wasm32-wasip2              # as WASI programs (wasmtime runner, dev image)
```

Build-time variables add the database suites: `FRIENDS_MYSQL_SERVERS=127.0.0.1:3306/rootpw,...` (MySQL/MariaDB root
logins, database `friends`) and `FRIENDS_SQLITE_FIXTURE=<dir>` (a `friends.db` as the Java plugin writes it, from
`core/tests/fixtures/java_sqlite.py`). End-to-end scenarios on a real Pumpkin server (`pumpkin`, `pumpkin-mysql`,
`load-pumpkin`, `api-pumpkin`) are part of the local end-to-end tools (`tools/e2e`).
