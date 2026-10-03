# Friends

A Hypixel-style friends system for **Velocity**, **BungeeCord**, **Paper 1.8.8 → 26.3** and **Folia**, built for Java 25.

Modules (one folder per part; a new platform gets its own top-level folder next to these):
- `api/` — the API for other plugins (Java 8, no dependencies): interfaces, data models, events. Usage guide:
  [`api/README.md`](api/README.md).
- `core/` — platform-agnostic core: rules, cache, storage (SQLite/MySQL), messages, commands, multi-proxy network.
- `paper/` — Paper and Folia, one jar for 1.8.8 to 26.x.
- `bungee/`, `velocity/` — proxy plugins (thin glue). Both support multi-proxy networks through Redis, even mixed.
- `builds/` — the jars to ship, written by every build (not in git).
- `tools/e2e` — end-to-end tests on real servers with bot players; `tools/e2e/probe` is a test plugin using the API.

## Build

```bash
./gradlew build   # tests, then builds/friends-paper.jar, friends-velocity.jar, friends-bungee.jar, friends-api.jar
```

Or download the jars from the [GitHub releases](https://github.com/Macaron27/Friends/releases) (each release builds and
attaches them). Put the jar for your platform in `plugins/`. Every server/proxy must run **Java 25** (Velocity 4.2 and Paper 26.x
require it anyway; older Paper versions run on it, see below). Add `--enable-native-access=ALL-UNNAMED` to the JVM
flags to silence the JDK warning about SQLite's native library.

## Compatibility (tested on real servers, see `tools/e2e`)

| Platform | Tested | Notes |
|---|---|---|
| Paper 1.8.8, 1.12.2, 1.16.5, 1.20.6, 1.21.11, 26.3 | full friend flow with bot players | 1.16.5 needs `-DPaper.IgnoreJavaVersion=true` to start on Java 25 |
| Folia 1.21.11 | full friend flow with bot players (same jar as Paper) | regionized Paper: Friends uses no Bukkit scheduler |
| BungeeCord 26.1 (build 2100) | players on 1.8.8 and 26.3 backends | |
| Velocity 4.2.0 | players on 1.8.8 and 26.3 backends | |
| BungeeCord + Velocity sharing MySQL + Redis | cross-proxy friend flow | |

Versions in between use the same APIs; they were not all run. On old Paper the plugin uses the server's own
SQLite driver: Paper 1.12.2's has no Apple Silicon build (Linux x86_64/ARM are fine), use MySQL there.

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
permission check (no permission defaults there).

## Config (`plugins/Friends/config.yml`, `plugins/friends/` on Velocity)

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

End-to-end on real servers: see [`tools/e2e/README.md`](tools/e2e/README.md).
