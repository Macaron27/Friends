"""Start/stop real servers (Paper, BungeeCord, Velocity) in throwaway folders for end-to-end tests."""
import os
import re
import shutil
import subprocess
import threading
import time

JAVA_FLAGS = ["-Xmx1G", "--enable-native-access=ALL-UNNAMED",
              # Paper 1.16.5 refuses Java > 16 unless told otherwise; it runs fine on Java 25.
              "-DPaper.IgnoreJavaVersion=true"]


class Server:
    def __init__(self, name, folder, jar, ready=r"Done \(|Listening on"):
        self.name, self.folder, self.jar, self.ready = name, folder, jar, re.compile(ready)
        self.lines = []
        self.proc = None

    def start(self, timeout=180):
        self.proc = subprocess.Popen(["java", *JAVA_FLAGS, "-jar", self.jar, "nogui"], cwd=self.folder,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(self.ready.search(line) for line in self.lines):
                return self
            if self.proc.poll() is not None:
                break
            time.sleep(0.2)
        raise RuntimeError(f"{self.name} did not start:\n" + "".join(self.lines[-40:]))

    def _pump(self):
        with open(os.path.join(self.folder, "e2e.log"), "w") as log:
            for line in self.proc.stdout:
                line = re.sub(r"\x1b\[[0-9;]*m", "", line)
                self.lines.append(line)
                log.write(line)
                log.flush()

    def console(self, command):
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def wait_log(self, pattern, since=0, timeout=15):
        rx = re.compile(pattern)
        deadline = time.time() + timeout
        while time.time() < deadline:
            for line in self.lines[since:]:
                if rx.search(line):
                    return line
            time.sleep(0.1)
        raise AssertionError(f"{self.name}: no log line matching {pattern!r}; last lines:\n" + "".join(self.lines[-20:]))

    def errors(self):
        """Log lines that mention our plugin together with an error."""
        return [l for l in self.lines if re.search(r"ERROR|Exception|SEVERE", l) and re.search(r"[Ff]riends", l)]

    def stop(self, command="stop", timeout=60):
        if self.proc and self.proc.poll() is None:
            try:
                self.console(command)
                self.proc.wait(timeout)
            except (subprocess.TimeoutExpired, BrokenPipeError, OSError):
                self.proc.kill()


def fresh(folder):
    shutil.rmtree(folder, ignore_errors=True)
    os.makedirs(os.path.join(folder, "plugins"))
    return folder


def paper(folder, server_jar, port, plugin_jar=None):
    fresh(folder)
    shutil.copy(server_jar, os.path.join(folder, "server.jar"))
    if plugin_jar:
        shutil.copy(plugin_jar, os.path.join(folder, "plugins"))
    with open(os.path.join(folder, "eula.txt"), "w") as f:
        f.write("eula=true\n")  # accepted by the user for these local test servers
    with open(os.path.join(folder, "server.properties"), "w") as f:
        f.write(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-type=FLAT\n"
                "generate-structures=false\nallow-nether=false\nspawn-protection=0\nview-distance=2\n"
                "max-players=20\nmotd=friends-e2e\nnetwork-compression-threshold=256\nwhite-list=false\n"
                "enforce-whitelist=false\n")
    with open(os.path.join(folder, "bukkit.yml"), "w") as f:
        f.write("settings:\n  connection-throttle: -1\n  allow-end: false\n")
    return Server(f"paper@{port}", folder, "server.jar")


def bungee(folder, jar, port, backend_port, plugin_jar):
    fresh(folder)
    shutil.copy(jar, os.path.join(folder, "BungeeCord.jar"))
    shutil.copy(plugin_jar, os.path.join(folder, "plugins"))
    with open(os.path.join(folder, "config.yml"), "w") as f:
        f.write(f"""listeners:
- host: 127.0.0.1:{port}
  query_port: {port}
  query_enabled: false
  motd: friends-e2e
  max_players: 20
  priorities: [lobby]
  force_default_server: false
  forced_hosts: {{}}
  tab_list: GLOBAL_PING
  tab_size: 60
  ping_passthrough: false
  proxy_protocol: false
  bind_local_address: true
servers:
  lobby:
    motd: lobby
    address: 127.0.0.1:{backend_port}
    restricted: false
online_mode: false
ip_forward: false
connection_throttle: -1
network_compression_threshold: 256
player_limit: -1
permissions:
  default: []
groups: {{}}
""")
    return Server(f"bungee@{port}", folder, "BungeeCord.jar", ready=r"Listening on")


def velocity(folder, jar, port, backend_port, plugin_jar):
    fresh(folder)
    shutil.copy(jar, os.path.join(folder, "velocity.jar"))
    shutil.copy(plugin_jar, os.path.join(folder, "plugins"))
    toml = subprocess.run(["unzip", "-p", jar, "default-velocity.toml"], capture_output=True, text=True).stdout
    toml = re.sub(r'^bind = .*$', f'bind = "127.0.0.1:{port}"', toml, flags=re.M)
    toml = re.sub(r'^online-mode = .*$', "online-mode = false", toml, flags=re.M)
    toml = re.sub(r'^login-ratelimit = .*$', "login-ratelimit = 0", toml, flags=re.M)
    toml = re.sub(r'(?s)\[servers\].*?\n\[forced-hosts\].*?(?=\n\[)',
                  f'[servers]\nlobby = "127.0.0.1:{backend_port}"\ntry = ["lobby"]\n\n[forced-hosts]\n', toml)
    with open(os.path.join(folder, "velocity.toml"), "w") as f:
        f.write(toml)
    return Server(f"velocity@{port}", folder, "velocity.jar", ready=r"Done \(")


def plugin_config(folder, **values):
    """Write plugins/Friends|friends/config.yml overrides (dotted keys) before the first start."""
    import yaml
    for sub in ("Friends", "friends"):
        path = os.path.join(folder, "plugins", sub)
        os.makedirs(path, exist_ok=True)
        with open(os.path.join(os.path.dirname(__file__), "..", "..", "core", "src", "main", "resources", "config.yml")) as f:
            config = yaml.safe_load(f)
        for key, value in values.items():
            node = config
            *parents, leaf = key.split(".")
            for p in parents:
                node = node.setdefault(p, {})
            node[leaf] = value
        with open(os.path.join(path, "config.yml"), "w") as f:
            yaml.safe_dump(config, f)
