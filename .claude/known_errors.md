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
