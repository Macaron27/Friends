# Known errors & fixes

- **Settings clobbered on write (old SqlFriendService):** `INSERT OR REPLACE INTO settings (player_uuid, x)` reset every
  other column to its default, and is SQLite-only. Fix: dialect upserts that only touch the given columns
  (`ON CONFLICT DO UPDATE` / `ON DUPLICATE KEY UPDATE`), plain `UPDATE` for settings.
- **Portable DDL:** MySQL 8 has no `CREATE INDEX IF NOT EXISTS`; SQLite has no inline `INDEX` in `CREATE TABLE`.
  MySQL also rejects `DELETE ... WHERE x IN (SELECT ... same table)` (error 1093) — batch explicit deletes instead.
- **Velocity session races:** with `kick-existing-players`, the new session can load before the old one's
  `DisconnectEvent`. Profiles are keyed by UUID but owned by the session (`Audience` identity); only the owner may drop it,
  and a load that finishes after the player left is discarded.
- **Chat before the first backend:** at `PostLoginEvent` the client may not accept chat yet; send "pending requests"
  on the first `ServerPostConnectEvent`.
- **Adventure 5:** `ClickEvent` is generic; read commands with `((ClickEvent.Payload.Text) e.payload()).value()`.
- **JUnit:** `@AfterEach` still runs when `@BeforeEach` aborts on an assumption — null-guard teardown.
- **sqlite-jdbc:** never relocate `org.sqlite` when shading (JNI symbol names). Java 25 has no 32-bit x86 port
  (JEP 503), so those natives can be excluded from the jar.
- **Local MySQL on macOS:** the Unix socket path must be ≤ 103 chars; use a short `--socket` path and connect over TCP.
- **Cache vs SQL drift under concurrency:** "decide → update cache → queue SQL write" must be one atomic step, or two
  threads (accept vs remove, two `/f best` toggles) queue writes in a different order than they changed the cache.
  Fix: one lock around every state change; SQL writes queued inside it. Found by `ConsistencyTest` (random stress).
- **Don't apply an event after its write commits (thenRun):** with crossing accepts, a delayed second `Befriended` fired
  after a later removal and re-linked the pair. Apply immediately under the lock instead.
- **Cross-proxy ordering:** publish events *through the SQL queue* (after the writes queued before them), otherwise a
  remote proxy can delete a friendship before its insert lands. Crossing accepts on two proxies need a Redis
  `SET NX PX` claim on the pair, or the loser's lagging insert resurrects a removed friendship.
- **Stale profile loads:** a friendship change for a player whose profile is still loading is missed by the load (the
  write is queued behind it) — track loading players and re-read if something changed.
- **Detect races deterministically:** a `LaggingDb` executor (queue SQL tasks, run random FIFO batches) reproduces
  write-timing bugs every run; random multi-threaded stress only catches them ~10% of the time.
- **Test clocks vs wall time:** RedisNetwork turns absolute expiry into a TTL with real time; tests using a fake clock
  must set it to `Instant.now()` or keys expire instantly.
- **Name shadowing:** a local variable named `redis` shadows the `redis.clients...` package in fully-qualified names.
- **Jedis 8:** `JedisPooled` → `RedisClient.builder()`; `ssl(boolean)` is deprecated → `sslOptions(SslOptions.defaults())`.
- **GitHub Actions YAML:** an unquoted `run:` containing `: ` (e.g. `...:root: -D...`) is parsed as a mapping and the
  whole workflow is rejected (run with 0 jobs, named after the file path). Quote such commands.
- **Bukkit loads the server's libraries first:** Paper 1.8.8 bundles sqlite-jdbc 3.7.2 (1.12.2: 3.21.0.1), so our
  SQLite SQL must avoid UPSERT/TRUE/FALSE, set `connectionTestQuery`, and bind booleans with `setBoolean`. The
  `testSqlite3_7_2` / `testSqlite3_21_0_1` tasks run the suites on those drivers. 3.7.2 rejects `journal_mode` as a
  connection property (it returns a row): use `connectionInitSql`. 3.21.0.1 has no Apple Silicon native.
- **CraftBukkit's class rewriter (Commodore, 1.13+) can't read Java 25 classes before ~1.21:** "Fatal error trying to
  convert" per class. Fix: Java 8 bootstrap plugin + embedded implementation jar loaded by a child URLClassLoader.
- **Paper 1.16.5 refuses Java > 16** at startup: `-DPaper.IgnoreJavaVersion=true` (it runs fine on Java 25).
- **bungeecord-chat:** `ChatColor` stopped being an enum (compile against 1.8, never `switch` on it);
  `ComponentSerializer` moved to `bungeecord-serializer`; `ChatColor.of` (hex) only on 1.16+.
- **Hot-path messages:** MiniMessage parsing costs ~4 µs per message; join/leave notices go to every online friend,
  so build them from prebuilt components (~20 ns) and cache fixed messages.
- **Gradle:** `project.version` inside `filesMatching { expand(...) }` is Task.project at execution time
  (deprecated, breaks the configuration cache) — capture it during configuration.
- **E2E bot protocol ids:** minecraft-data lacks 26.2/26.3; minecraft.wiki's protocol page documents the latest
  (26.3 = 777; known packs moved 0x0E -> 0x0F, play ids shifted). Paper 26.x enables the whitelist by default.

- **Duplicated build outputs ("Foo 2.class"):** seen once in common/build (cause not confirmed; the repo is under
  ~/Desktop, which macOS can sync). Gradle then fails with "Could not execute test class 'X 2' (wrong name: X)":
  `./gradlew :<module>:clean`; nothing tracked by git is affected.
- **Plugin code must never run on the database thread:** a continuation (`thenCompose`) of a `db(...)` future runs on
  the DB thread, so a hook there blocks SQL, and a listener that joins an API future deadlocks. Hop first:
  `thenComposeAsync(..., async)`. `FriendsApiTest.listenersRunOffTheDatabaseThread...` deadlocks without it.
- **`supplyAsync(x, ex).thenCompose(f)` can run `f` on the caller's thread** (if x finished first). For API actions
  the whole action goes inside the `supplyAsync`, or a Bukkit async event could fire from the server thread.
- **Cancellable events outside the lock:** fire, then re-check under the lock. Post events ("...Added") come from the
  locked commit, exactly once, delivered in order by one FriendsRuntime thread.
- **Offline cache vs a racing change:** put the cache entry *before* queuing its read; changes queue their write
  before dropping entries, so any surviving entry was read after the write. Reversed, a stale list stays cached.
- **javac 25 `--release 8`** compiles against Java 25 jars (velocity-api) fine, but Gradle refuses such a dependency
  for a Java 8 consumer: `java { disableAutoTargetJvm() }`. Published metadata still says JVM 8 (from `release`).
- **MockBukkit + Adventure 5:** paper-api 1.21.11's `BookMeta` implements Adventure 4's `Book`, sealed in Adventure 5
  (IncompatibleClassChangeError). Run Paper tests against the shadow jar (Adventure relocated), like a real server.
  MockBukkit also subclasses the test plugin (can't be `final`) and ignores plugin.yml permission defaults.
