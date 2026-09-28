# Friends (Velocity)

A Hypixel-style friends system for [Velocity](https://papermc.io/software/velocity) 4.2, built for Java 25.

Modules:
- `common` — platform-agnostic core (rules, cache, storage, Adventure messages) + tests.
- `velocity` — the Velocity plugin (thin glue: config, events, commands, optional LuckPerms prefixes).
- `paper/`, `bungee/`, `bungee-api-stub/` — the old implementations. They still target the previous
  `common` API and are **not part of the build** until they are ported.

## Build

```bash
gradle :velocity:shadowJar   # -> velocity/build/libs/friends-velocity-0.2.0.jar
gradle :common:test          # SQLite; add -Dfriends.mysql=host:port/db:user:password to also run on MySQL
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
`max-friends` (default 5000).

## How it works

- Each online player's friends and settings are loaded with one query at login and kept in memory, so
  lists, notifications and tab-completion never hit the database.
- Writes update the cache immediately and run on a single ordered database thread, never on proxy threads.
- Friend requests live in memory (they expire after minutes) and are lost on proxy restart.
- Single proxy only: multiple Velocity instances would need a shared cache/pub-sub (e.g. Redis).
- Tables are `friends_players` and `friends_friendships`. Data from the old Bungee/Paper tables
  (`players`, `friends`, `requests`, `settings`) is not migrated.
