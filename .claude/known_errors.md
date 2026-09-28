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
