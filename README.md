# Friends

A Hypixel-style friends system for Minecraft servers and networks: friend requests, a friend list that shows who is
online and what they are playing, best friends, nicknames, statuses, private messages between friends and ignore
lists. It runs on **Velocity**, **BungeeCord**, **Paper 1.8.8 → 26.3** and **Folia** (Java 25), and on
**[Pumpkin](https://github.com/Pumpkin-MC/Pumpkin)** (the Rust server) as a WebAssembly plugin. On a network it goes
on the proxy and covers every backend server; several proxies can share it through MySQL and Redis.

[Version](#version) · [Install on Velocity](#install-on-velocity) · [Config example](#config-example) ·
[Other platforms](#other-platforms) · [Commands](#commands) · [Upgrading](#upgrading) · [Changelog](CHANGELOG.md)

## Version

The current version is **0.7.0**. [`CHANGELOG.md`](CHANGELOG.md) lists what each version added.

> [!WARNING]
> The release named **"Friends Release (1.0.0)" holds version 0.5.0**: its jars were built before 0.6.0 and report
> version 0.5.0. They have no private messages (`/msg`, `/r`), ignore lists, presence rules or last-seen display, and
> there is no Pumpkin plugin in it. Until a 0.7.0 release is published, [build the jars](#build) to get those.

## Install on Velocity

Friends runs on the proxy only: the backend servers need nothing. Use it either on the proxies or on standalone Paper
servers, not both.

1. **Requirements:** Velocity on **Java 25** (tested on Velocity 4.2.0). Optional: LuckPerms on the proxy, for rank
   prefixes before player names in Friends' messages.
2. **Get `friends-velocity.jar`** from the [GitHub releases](https://github.com/Macaron27/Friends/releases) (read
   [Version](#version) first), or build it with `./gradlew build` (it lands in `builds/`).
3. **Put it in the proxy's `plugins/` folder.** Optionally add `--enable-native-access=ALL-UNNAMED` to the proxy's JVM
   flags: it silences the JDK warning about SQLite's native library.
4. **Start the proxy.** Friends writes `plugins/friends/config.yml` and, with the default SQLite storage, the database
   `plugins/friends/friends.db`. The log then shows:

   ```
   Friends: 0 presence rule(s); private messages on /msg and /r (rate limit: 3 per 3s)
   Friends enabled (proxy id: local)
   ```

   If Friends can't start (for example an unknown `storage.type`, or MySQL or Redis unreachable), the log shows
   `Friends is disabled: <reason>` instead. The proxy keeps running, without the Friends commands.
5. **Join and type `/f`** to see the commands. Every player can use them unless the permission `friends.use` is
   explicitly set to false (for example with LuckPerms).

The defaults work for a single proxy, so nothing has to be edited. To change a setting, edit
`plugins/friends/config.yml` and restart the proxy: there is no reload command.

## Config example

The same `config.yml` and keys on every platform. It lives in `plugins/friends/` on Velocity, `plugins/Friends/` on
BungeeCord and Paper, and `plugins/data/friends/` on Pumpkin. The file written on first start explains every setting
in its comments; the default is [`core/src/main/resources/config.yml`](core/src/main/resources/config.yml).

Example: two Velocity proxies sharing one MySQL database and one Redis, with game names taken from the server names.
The second proxy uses the same file with `proxy-id: "proxy-2"`.

```yaml
storage:
  type: mysql             # sqlite (the default) is enough for a single proxy

sqlite:
  file: friends.db        # only used with storage.type: sqlite

mysql:                    # MySQL or MariaDB, the same database on every proxy
  host: db.example.internal
  port: 3306
  database: friends
  user: friends
  password: "change-me"
  use-ssl: false

request-expiry-minutes: 5 # pending friend requests expire after this
max-friends: 5000

redis:                    # only for several proxies; needs storage.type: mysql
  enabled: true
  host: redis.example.internal
  port: 6379
  password: ""
  database: 0
  ssl: false
  proxy-id: "proxy-1"     # unique per proxy and stable across restarts
  namespace: friends

private-messages:
  enabled: true           # false if another plugin provides /msg and /r
  rate-limit:             # at most 3 messages per 3 seconds per player; 0 turns it off
    messages: 3
    seconds: 3

presence:                 # proxies only: "Playing BedWars Solo" in /f list
  rules:                  # first match wins; each pattern must match the whole server name, ignoring case
    - pattern: "BW.1S.*"
      game: "BedWars"
      mode: "Solo"
    - pattern: "SW.*"
      game: "SkyWars"     # mode is optional: "Playing SkyWars"
```

On one proxy with SQLite, keep `storage.type: sqlite` and `redis.enabled: false` (the defaults). The startup log then
says `proxy id: local`.

## Other platforms

| Platform | File | Config folder | Notes |
|---|---|---|---|
| BungeeCord | `friends-bungee.jar` in the proxy's `plugins/` | `plugins/Friends/` | Same steps as Velocity. No permission check. |
| Paper, Folia | `friends-paper.jar` in `plugins/` | `plugins/Friends/` | Standalone servers only (not behind a proxy that runs Friends). `redis` and `presence` are ignored. |
| Pumpkin | `friends-pumpkin.wasm` in `plugins/` | `plugins/data/friends/` | Not in a release yet: build it. Install and differences: [`PumpkinMC/README.md`](PumpkinMC/README.md). |

The jars come from the [GitHub releases](https://github.com/Macaron27/Friends/releases) or from `./gradlew build`
(in `builds/`); every server and proxy needs **Java 25**.

## Compatibility (tested on real servers with bot players)

| Platform | Tested | Notes |
|---|---|---|
| Paper 1.8.8, 1.12.2, 1.16.5, 1.20.6, 1.21.11, 26.3 | full friend flow with bot players | 1.16.5 needs `-DPaper.IgnoreJavaVersion=true` to start on Java 25 |
| Folia 1.21.11 | full friend flow with bot players (same jar as Paper) | regionized Paper: Friends uses no Bukkit scheduler |
| BungeeCord 26.1 (build 2100) | players on 1.8.8 and 26.3 backends | |
| Velocity 4.2.0 | players on 1.8.8 and 26.3 backends | |
| BungeeCord + Velocity sharing MySQL + Redis | cross-proxy friend flow | |
| Pumpkin nightly 2026-11-06 (26.3) | full friend flow with bot players, SQLite and MySQL; API over IPC | `friends_pumpkin.wasm` in `plugins/`; see [`PumpkinMC/README.md`](PumpkinMC/README.md) for what differs |

Versions in between use the same APIs; they were not all run. Velocity 4.2 and Paper 26.x need Java 25 anyway; older
Paper versions run on it (see the notes above). On old Paper the plugin uses the server's own SQLite driver: Paper
1.12.2's has no Apple Silicon build (Linux x86_64/ARM are fine), use MySQL there.

## Commands

`/friend` (aliases `/f`, `/friends`):

| Command | What it does |
|---|---|
| `/f add <player>` or `/f <player>` | Send a request (works for offline players who joined before). If they already asked you, you become friends. |
| `/f accept <player>` / `/f deny <player>` | Answer a request (clickable `[ACCEPT] - [DENY]` in chat). Requests expire after 5 minutes. |
| `/f list [best] [page]`, `/fl [page]` | 10 per page with clickable `<< >>`: best friends first (bold), then online (with server), then offline (by last seen). |
| `/f requests` | Pending incoming and outgoing requests. |
| `/f remove <player>` | Remove a friend (both sides). |
| `/f best <player>` | Toggle best friend. |
| `/f nickname <player> [nickname]` | Nickname only you can see (in your list and notifications). No nickname = clear. |
| `/f removeall [confirm]` | Remove every friend except best friends, after a clickable confirmation. |
| `/f notifications` | Toggle "Friend > X joined./left." messages. |
| `/f ignore <player>` / `/f unignore <player>` | Block a player (anyone who joined before): no private messages or friend requests from them, either way. Silently ends a friendship with them (nobody is told) and drops pending requests. `/f ignore` alone lists who you ignore. |
| `/status [online\|away\|busy\|offline]` | Status shown to friends; `offline` = appear offline (no join/leave messages). |

Private messages (friends only, network-wide; turn off with `private-messages.enabled: false`):

| Command | What it does |
|---|---|
| `/msg <friend> <message>` (also `/tell`, `/w`, `/whisper`) | Message a friend who is online and not appearing offline. Offline: "Bob is offline. (Last seen 3 days ago)". |
| `/r <message>` (also `/reply`) | Answer whoever you last messaged or heard from. Clicking a message line also starts a reply. |

- **Anti-spam:** at most 3 messages per 3 seconds per player (`private-messages.rate-limit`); over that, the message
  is blocked and the sender told how long to wait.
- **AFK auto-reply:** a friend whose status is away (`/status away`) still gets the message, and the sender sees
  "From Bob: I'm currently AFK, and will answer when I am available. (auto-reply)", at most once every 5 minutes per
  friend.

`/f list` shows each friend's presence:

```
● Player1 — Playing BedWars Solo      (green: online; yellow: away; red: busy, with "Away · " / "Busy · ")
● Player2 — In lobby-1                (no presence rule matches the server)
● Player3 — Last seen 17 minutes ago  (offline, or appearing offline)
```

Permission `friends.use`: on by default on Paper; on Velocity allowed unless explicitly false; BungeeCord has no
permission check (no permission defaults there). On Pumpkin it is `friends:use` (allowed by default): Pumpkin
namespaces plugin permissions.

## Config reference

The same file on every platform: `storage.type` `sqlite` or `mysql` (MySQL/MariaDB), `request-expiry-minutes`
(default 5), `max-friends` (default 5000), the `redis` section (proxies only; ignored on Paper),
`private-messages.enabled` (default true), `private-messages.rate-limit` (`messages: 3` per `seconds: 3`; 0 = no
limit) and `presence.rules` (proxies only):

```yaml
presence:
  rules:              # first match wins; each pattern is a regex matching the whole server name, ignoring case
    - pattern: "BW.1S.*"
      game: "BedWars"
      mode: "Solo"
    - pattern: "BW.2S.*"
      game: "BedWars"
      mode: "Doubles"
    - pattern: "SW.*"
      game: "SkyWars"   # mode is optional: "Playing SkyWars"
```

A rule with an invalid regex or without a game is skipped with a warning at startup; Friends keeps running.

## Upgrading

Replace the jar and restart: no database migration (new tables are created at startup). What a new version adds to
the config is added to your `config.yml` with its comments at startup: new sections at the end (0.6.0:
`private-messages` and `presence`), new keys at the end of their section, indented like yours (0.7.0:
`private-messages.rate-limit`). Nothing you wrote is changed; the startup log lists what was added.

Coming from the jars of the "1.0.0" release (version 0.5.0), both the 0.6.0 and the 0.7.0 notes below apply.

- **0.7.0 adds `/f ignore`, `/f unignore`, the message rate limit and AFK auto-replies.** Ignore lists live in a new
  `friends_ignores` table. On a multi-proxy network, upgrade every proxy: a proxy still on 0.6.0 doesn't check ignore
  lists, so players on it can still send friend requests to someone who ignores them (who can't accept them).
- **0.6.0 adds `/msg`, `/tell`, `/w`, `/whisper`, `/r` and `/reply`** (friends only). If another plugin provides them,
  set `private-messages.enabled: false`. On Paper, a plugin that declares `/msg` in its plugin.yml (e.g. Essentials)
  keeps it and Friends' becomes `/friends:msg` (logged); on the proxies Friends replaces an existing `/msg` (logged).
- **Multi-proxy:** upgrade every proxy together. Older proxies ignore activities (they keep working), but a private
  message to a player on a proxy that isn't upgraded yet is lost (that proxy logs "ignoring unknown event type").

## Multiple proxies

Set `storage.type: mysql` on every proxy (same database), then `redis.enabled: true` with a unique, stable
`redis.proxy-id` per proxy. Velocity and BungeeCord proxies can be mixed. Requests, accept/deny, join/leave
messages, status, current server, `/f list` and tab-completion then see the whole network.

- Redis holds only short-lived shared state: who is online where (`<ns>:online`), pending requests (one key
  each, expiring with the request), a heartbeat per proxy and one pub/sub channel. MySQL stays the source of truth.
- A proxy that stops heartbeating (crash, freeze) is reaped by the others after ~30-40 s and its players are shown
  offline. A restarted proxy clears what its previous run left behind.
- After a Redis reconnect every proxy re-reads the shared state and re-announces its own players.

Use the plugin either on the proxies or on standalone Paper servers, not both.

## API for other plugins

`FriendsAPI` is the same on Paper, Velocity and BungeeCord: friend lists, status, current server and pending
requests, activity, actions (request, accept, deny, remove, best friend, nickname, status, private message) and
events on every platform (including a cancellable `FriendMessageEvent` to plug in your own chat system, and
`PlayerActivityChangeEvent` on the proxies). `LastSeen` formats presence for other UIs ("🟢 Online",
"⚫ Last seen 3 days ago", "🟢 Bob — Playing BedWars Solo"). See
[`api/README.md`](api/README.md) for setup, examples and the threading rules.

## Build

```bash
./gradlew build   # tests, then builds/friends-paper.jar, friends-velocity.jar, friends-bungee.jar, friends-api.jar
```

The Pumpkin plugin: `cargo plugin` in `PumpkinMC/` (see its README) builds `friends_pumpkin.wasm`.

Publishing a GitHub release builds its tag and attaches the four jars and `friends-pumpkin.wasm` to it. The tag must
be the project version (`build.gradle.kts` and `PumpkinMC/Cargo.toml`, e.g. `0.7.0` or `v0.7.0`), or the workflow
stops before building.

Modules (one folder per part; a new platform gets its own top-level folder next to these):
- `api/` — the API for other plugins (Java 8, no dependencies): interfaces, data models, events. Usage guide:
  [`api/README.md`](api/README.md).
- `core/` — platform-agnostic core: rules, cache, storage (SQLite/MySQL), messages, commands, multi-proxy network.
- `paper/` — Paper and Folia, one jar for 1.8.8 to 26.x.
- `bungee/`, `velocity/` — proxy plugins (thin glue). Both support multi-proxy networks through Redis, even mixed.
- `PumpkinMC/` — the Pumpkin plugin, in Rust: the core ported (same config.yml, tables and messages) plus Pumpkin glue.
  Built with Cargo, not Gradle: [`PumpkinMC/README.md`](PumpkinMC/README.md).
- `builds/` — the jars to ship, written by every build (not in git).

## How it works

- Each online player's friends and settings are loaded with one query at login (the login waits for it on
  Velocity and BungeeCord) and kept in memory, so lists, notifications and tab-completion never hit the database.
- Every change is an event: applied under one lock (cache + queued SQL write together, so they can't drift), then
  published through the SQL queue, so other proxies only see a change once it is stored.
- SQL runs on one ordered database thread; commands run off the server's main/network threads (virtual threads on
  Paper). Join/leave notifications are built without template parsing (~20 ns instead of ~4 µs each).
- Chat on BungeeCord and Paper goes out as BungeeCord components, the one chat API every version has; the core
  uses Adventure (bundled and relocated where the platform lacks it).
- Paper jar: a Java 8 bootstrap loads the Java 25 plugin from an embedded jar, so CraftBukkit's class rewriter
  (1.13+, which can't read Java 25 class files before ~1.21) never sees it. The API (Java 8) sits next to the
  bootstrap, where other plugins' class loaders can see it. It skips SQLite (every Paper bundles its
  own; the SQL stays portable to SQLite 3.7.2, Paper 1.8.8's) and Redis, so it is 3.4 MB, and tells Paper 1.20.5+
  not to remap it.
- Presence (online, server, status, activity) lives in memory and, with several proxies, in the Redis `<ns>:online`
  hash: a server switch evaluates `presence.rules` once and publishes one update only if something changed. Private
  messages are one pub/sub event, published straight away (not behind the SQL queue) and never stored.
- Rate-limit and AFK-reply bookkeeping lives with each online player's cached data and goes away when they leave.
- Tables are `friends_players`, `friends_friendships` and `friends_ignores`. Data from the old Bungee/Paper tables
  (`players`, `friends`, `requests`, `settings`) is not migrated.

## Tests

Test sources are kept locally and are not published in this repository. `./gradlew build` always runs
`verifyBuilds`, which opens the jars in `builds/` and checks their descriptors and versions, that each platform ships
only its own events, that bundled libraries are relocated, that the Paper and API jars are Java 8 bytecode, and that
no file-sync copies (`Foo 2.class`) slipped in.

With the local suites present, `./gradlew build` also runs SQLite, in-memory multi-proxy, randomized cache-vs-database
checks with a deliberately slow database, a concurrency stress test, chat conversion on the newest and on the 1.8
BungeeCord chat API, and the SQLite suites on sqlite-jdbc 3.7.2 / 3.21.0.1 (the drivers Paper 1.8.8 / 1.12.2 bundle).
Add `-Dfriends.mysql=host:port/db:user:password` and/or `-Dfriends.redis=host:port` to also run the MySQL and
real-Redis suites (throwaway tables/key namespaces). The `paper` module runs the real plugin wiring on MockBukkit
against the shipped implementation jar, and the proxies' events are tested on their own event APIs.

End-to-end tests on real servers with bot players (`tools/e2e`, with `tools/e2e/probe`, a test plugin using the API)
are kept locally too: they are not in the repository, and Gradle builds the probe only where that folder exists.
