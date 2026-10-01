# Friends

A Hypixel-style friends system for **Velocity**, **BungeeCord** and **Paper 1.8.8 → 26.3**, built for Java 25.

Modules:
- `api` — the API for other plugins (Java 8, no dependencies), see [API for other plugins](#api-for-other-plugins).
- `common` — platform-agnostic core (rules, cache, storage, messages, multi-proxy network) + tests.
- `velocity`, `bungee` — proxy plugins (thin glue). Both support multi-proxy networks through Redis, even mixed.
- `paper` — standalone Paper servers, one jar for 1.8.8 to 26.x.
- `tools/e2e` — end-to-end tests on real servers with bot players; `tools/e2e/probe` is a test plugin using the API.

## Build

```bash
./gradlew build   # tests + velocity/build/libs/friends-velocity-0.5.0.jar,
                  #         bungee/build/libs/friends-bungee-0.5.0.jar, paper/build/libs/friends-paper-0.5.0.jar
```

Put the jar for your platform in `plugins/`. Every server/proxy must run **Java 25** (Velocity 4.2 and Paper 26.x
require it anyway; older Paper versions run on it, see below). Add `--enable-native-access=ALL-UNNAMED` to the JVM
flags to silence the JDK warning about SQLite's native library.

## Compatibility (tested on real servers, see `tools/e2e`)

| Platform | Tested | Notes |
|---|---|---|
| Paper 1.8.8, 1.12.2, 1.16.5, 1.20.6, 1.21.11, 26.3 | full friend flow with bot players | 1.16.5 needs `-DPaper.IgnoreJavaVersion=true` to start on Java 25 |
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
| `/status [online\|away\|busy\|offline]` | Status shown to friends; `offline` = appear offline (no join/leave messages). |

Permission `friends.use`: on by default on Paper; on Velocity allowed unless explicitly false; BungeeCord has no
permission check (no permission defaults there).

## Config (`plugins/Friends/config.yml`, `plugins/friends/` on Velocity)

The same file on every platform: `storage.type` `sqlite` or `mysql` (MySQL/MariaDB), `request-expiry-minutes`
(default 5), `max-friends` (default 5000), and the `redis` section (proxies only; ignored on Paper).

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

`FriendsAPI` (artifact `com.friends:friends-api`) is the same on Paper, Velocity and BungeeCord: friend lists,
status, current server and pending requests, plus actions (request, accept, deny, remove, best friend, nickname,
status). Each platform also has six events (`com.friends.api.bukkit`, `.velocity`, `.bungee`). Every public method is
documented: `./gradlew :api:javadoc`.

```bash
./gradlew :api:publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { compileOnly("com.friends:friends-api:0.5.0") } // Friends provides it at runtime
```

Declare the dependency: `depend: [Friends]` in plugin.yml, `depends: [Friends]` in bungee.yml,
`@Dependency(id = "friends")` on Velocity.

```java
FriendsAPI friends = FriendsAPI.get(); // on Paper also getServer().getServicesManager().load(FriendsAPI.class)

boolean pals = friends.areFriends(alice, bob); // memory only: fine on the server thread
friends.loadFriends(someone)                   // offline players come from the database (cached 30 s)
        .thenAccept(list -> ...);              // completes on Friends' threads, not the server thread
friends.sendRequest(alice, bob).thenAccept(result -> {
    if (result == Result.CANCELLED) ...        // a plugin cancelled the event
});

@EventHandler
public void onRequest(FriendRequestSendEvent e) { // asynchronous: never on the server thread
    if (isMuted(e.getPlayerId())) e.setCancelled(true); // Friends says nothing: tell the player yourself
}
```

| Event | Cancellable | Fired |
|---|---|---|
| `FriendRequestSendEvent` | yes | before a request is sent |
| `FriendAddEvent` | yes | before two players become friends (accept, or asking back) |
| `FriendAddedEvent` | no | after, exactly once per new friendship |
| `FriendRemoveEvent` | yes | before a friendship ends, once per friend (`/f removeall` too) |
| `FriendRemovedEvent` | no | after, exactly once |
| `FriendStatusChangeEvent` | yes | before `/status` changes |

- **Threads.** Methods returning a value read memory and never block. Methods returning a `CompletableFuture`
  return at once and do their work (database included) on Friends' threads. Events run on Friends' threads too
  (Bukkit: asynchronous events): switch to the server thread before touching the world.
- **Commands and API actions fire the same events.** An action doesn't reply to the acting player (the `Result` says
  what happened, e.g. `NOT_LOADED`, `ALREADY_FRIENDS`, `CANCELLED`); the other player is still notified. The acting
  player must be online on this server/proxy.
- **Exactly once:** cancellable events fire outside Friends' lock and the action is checked again afterwards, so in
  a rare race (one request accepted twice at the same moment) an action can still fail after its event. Rewards and
  statistics belong in `FriendAddedEvent` / `FriendRemovedEvent`.
- **Where to listen:** on a network Friends runs on the proxy, so listen to its Velocity/BungeeCord events (each
  fires once network-wide, on the proxy where the change happened). Bukkit events fire on standalone Paper servers.
- **Nothing throws:** null or invalid arguments give empty results, `false` or `Result.INVALID_ARGUMENT`; database
  errors give `Result.ERROR`. Events and snapshots hold UUIDs and names, never `Player` objects.

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
- Tables are `friends_players` and `friends_friendships`. Data from the old Bungee/Paper tables
  (`players`, `friends`, `requests`, `settings`) is not migrated.

## Tests

`./gradlew build` runs everything that needs no services: SQLite, in-memory multi-proxy, randomized
cache-vs-database checks with a deliberately slow database, a concurrency stress test, chat conversion on the
newest and on the 1.8 BungeeCord chat API, and the SQLite suites on sqlite-jdbc 3.7.2 / 3.21.0.1 (the drivers
Paper 1.8.8 / 1.12.2 bundle). Add `-Dfriends.mysql=host:port/db:user:password` and/or `-Dfriends.redis=host:port`
to also run the MySQL and real-Redis suites (throwaway tables/key namespaces). The API has its own suite (results,
null/offline/invalid input, the offline cache under a paused database, cancelling, exactly-once events), and the
`paper` module runs the real plugin wiring on MockBukkit 4.116.3 against the shipped implementation jar (service,
asynchronous Bukkit events, cancelling); the proxies' events are tested on their own event APIs.

End-to-end on real servers: see [`tools/e2e/README.md`](tools/e2e/README.md).
