package com.friends.common;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.slf4j.LoggerFactory;

import com.friends.common.Platform.Online;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class TestSupport {
    private TestSupport() {}

    /** SQLite in {@code dir}, or MySQL when run with -Dfriends.mysql=host:port/db:user:password. */
    static Storage open(Path dir, boolean mysql) throws SQLException {
        if (!mysql) return Storage.sqlite(dir.resolve("friends.db"));
        String spec = System.getProperty("friends.mysql");
        assumeTrue(spec != null && !spec.isBlank(), "set -Dfriends.mysql=host:port/db:user:password to run MySQL tests");
        String[] hostDb = spec.split(":", 3); // host, port/db, user:password
        String[] portDb = hostDb[1].split("/", 2);
        String[] userPass = hostDb[2].split(":", 2);
        String url = "jdbc:mysql://%s:%s/%s?sslMode=DISABLED".formatted(hostDb[0], portDb[0], portDb[1]);
        try (var c = DriverManager.getConnection(url, userPass[0], userPass.length > 1 ? userPass[1] : "");
             var st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS friends_friendships, friends_players");
        }
        return Storage.mysql(hostDb[0], Integer.parseInt(portDb[0]), portDb[1], userPass[0],
                userPass.length > 1 ? userPass[1] : "", false);
    }

    static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Every click command in a component tree. */
    static List<String> clicks(Component c) {
        List<String> out = new ArrayList<>();
        if (c.clickEvent() != null && c.clickEvent().action() == ClickEvent.Action.RUN_COMMAND) {
            out.add(((ClickEvent.Payload.Text) c.clickEvent().payload()).value());
        }
        c.children().forEach(child -> out.addAll(clicks(child)));
        return out;
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    static final class Inbox implements Audience {
        final List<Component> messages = new CopyOnWriteArrayList<>();

        @Override
        public void sendMessage(Component message) {
            messages.add(message);
        }
    }

    /**
     * A single-threaded "slow database": SQL tasks queue up and run in random FIFO batches between commands, so any
     * logic that depends on when a write actually lands (rather than on queue order) shows up deterministically.
     */
    static final class LaggingDb implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        void drain() {
            while (!queue.isEmpty()) queue.poll().run();
        }

        void runSome(java.util.Random random) {
            for (int n = random.nextInt(3); n > 0 && !queue.isEmpty(); n--) queue.poll().run(); // 0-2 tasks: writes pile up
        }
    }

    static final class FakePlatform implements Platform {
        final Map<UUID, Online> online = new ConcurrentHashMap<>();

        @Override
        public Optional<Online> player(UUID id) {
            return Optional.ofNullable(online.get(id));
        }
    }

    /**
     * In-memory stand-in for Redis: shared state with the same semantics as {@link RedisNetwork#publish}, and
     * synchronous fan-out to the other proxies. {@code hold} queues deliveries so tests can reorder them.
     */
    static final class Hub {
        final Map<UUID, Presence> online = new ConcurrentHashMap<>();
        final Map<Request.Key, Request> requests = new ConcurrentHashMap<>();
        final Set<Set<UUID>> claims = ConcurrentHashMap.newKeySet(); // like Redis' 2s claim keys; tests clear it
        final List<Node> nodes = new CopyOnWriteArrayList<>();
        final List<Runnable> held = new ArrayList<>();
        boolean hold;

        Node node(String proxy) {
            return new Node(proxy);
        }

        void release() {
            List<Runnable> batch = List.copyOf(held);
            held.clear();
            batch.forEach(Runnable::run);
        }

        Network.Snapshot snapshot() {
            return new Network.Snapshot(Map.copyOf(online), List.copyOf(requests.values()));
        }

        final class Node implements Network {
            final String proxy;
            Listener listener;

            Node(String proxy) {
                this.proxy = proxy;
            }

            @Override public String proxyId() { return proxy; }

            @Override
            public boolean claim(UUID a, UUID b) {
                return claims.add(Set.of(a, b));
            }

            @Override
            public void start(Listener listener) {
                this.listener = listener;
                nodes.add(this);
                listener.resync(snapshot());
            }

            @Override
            public void publish(Event event) {
                switch (event) {
                    case Event.Joined(UUID id, Presence p) -> online.put(id, p);
                    case Event.Updated(UUID id, Presence p) -> online.put(id, p);
                    case Event.Left(UUID id, String from, Instant _) -> online.computeIfPresent(id, (_, p) -> p.proxy().equals(from) ? null : p);
                    case Event.RequestSent(Request r) -> requests.put(r.key(), r);
                    case Event.RequestDenied(UUID from, UUID to) -> requests.remove(new Request.Key(from, to));
                    case Event.Befriended(var a, var b, Instant _) -> {
                        requests.remove(new Request.Key(a.id(), b.id()));
                        requests.remove(new Request.Key(b.id(), a.id()));
                    }
                    case Event.Unfriended _, Event.ProxyDown _ -> {}
                }
                for (Node n : nodes) {
                    if (n == this) continue;
                    Runnable delivery = () -> n.listener.event(event);
                    if (hold) held.add(delivery); else delivery.run();
                }
            }

            @Override
            public void close() {
                nodes.remove(this);
            }
        }
    }

    record TestPlayer(Online online, Inbox inbox) {
        UUID id() {
            return online.id();
        }

        String last() {
            return inbox.messages.isEmpty() ? "" : plain(inbox.messages.getLast());
        }

        Component lastComponent() {
            return inbox.messages.getLast();
        }

        String all() {
            return String.join("\n", inbox.messages.stream().map(TestSupport::plain).toList());
        }

        void clear() {
            inbox.messages.clear();
        }
    }

    /** Wires the core like the Velocity plugin does, but with a direct DB executor and a controllable clock. */
    static final class Harness implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final FakePlatform platform = new FakePlatform();
        final Storage storage;
        final Friends friends;
        final FriendCommand command;

        Harness(Storage storage, int maxFriends) {
            this(storage, maxFriends, Network.LOCAL, Runnable::run);
        }

        /** One proxy: its own players, clock and caches, sharing {@code storage} (and {@code network}) with others. */
        Harness(Storage storage, int maxFriends, Network network, java.util.concurrent.Executor db) {
            this.storage = storage;
            this.friends = new Friends(storage, platform, network, db, clock, Duration.ofMinutes(5), maxFriends,
                    LoggerFactory.getLogger(Harness.class));
            this.command = new FriendCommand(friends);
            friends.start();
        }

        TestPlayer join(String name) {
            return join(name, null);
        }

        TestPlayer join(String name, String prefix) {
            Online online = new Online(uuid(name), name, prefix, "lobby", new Inbox());
            platform.online.put(online.id(), online);
            friends.connect(online).join();
            friends.greet(online);
            return new TestPlayer(online, (Inbox) online.audience());
        }

        void quit(TestPlayer p) {
            friends.disconnect(p.online());
            platform.online.remove(p.id());
        }

        void run(TestPlayer p, String line) {
            command.execute(p.online(), line.isEmpty() ? new String[0] : line.split(" ")).join();
        }

        List<String> suggest(TestPlayer p, String... args) {
            return command.suggest(p.online(), args);
        }

        void befriend(TestPlayer a, TestPlayer b) {
            run(a, "add " + b.online().name());
            run(b, "accept " + a.online().name());
        }

        @Override
        public void close() {
            storage.close();
        }
    }
}
