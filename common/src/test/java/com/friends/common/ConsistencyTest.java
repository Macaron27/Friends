package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.friends.common.TestSupport.Harness;
import com.friends.common.TestSupport.Hub;
import com.friends.common.TestSupport.LaggingDb;
import com.friends.common.TestSupport.TestPlayer;

/**
 * Throws random command sequences (and concurrent ones) at the core, then checks the invariants the cache
 * optimisation relies on: every online player's cached friends equal the database, and friendships are symmetric.
 */
class ConsistencyTest {
    @TempDir
    Path dir;

    static final List<String> NAMES = List.of("Ann", "Ben", "Cat", "Dan", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jon");

    /** What must match between cache and database (lastSeen/name are refreshed lazily and may differ). */
    record Link(UUID friend, boolean best, String nickname) {}

    static Set<Link> links(List<Friend> friends) {
        return friends.stream().map(f -> new Link(f.id(), f.best(), f.nickname())).collect(Collectors.toSet());
    }

    static void assertConsistent(Storage storage, List<Harness> proxies, Map<String, TestPlayer> online) throws Exception {
        Map<UUID, Set<UUID>> db = new HashMap<>();
        for (String name : NAMES) {
            UUID id = TestSupport.uuid(name);
            db.put(id, storage.load(id).friends().stream().map(Friend::id).collect(Collectors.toSet()));
        }
        db.forEach((a, friends) -> friends.forEach(b ->
                assertEquals(true, db.get(b).contains(a), "friendship " + a + " -> " + b + " is one-sided")));
        for (TestPlayer p : online.values()) {
            List<Friend> cached = proxies.stream().map(h -> h.friends.cachedFriends(p.id())).filter(l -> !l.isEmpty())
                    .findFirst().orElse(List.of());
            assertEquals(links(storage.load(p.id()).friends()), links(cached), "cache drifted for " + p.online().name());
        }
    }

    @ParameterizedTest(name = "{0} proxies, lagging db: {1}")
    @CsvSource({"1, false, 1", "2, false, 1", "1, true, 1", "2, true, 1", "2, true, 2", "2, true, 3", "2, true, 4", "3, true, 5"})
    void randomCommandSequencesKeepCacheAndDatabaseInSync(int proxyCount, boolean lag, int seed) throws Exception {
        Storage storage = Storage.sqlite(dir.resolve("friends.db"));
        Hub hub = new Hub();
        List<Harness> proxies = new ArrayList<>();
        List<LaggingDb> dbs = new ArrayList<>();
        for (int i = 0; i < proxyCount; i++) {
            LaggingDb db = new LaggingDb();
            dbs.add(db);
            Network network = proxyCount == 1 ? Network.LOCAL : hub.node("p" + i);
            proxies.add(new Harness(storage, 5000, network, lag ? db : Runnable::run));
        }
        Runnable drainAll = () -> {
            while (dbs.stream().anyMatch(d -> !d.queue.isEmpty())) dbs.forEach(d -> {
                while (!d.queue.isEmpty()) d.queue.poll().run();
            });
            hub.claims.clear(); // nothing in flight any more: like Redis' short claim TTL running out
        };
        Map<String, TestPlayer> online = new HashMap<>();
        Map<String, Harness> where = new HashMap<>();
        Random random = new Random(seed * 1000L + proxyCount + (lag ? 100 : 0));
        List<String> crowd = NAMES.subList(0, 4); // few players, so the same pairs keep colliding

        for (int step = 0; step < 3000; step++) {
            String name = crowd.get(random.nextInt(crowd.size()));
            String other = crowd.get(random.nextInt(crowd.size()));
            TestPlayer p = online.get(name);
            if (p == null) { // offline players can only join
                Harness h = proxies.get(random.nextInt(proxies.size()));
                var o = new Platform.Online(TestSupport.uuid(name), name, null, "lobby", new TestSupport.Inbox());
                h.platform.online.put(o.id(), o);
                var loaded = h.friends.connect(o);
                drainAll.run();
                loaded.join();
                h.friends.greet(o);
                online.put(name, new TestPlayer(o, (TestSupport.Inbox) o.audience()));
                where.put(name, h);
                continue;
            }
            Harness h = where.get(name);
            String line = switch (random.nextInt(16)) {
                case 0 -> null;
                case 1, 2, 3, 4 -> "add " + other;
                case 5, 6, 7, 8 -> "accept " + other;
                case 9 -> "deny " + other;
                case 10, 11 -> "remove " + other;
                case 12 -> "best " + other;
                case 13 -> random.nextBoolean() ? "nickname " + other + " N" + step % 7 : "nickname " + other;
                case 14 -> random.nextInt(8) == 0 ? "removeall confirm" : "status " + List.of("online", "busy", "offline").get(random.nextInt(3));
                default -> "tick";
            };
            if (line == null) {
                h.quit(p);
                online.remove(name);
            } else if (line.equals("tick")) {
                Duration d = Duration.ofSeconds(random.nextInt(120));
                proxies.forEach(x -> {
                    x.clock.advance(d);
                    x.friends.expireRequests();
                });
            } else {
                h.command.execute(p.online(), line.split(" ")); // not joined: with a lagging db it may still be queued
            }
            if (lag) dbs.forEach(d -> d.runSome(random));
            else hub.claims.clear();
            if (step % 100 == 0) {
                drainAll.run();
                assertConsistent(storage, proxies, online);
            }
        }
        drainAll.run();
        assertConsistent(storage, proxies, online);
        storage.close();
    }

    @Test
    void concurrentCommandsOnTheRealDatabaseThreadStayConsistent() throws Exception {
        Storage storage = Storage.sqlite(dir.resolve("friends.db"));
        ExecutorService db = Executors.newSingleThreadExecutor(); // like production: one ordered DB thread
        Harness h = new Harness(storage, 5000, Network.LOCAL, db);
        Map<String, TestPlayer> online = new HashMap<>();
        List<String> crowd = NAMES.subList(0, 4); // few players = the same pairs collide constantly
        for (String name : crowd) online.put(name, h.join(name));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<CompletableFuture<?>> commands = new ArrayList<>();
        Set<Throwable> errors = new HashSet<>();
        for (int t = 0; t < 8; t++) {
            Random random = new Random(t);
            pool.execute(() -> {
                for (int i = 0; i < 2000; i++) {
                    TestPlayer p = online.get(crowd.get(random.nextInt(crowd.size())));
                    String other = crowd.get(random.nextInt(crowd.size()));
                    String line = switch (random.nextInt(6)) {
                        case 0, 1 -> "add " + other;
                        case 2, 3 -> "accept " + other;
                        case 4 -> "remove " + other;
                        default -> "best " + other;
                    };
                    var f = h.command.execute(p.online(), line.split(" "));
                    synchronized (commands) {
                        commands.add(f);
                    }
                }
            });
        }
        pool.shutdown();
        assertEquals(true, pool.awaitTermination(30, TimeUnit.SECONDS));
        CompletableFuture.allOf(commands.toArray(CompletableFuture[]::new))
                .exceptionally(e -> {
                    errors.add(e);
                    return null;
                }).get(30, TimeUnit.SECONDS);
        db.submit(() -> {}).get(30, TimeUnit.SECONDS); // drain queued writes
        assertEquals(Set.of(), errors);
        assertEquals(List.of(), online.values().stream().map(TestPlayer::last).filter(m -> m.contains("Something went wrong")).toList());

        assertConsistent(storage, List.of(h), online);
        db.shutdown();
        storage.close();
    }
}
