package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import com.friends.api.Status;
import com.friends.common.Storage.PlayerRow;
import com.friends.common.TestSupport.Harness;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.args.ClientType;
import redis.clients.jedis.params.ClientKillParams;

/** Real-Redis checks; the Redis-backed ones need -Dfriends.redis=host:port (they use a throwaway namespace). */
class RedisNetworkTest {
    @TempDir
    Path dir;
    final String namespace = "friends-test-" + UUID.randomUUID().toString().substring(0, 8);
    final List<AutoCloseable> cleanup = new ArrayList<>();
    Storage storage;

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : cleanup.reversed()) c.close();
        if (storage != null) storage.close();
    }

    @Test
    void everyEventSurvivesTheWireFormat() {
        Instant t = Instant.ofEpochMilli(1_790_000_000_123L);
        PlayerRow a = new PlayerRow(TestSupport.uuid("A"), "A", "&b[VIP] ", t);
        PlayerRow b = new PlayerRow(TestSupport.uuid("B"), "B", null, t);
        Presence p = new Presence("A", "&b[VIP] ", "p1", null, Status.AWAY);
        List<Event> events = List.of(
                new Event.Joined(a.id(), p),
                new Event.Updated(a.id(), p.withServer("lobby")),
                new Event.Left(a.id(), "p1", t),
                new Event.RequestSent(new Request(a, b, t)),
                new Event.RequestDenied(a.id(), b.id()),
                new Event.Befriended(a, b, t),
                new Event.Unfriended(a.id(), List.of(b.id())),
                new Event.ProxyDown("p1"));
        assertEquals(Event.class.getPermittedSubclasses().length, events.size(), "cover every event type");
        for (Event e : events) {
            assertEquals(e, RedisNetwork.GSON.fromJson(RedisNetwork.GSON.toJson(e), e.getClass()));
        }
    }

    // --- against a real Redis ---

    String[] redisAddress() {
        String spec = System.getProperty("friends.redis");
        assumeTrue(spec != null && !spec.isBlank(), "set -Dfriends.redis=host:port to run Redis tests");
        return spec.split(":", 2);
    }

    RedisNetwork network(String proxy) throws Exception {
        String[] hp = redisAddress();
        var n = new RedisNetwork(hp[0], Integer.parseInt(hp[1]), null, 0, false, namespace, proxy,
                Duration.ofMillis(100), Duration.ofMillis(500), LoggerFactory.getLogger("redis-" + proxy));
        cleanup.add(n);
        return n;
    }

    Harness proxy(RedisNetwork network) throws Exception {
        if (storage == null) storage = Storage.sqlite(dir.resolve("friends.db"));
        var h = new Harness(storage, 5000, network, Runnable::run);
        h.clock.now = Instant.now(); // Redis TTLs are computed from wall-clock time, like in production
        return h;
    }

    RedisClient client() {
        String[] hp = redisAddress();
        RedisClient c = RedisClient.create(hp[0], Integer.parseInt(hp[1]));
        cleanup.add(c);
        return c;
    }

    /** Events cross proxies asynchronously: retry the assertion for a few seconds. */
    static void eventually(Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) throw e;
                Thread.sleep(25);
            }
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected <" + needle + "> in:\n" + haystack);
    }

    @Test
    void friendsAcrossTwoProxies() throws Exception {
        var p1 = proxy(network("p1"));
        var p2 = proxy(network("p2"));
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        eventually(() -> assertTrue(p1.suggest(alice, "add", "").contains("Bob")));

        p1.run(alice, "add Bob");
        eventually(() -> assertContains(bob.all(), "Friend request from Alice"));
        p2.run(bob, "accept Alice");
        eventually(() -> assertContains(alice.all(), "You are now friends with Bob"));
        p1.run(alice, "list");
        assertContains(alice.last(), "Bob is in lobby");

        p2.quit(bob);
        eventually(() -> assertEquals("Friend > Bob left.", alice.last()));
    }

    @Test
    void onlyOneProxyCanClaimAPairAtATime() throws Exception {
        var n1 = network("p1");
        var n2 = network("p2");
        UUID a = TestSupport.uuid("A");
        UUID b = TestSupport.uuid("B");
        assertTrue(n1.claim(a, b));
        assertTrue(!n2.claim(b, a), "same pair, either order");
        assertTrue(n2.claim(a, TestSupport.uuid("C")));
        eventually(() -> assertTrue(n2.claim(b, a), "claims expire after 2s")); // eventually() waits up to 5s
    }

    @Test
    void aLateProxyStartsFromTheSnapshot() throws Exception {
        var p1 = proxy(network("p1"));
        var alice = p1.join("Alice");
        p1.quit(p1.join("Dave"));
        p1.run(alice, "add Dave");
        eventually(() -> assertEquals(1, client().keys(namespace + ":request:*").size()));

        var p3 = proxy(network("p3")); // start() waits for the first snapshot
        var dave = p3.join("Dave");
        assertContains(dave.all(), "You have 1 pending friend request.");
        assertTrue(p3.friends.onlineNames().contains("Alice"));
    }

    @Test
    void aProxyThatStopsHeartbeatingIsReaped() throws Exception {
        var p1 = proxy(network("p1"));
        var net2 = network("p2");
        var p2 = proxy(net2);
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        eventually(() -> assertContains(bob.all(), "Friend request from Alice"));
        p2.run(bob, "accept Alice");
        eventually(() -> assertContains(alice.all(), "You are now friends with Bob"));

        net2.heartbeat.shutdownNow(); // p2 "freezes": no Left events, its heartbeat key expires
        eventually(() -> assertEquals("Friend > Bob left.", alice.last()));
        assertTrue(client().hget(namespace + ":online", bob.id().toString()) == null);
    }

    @Test
    void afterLosingTheSubscriptionAndTheDataAProxyReannouncesItsPlayers() throws Exception {
        var p1 = proxy(network("p1"));
        var alice = p1.join("Alice");
        var redis = client();
        eventually(() -> assertTrue(redis.hexists(namespace + ":online", alice.id().toString())));

        redis.del(namespace + ":online");            // e.g. Redis restarted without persistence
        String[] hp = redisAddress();
        try (var admin = new Jedis(hp[0], Integer.parseInt(hp[1]))) { // drop every subscription
            admin.clientKill(ClientKillParams.clientKillParams()
                    .type(ClientType.PUBSUB));
        }
        eventually(() -> assertTrue(redis.hexists(namespace + ":online", alice.id().toString())));
    }
}
