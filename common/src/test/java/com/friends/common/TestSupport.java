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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    static final class FakePlatform implements Platform {
        final Map<UUID, Online> online = new ConcurrentHashMap<>();

        @Override
        public Optional<Online> player(UUID id) {
            return Optional.ofNullable(online.get(id));
        }

        @Override
        public Optional<Online> player(String name) {
            return online.values().stream().filter(o -> o.name().equalsIgnoreCase(name)).findFirst();
        }

        @Override
        public Collection<String> onlineNames() {
            return online.values().stream().map(Online::name).sorted().toList();
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
            this.storage = storage;
            this.friends = new Friends(storage, platform, Runnable::run, clock, Duration.ofMinutes(5), maxFriends,
                    LoggerFactory.getLogger(Harness.class));
            this.command = new FriendCommand(friends, platform);
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
