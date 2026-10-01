"""End-to-end tests on real servers with bot players.

  python3 tools/e2e/run.py <jars-dir> [scenario ...]

<jars-dir> holds the downloaded servers: paper-<version>.jar, BungeeCord.jar, velocity.jar (see README).
Scenarios: paper-<version> (full friend flow with bot players: 1.8.8, 1.12.2, 1.16.5, 1.20.6, 1.21.11, 26.3),
load-<version> (plugin enables and answers the console), bungee[-<version>] / velocity[-<version>] (the proxy
plugin with players and a Paper backend of that version, default 1.8.8), mixed (BungeeCord + Velocity sharing MySQL and Redis; needs
FRIENDS_MYSQL=host:port/db:user:password and FRIENDS_REDIS=host:port), api-<version> / api-bungee / api-velocity (another
plugin, tools/e2e/probe, using the API and its events). Servers run in <jars-dir>/e2e-*.
"""
import os
import platform
import shutil
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(__file__))
from mcbot import Bot  # noqa: E402
import servers  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
VERSION = "0.5.0"
PLUGIN = {m: os.path.join(ROOT, m, "build", "libs", f"friends-{m}-{VERSION}.jar") for m in ("paper", "bungee", "velocity")}
PROBE = os.path.join(ROOT, "tools", "e2e", "probe", "build", "libs", "friends-probe.jar")
PROTOCOL = {"1.8.8": 47, "1.12.2": 340, "1.16.5": 754, "1.20.6": 766, "1.21.11": 774, "26.3": 777}
PAUSE = 0.4  # keeps well under Spigot's chat spam filter


def cmd(bot, line, expect, timeout=10):
    mark = bot.mark()
    bot.say(line)
    time.sleep(PAUSE)
    return bot.wait_for(expect, mark, timeout)


def friend_flow(alice, bob, where):
    """The same player story on every platform. `where`: how Alice sees Bob online ("is online" / "is in lobby")."""
    cmd(alice, "/f", "Friend Commands:")
    cmd(alice, "/f add Bob", "You sent a friend request to Bob! They have 5 minutes to accept it!")
    _, raw = bob.wait_for("Friend request from Alice")
    assert "/f accept Alice" in raw and "/f deny Alice" in raw, f"clickable buttons missing: {raw}"
    mark = alice.mark()
    cmd(bob, "/f accept Alice", "You are now friends with Alice")
    alice.wait_for("You are now friends with Bob", mark)
    cmd(alice, "/fl", f"Bob {where}")
    cmd(bob, "/status busy", "Your status is now Busy.")
    cmd(alice, "/f list", "Bob is busy")
    cmd(bob, "/status online", "Your status is now Online.")
    cmd(alice, "/f nickname Bob Bobby", "You'll now see Bob as Bobby.")
    mark = alice.mark()
    bob.close()
    alice.wait_for("Friend > Bobby left.", mark)
    mark = alice.mark()
    bob.connect()
    alice.wait_for("Friend > Bobby joined.", mark)
    time.sleep(1)
    cmd(alice, "/f remove Bob", "You removed Bob from your friends list!")
    cmd(alice, "/f list", "You don't have any friends yet!")


def players(port, protocol=47, names=("Alice", "Bob")):
    bots = []
    for name in names:
        bots.append(Bot(name, port, protocol).connect())
        time.sleep(0.5)
    time.sleep(1.5)  # let the join (and the friends data load) settle
    return bots


def check_clean(*srvs):
    for s in srvs:
        bad = s.errors()
        assert not bad, f"{s.name} logged errors:\n" + "".join(bad[:10])


def mysql_settings():
    host_port, rest = os.environ["FRIENDS_MYSQL"].split("/", 1)
    database, user, password = (rest.split(":") + [""])[:3]
    return {"storage.type": "mysql", "mysql.host": host_port.split(":")[0], "mysql.port": int(host_port.split(":")[1]),
            "mysql.database": database, "mysql.user": user, "mysql.password": password}


def scenario_paper(jars, version):
    port = 25700 + list(PROTOCOL).index(version)
    s = servers.paper(os.path.join(jars, f"e2e-paper-{version}"), os.path.join(jars, f"paper-{version}.jar"), port, PLUGIN["paper"])
    if version == "1.12.2" and platform.system() == "Darwin" and platform.machine() == "arm64":
        # Paper 1.12.2's own sqlite-jdbc (3.21.0.1) has no Apple Silicon native; Linux is fine. Use MySQL here.
        servers.plugin_config(s.folder, **mysql_settings())
    try:
        s.start()
        s.wait_log(r"Enabling Friends")
        alice, bob = players(port, PROTOCOL[version])
        friend_flow(alice, bob, "is online")
        for b in (alice, bob):
            b.close()
        check_clean(s)
    finally:
        s.stop()
    s.wait_log(r"Disabling Friends")


def scenario_load(jars, version):
    port = 25710 + ["1.20.6", "1.21.11", "26.3", "1.8.8", "1.12.2", "1.16.5"].index(version)
    s = servers.paper(os.path.join(jars, f"e2e-load-{version}"), os.path.join(jars, f"paper-{version}.jar"), port, PLUGIN["paper"])
    try:
        s.start()
        s.wait_log(r"Enabling Friends")
        mark = len(s.lines)
        s.console("friend")
        s.wait_log(r"Only players can use this command", mark)
        assert os.path.exists(os.path.join(s.folder, "plugins", "Friends", "friends.db")), "no SQLite database created"
        check_clean(s)
    finally:
        s.stop()
    s.wait_log(r"Disabling Friends")


def backend(jars, port, version="1.8.8"):
    return servers.paper(os.path.join(jars, f"e2e-backend-{port}"), os.path.join(jars, f"paper-{version}.jar"), port)


def scenario_proxy(jars, kind, version="1.8.8"):
    """The proxy plugin with players (and a Paper backend) of `version`."""
    base = 25730 + 4 * list(PROTOCOL).index(version) + (0 if kind == "bungee" else 2)
    back_port, proxy_port = base, base + 1
    back = backend(jars, back_port, version)
    make = servers.bungee if kind == "bungee" else servers.velocity
    jar = os.path.join(jars, "BungeeCord.jar" if kind == "bungee" else "velocity.jar")
    proxy = make(os.path.join(jars, f"e2e-{kind}-{version}"), jar, proxy_port, back_port, PLUGIN[kind])
    try:
        back.start()
        proxy.start()
        alice, bob = players(proxy_port, PROTOCOL[version])
        friend_flow(alice, bob, "is in lobby")
        for b in (alice, bob):
            b.close()
        check_clean(proxy)
    finally:
        proxy.stop("end")
        back.stop()


def api_flow(srv, alice, bob):
    """What the probe plugin sees and does through the API: events (one cancelled), reads and actions from listeners."""
    srv.wait_log(r"probe: api ok")
    mark = len(srv.lines)
    cmd(alice, "/f add Bob", "You sent a friend request to Bob!")
    srv.wait_log(r"probe: FriendRequestSendEvent Alice->Bob", mark)
    cmd(bob, "/f accept Alice", "You are now friends with Alice")
    for line in (r"probe: FriendAddEvent Bob->Alice", r"probe: FriendAddedEvent Bob->Alice",
                 r"probe: areFriends=true friends of Bob=\[Alice\]", r"probe: FriendStatusChangeEvent Bob ONLINE->BUSY",
                 r"probe: setStatus Bob BUSY -> SUCCESS"):
        srv.wait_log(line, mark)
    cmd(alice, "/fl", "Bob is busy")  # the probe's API call, as players see it
    bob.say("/status away")           # the probe cancels this one: no reply, nothing changes
    srv.wait_log(r"probe: FriendStatusChangeEvent Bob BUSY->AWAY cancelled", mark)
    time.sleep(PAUSE)
    cmd(alice, "/fl", "Bob is busy")
    cmd(alice, "/f remove Bob", "You removed Bob from your friends list!")
    srv.wait_log(r"probe: FriendRemoveEvent Alice->Bob", mark)
    srv.wait_log(r"probe: FriendRemovedEvent Alice->Bob", mark)


def scenario_api(jars, where):
    """The probe on Paper <where> (a version), or on BungeeCord / Velocity (with a 1.8.8 backend)."""
    if where in ("bungee", "velocity"):
        back_port, proxy_port = 25800 + (0 if where == "bungee" else 2), 25801 + (0 if where == "bungee" else 2)
        back = backend(jars, back_port)
        make = servers.bungee if where == "bungee" else servers.velocity
        jar = os.path.join(jars, "BungeeCord.jar" if where == "bungee" else "velocity.jar")
        srv = make(os.path.join(jars, f"e2e-api-{where}"), jar, proxy_port, back_port, PLUGIN[where])
        port, protocol, stop = proxy_port, 47, "end"
    else:
        back, port, protocol, stop = None, 25810 + list(PROTOCOL).index(where), PROTOCOL[where], "stop"
        srv = servers.paper(os.path.join(jars, f"e2e-api-{where}"), os.path.join(jars, f"paper-{where}.jar"), port, PLUGIN["paper"])
    shutil.copy(PROBE, os.path.join(srv.folder, "plugins"))
    try:
        if back:
            back.start()
        srv.start()
        alice, bob = players(port, protocol)
        api_flow(srv, alice, bob)
        for b in (alice, bob):
            b.close()
        if not back:  # Bukkit: every event asynchronous, never on the server thread
            probe = [line for line in srv.lines if "probe: Friend" in line]
            assert probe and all("async=true main=false" in line for line in probe), "".join(probe)
        check_clean(srv)
    finally:
        srv.stop(stop)
        if back:
            back.stop()


def scenario_mixed(jars):
    rhost, rport = os.environ["FRIENDS_REDIS"].split(":")
    shared = {**mysql_settings(), "redis.enabled": True, "redis.host": rhost, "redis.port": int(rport),
              "redis.namespace": f"friends-e2e-{int(time.time())}"}
    back = backend(jars, 25790)
    bungee = servers.bungee(os.path.join(jars, "e2e-mixed-bungee"), os.path.join(jars, "BungeeCord.jar"), 25791, 25790, PLUGIN["bungee"])
    velocity = servers.velocity(os.path.join(jars, "e2e-mixed-velocity"), os.path.join(jars, "velocity.jar"), 25792, 25790, PLUGIN["velocity"])
    servers.plugin_config(bungee.folder, **shared, **{"redis.proxy-id": "bungee"})
    servers.plugin_config(velocity.folder, **shared, **{"redis.proxy-id": "velocity"})
    try:
        back.start()
        bungee.start()
        velocity.start()
        alice = Bot("Alice", 25791).connect()  # through BungeeCord
        time.sleep(0.5)
        bob = Bot("Bob", 25792).connect()      # through Velocity
        time.sleep(2)
        friend_flow(alice, bob, "is in lobby")
        for b in (alice, bob):
            b.close()
        check_clean(bungee, velocity)
    finally:
        velocity.stop("end")
        bungee.stop("end")
        back.stop()


def main():
    jars = os.path.abspath(sys.argv[1])
    wanted = sys.argv[2:] or [f"paper-{v}" for v in PROTOCOL] + ["bungee", "velocity", "bungee-26.3", "velocity-26.3",
                                                                 "api-1.8.8", "api-1.20.6", "api-26.3", "api-bungee", "api-velocity"]
    failed = []
    for name in wanted:
        start = time.time()
        try:
            if name.startswith("paper-"):
                scenario_paper(jars, name[6:])
            elif name.startswith("api-"):
                scenario_api(jars, name[4:])
            elif name.startswith("load-"):
                scenario_load(jars, name[5:])
            elif name.split("-")[0] in ("bungee", "velocity"):
                kind, _, version = name.partition("-")
                scenario_proxy(jars, kind, version or "1.8.8")
            elif name == "mixed":
                scenario_mixed(jars)
            else:
                raise ValueError(f"unknown scenario {name}")
            print(f"PASS {name} ({time.time() - start:.0f}s)", flush=True)
        except Exception:  # noqa: BLE001 - report every scenario
            failed.append(name)
            print(f"FAIL {name} ({time.time() - start:.0f}s)\n{traceback.format_exc()}", flush=True)
    print(f"\n{len(wanted) - len(failed)}/{len(wanted)} scenarios passed" + (f"; failed: {', '.join(failed)}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
