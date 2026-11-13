# Changelog

Every version of Friends, newest first. A version is the project version in `build.gradle.kts` (and
`PumpkinMC/Cargo.toml`); its date is when its last change was merged into `main`. How to upgrade:
[README](README.md#upgrading).

**Releases:** the only GitHub release so far, "Friends Release (1.0.0)" (2026-10-02), holds the **0.5.0** jars.
0.6.0 and 0.7.0 have not been published as releases yet.

## Unreleased

On `main` after 0.7.0. The version number is still 0.7.0.

- **Pumpkin plugin** ([#8]): Friends on [Pumpkin](https://github.com/Pumpkin-MC/Pumpkin), the Rust server, as a
  WebAssembly plugin (`friends-pumpkin.wasm`, in `PumpkinMC/`). Same commands, `config.yml`, tables and messages as
  on Paper, SQLite or MySQL/MariaDB storage, and the API over plugin IPC. Tested on the Pumpkin nightly of 2026-11-06
  (Minecraft 26.3). What differs from Paper: the permission is `friends:use`, player names are not tab-completed,
  `mysql.use-ssl: true` is refused, and there are no LuckPerms prefixes. Details:
  [`PumpkinMC/README.md`](PumpkinMC/README.md).
- Publishing a release also attaches `friends-pumpkin.wasm`.

## 0.7.0 (2026-10-03)

Moderation ([#7]).

- `/f ignore <player>`, `/f unignore <player>` and `/f ignore` (the list). Ignoring a player silently ends a friendship
  with them, drops pending requests both ways and blocks their `/msg`, `/r` and friend requests. Stored in a new
  `friends_ignores` table, created at startup.
- Private message rate limit: `private-messages.rate-limit`, by default 3 messages per 3 seconds per player.
- AFK auto-reply: a message to a friend whose status is away gets an "I'm currently AFK" reply, at most once every
  5 minutes per friend.
- API: `FriendsAPI.sendMessage`, the results `RATE_LIMITED` and `IGNORED`, `PlayerActivityChangeEvent` (Velocity and
  BungeeCord), and `LastSeen.presence(...)` / `LastSeen.line(...)`.
- Config upgrades also add new keys inside existing sections (here `private-messages.rate-limit`), indented like the
  file.

Several proxies: upgrade every proxy. A proxy still on 0.6.0 doesn't check ignore lists.

## 0.6.0 (2026-10-03)

Social features ([#6]).

- Private messages between friends: `/msg` (also `/tell`, `/w`, `/whisper`) and `/r` (also `/reply`), across the
  network, never stored. `private-messages.enabled: false` leaves those commands to another plugin.
- Last seen: `/f list` shows "Last seen 17 minutes ago" for offline friends, and `/msg` to an offline friend says when
  they were last seen.
- Presence rules (proxies only): `presence.rules` turns backend server names into "Playing BedWars Solo" in `/f list`.
- API: `FriendMessageEvent` (cancellable, can rewrite the text), `LastSeen` and `FriendsAPI.getActivity`.
- Sections missing from an existing `config.yml` are added at startup, with their comments.

Several proxies: upgrade every proxy together. A private message to a player on a proxy still on 0.5.0 is lost.

## 0.5.0 (2026-10-02), released as "1.0.0"

- Public API for other plugins ([#3]): `FriendsAPI` in `friends-api.jar` (Java 8), with six events on Paper, Velocity
  and BungeeCord.
- Folia support; the code is split into `api/`, `core/`, `paper/`, `bungee/` and `velocity/`, and every build writes
  the jars to `builds/` ([#4]).
- Publishing a GitHub release attaches the jars to it ([#5]).

## 0.4.0 (2026-09-28)

- BungeeCord plugin, and one Paper jar for Paper 1.8.8 to 26.3, on the shared core ([#2]).

## 0.3.0 (2026-09-28)

- Several proxies: a shared MySQL database plus Redis for presence, requests and notifications across proxies.
  Fixes for races between the in-memory cache and the database ([#1]).

## 0.2.0 (2026-09-28)

- First version on Velocity 4.2 (Java 25).

[#1]: https://github.com/Macaron27/Friends/pull/1
[#2]: https://github.com/Macaron27/Friends/pull/2
[#3]: https://github.com/Macaron27/Friends/pull/3
[#4]: https://github.com/Macaron27/Friends/pull/4
[#5]: https://github.com/Macaron27/Friends/pull/5
[#6]: https://github.com/Macaron27/Friends/pull/6
[#7]: https://github.com/Macaron27/Friends/pull/7
[#8]: https://github.com/Macaron27/Friends/pull/8
