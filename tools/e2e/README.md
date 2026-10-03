# End-to-end tests on real servers

Starts real Paper / BungeeCord / Velocity servers on `127.0.0.1` (offline mode) in throwaway folders, connects
bot players (`mcbot.py`: Minecraft 1.8, 1.12.2, 1.16.5, 1.20.6, 1.21.11, 26.1, 26.3 protocols) and plays the same
friend story everywhere: help, add, clickable accept/deny, accept, list, status, nickname, leave/join
notifications, remove.

1. `./gradlew build`
2. Put the server jars in one folder (not included; running Paper means accepting the
   [Minecraft EULA](https://aka.ms/MinecraftEULA), which the scripts write to `eula.txt`):
   - `paper-<version>.jar` from <https://papermc.io/downloads> (e.g. 1.8.8, 1.12.2, 1.16.5, 1.20.6, 1.21.11, 26.3)
   - `folia-<version>.jar` from <https://papermc.io/downloads/folia> (e.g. 1.21.11)
   - `BungeeCord.jar` from <https://ci.md-5.net/job/BungeeCord/>
   - `velocity.jar` from <https://papermc.io/downloads/velocity>
3. Run (needs Python 3 with PyYAML, Java 25):

```bash
python3 tools/e2e/run.py /path/to/jars                  # all Paper versions + both proxies
python3 tools/e2e/run.py /path/to/jars paper-26.3 bungee-26.3
FRIENDS_MYSQL=127.0.0.1:3306/friends:root: FRIENDS_REDIS=127.0.0.1:6379 python3 tools/e2e/run.py /path/to/jars mixed
```

Scenarios: `paper-<version>`, `folia-<version>` (the Paper jar on Folia), `load-<version>` (enables and answers the console only), `bungee[-<version>]`,
`velocity[-<version>]` (proxy plugin, players and a Paper backend of that version, default 1.8.8), `mixed`
(Alice through BungeeCord, Bob through Velocity, sharing MySQL and Redis), `api-<version>` / `api-bungee` /
`api-velocity` (another plugin, [`probe`](probe), using the API: its events, one cancelled, reads and actions from its
listeners and, on Paper, from the server thread; needs `./gradlew :probe:jar`), `upgrade-paper` / `upgrade-velocity`
(the previous release's jar, then this build on the same database and config.yml: friendships kept, new config
sections appended, new features working; set `FRIENDS_OLD_JARS` to a folder holding the previous `friends-paper.jar` /
`friends-velocity.jar`).
