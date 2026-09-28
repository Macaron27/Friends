# Friends (Velocity)

A Hypixel-style friends system for [Velocity](https://papermc.io/software/velocity) 4.2, built for Java 25.

Modules:
- `common` — platform-agnostic core (rules, cache, storage, Adventure messages) + tests.
- `velocity` — the Velocity plugin (thin glue: config, events, commands, optional LuckPerms prefixes).
- `paper/`, `bungee/`, `bungee-api-stub/` — the old implementations. They still target the previous
  `common` API and are **not part of the build** until they are ported.

## Build

```bash
./gradlew :velocity:shadowJar   # -> velocity/build/libs/friends-velocity-0.3.0.jar
./gradlew :common:test         # see "Tests" below for the MySQL and Redis suites
```

Drop the jar in Velocity's `plugins/` folder. Velocity 4.2 itself needs Java 25. sqlite-jdbc loads a native
library, so add `--enable-native-access=ALL-UNNAMED` to the proxy's JVM flags to silence the JDK warning.

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
| `/status [online\|away\|busy\|offline]` | Status shown to friends; `offline` = appear offline (no join/leave messages). |

Permission: `friends.use` — allowed unless explicitly set to false (Velocity has no permission defaults).

## Config (`plugins/friends/config.yml`)

`storage.type` `sqlite` (single proxy) or `mysql` (MySQL/MariaDB), `request-expiry-minutes` (default 5),
`max-friends` (default 5000), and the `redis` section for multi-proxy networks.

## Multiple proxies

Set `storage.type: mysql` on every proxy (same database), then `redis.enabled: true` with a unique, stable
`redis.proxy-id` per proxy. Friends then work across proxies: requests, accept/deny, join/leave messages,
online status, current server, `/f list` and tab-completion all see the whole network.

- Redis holds only short-lived shared state: who is online where (`<ns>:online`), pending requests (one key
  each, expiring with the request), a heartbeat per proxy and one pub/sub channel. MySQL stays the source of truth.
- A proxy that stops heartbeating (crash, freeze) is reaped by the others after ~30-40 s and its players are shown
  offline. A restarted proxy clears what its previous run left behind.
- After a Redis reconnect every proxy re-reads the shared state and re-announces its own players.

## How it works

- Each online player's friends and settings are loaded with one query at login and kept in memory, so
  lists, notifications and tab-completion never hit the database.
- Every change is an event: applied under one lock (cache + queued SQL write together, so they can't drift), then
  published through the SQL queue, so other proxies only see a change once it is stored.
- Writes run on a single ordered database thread per proxy, never on proxy threads.
- Friend requests live in memory (plus Redis when enabled) and expire after minutes; they are not kept in SQL.
- Tables are `friends_players` and `friends_friendships`. Data from the old Bungee/Paper tables
  (`players`, `friends`, `requests`, `settings`) is not migrated.

## Tests

`./gradlew :common:test` runs everything that needs no services (SQLite, in-memory multi-proxy, randomized
cache-vs-database checks with a deliberately slow database, a concurrency stress test). Add
`-Dfriends.mysql=host:port/db:user:password` and/or `-Dfriends.redis=host:port` to also run the MySQL and
real-Redis suites (they use throwaway tables/key namespaces).
