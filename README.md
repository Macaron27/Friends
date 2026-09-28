# Friends Plugin (Scaffold)

This workspace contains a scaffold for a multi-module Gradle-based Friends plugin for Minecraft (Paper + Bungee), Java 21.

Modules:
- common: Shared models, DAOs, DB utilities, `FriendService` interface.
- paper: Paper plugin that depends on `common`.
- bungee: BungeeCord plugin that depends on `common`.

DB support:
- Primary: MySQL (recommended for production).
- Fallback: SQLite (local file `friends.db`) if MySQL is not configured.

Next steps:
- Implement DAOs and `FriendService` using HikariCP
- Implement commands and listeners in `paper` and `bungee` modules
- Add migrations runner (Flyway or custom)

## Commands

All commands are available under `/f` or `/friends`:
- `/f add <player>` — send a friend request
- `/f remove <player>` — remove a friend
- `/f accept <player>` — accept a friend request
- `/f deny <player>` — deny a friend request
- `/f help` — show available commands
- `/f list` — list your friends (paginated)
- `/f requests <page>` — show incoming requests (paginated)
- `/f removeall` — remove all friends (must confirm: `/f removeall confirm`)
- `/f notifications|notif` — toggle join/leave notifications
- `/f settings` — show settings
- `/f settings expiry <minutes>` — set request expiry in minutes
- `/f settings notifications <on|off>` — toggle notifications

Plugin messaging channel used for richer notifications: `friends:notify` (payload: `TYPE|uuid|username|server`) — sent from Bungee to target servers.

Permissions (defaults):
- `friends.use` (true) — allows using friends commands
- `friends.add` (true) — send requests
- `friends.remove` (true) — remove friends
- `friends.accept` (true) — accept requests
- `friends.deny` (true) — deny requests
- `friends.list` (true) — list friends
- `friends.requests` (true) — view incoming requests
- `friends.notifications` (true) — toggle notifications
- `friends.settings` (true) — change settings
- `friends.removeall` (op) — remove all friends
- `friends.admin` (op) — admin actions

Notes:
- Currently commands are wired to an in-memory `FriendService` for testing; a DB-backed service will replace it.
- MySQL is the recommended production DB; an SQLite fallback is available for local testing.
