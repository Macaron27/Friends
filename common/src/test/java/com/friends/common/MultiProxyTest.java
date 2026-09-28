package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.friends.common.TestSupport.Harness;
import com.friends.common.TestSupport.Hub;
import com.friends.common.TestSupport.TestPlayer;

/** Two (or three) proxies sharing one database and an in-memory stand-in for Redis. */
class MultiProxyTest {
    @TempDir
    Path dir;
    Storage storage;
    Hub hub;
    Harness p1;
    Harness p2;

    @BeforeEach
    void setUp() throws Exception {
        storage = Storage.sqlite(dir.resolve("friends.db"));
        hub = new Hub();
        p1 = proxy("p1");
        p2 = proxy("p2");
    }

    Harness proxy(String id) {
        return new Harness(storage, 5000, hub.node(id), Runnable::run);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected <" + needle + "> in:\n" + haystack);
    }

    @Test
    void requestAcceptAndListAcrossProxies() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");

        p1.run(alice, "add Bob");
        assertContains(alice.last(), "You sent a friend request to Bob!");
        assertContains(bob.last(), "Friend request from Alice");

        p2.run(bob, "accept Alice");
        assertContains(bob.last(), "You are now friends with Alice");
        assertContains(alice.last(), "You are now friends with Bob");

        p1.run(alice, "list");
        assertContains(alice.last(), "Bob is in lobby");
        p2.run(bob, "list");
        assertContains(bob.last(), "Alice is in lobby");
    }

    @Test
    void joinLeaveAndStatusReachFriendsOnOtherProxies() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "accept Alice");

        p2.quit(bob);
        assertEquals("Friend > Bob left.", alice.last());
        p1.run(alice, "list");
        assertContains(alice.last(), "Bob is currently offline");

        bob = p2.join("Bob");
        assertEquals("Friend > Bob joined.", alice.last());

        p2.run(bob, "status busy");
        p1.run(alice, "list");
        assertContains(alice.last(), "Bob is busy");
    }

    @Test
    void serverSwitchesAreVisibleEverywhere() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "accept Alice");

        var moved = new Platform.Online(bob.id(), "Bob", null, "bedwars-3", bob.inbox());
        p2.platform.online.put(bob.id(), moved);
        p2.friends.moved(moved);
        p1.run(alice, "list");
        assertContains(alice.last(), "Bob is in bedwars-3");
    }

    @Test
    void removingOnOneProxyUpdatesTheOther() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "accept Alice");

        p1.run(alice, "remove Bob");
        assertEquals(List.of(), p2.friends.friendNames(bob.id()));
        p2.run(bob, "list");
        assertContains(bob.last(), "You don't have any friends yet!");
    }

    @Test
    void deniedRequestsDisappearEverywhere() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "deny Alice");
        p1.run(alice, "requests");
        assertContains(alice.last(), "You don't have any pending friend requests.");
    }

    @Test
    void mutualRequestsFromDifferentProxiesBecomeFriends() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "add Alice");
        assertContains(bob.last(), "You are now friends with Alice");
        assertEquals(List.of("Bob"), p1.friends.friendNames(alice.id()));
    }

    @Test
    void expiryIsAnnouncedOnceByEachPlayersOwnProxy() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");

        p1.clock.advance(Duration.ofMinutes(6));
        p2.clock.advance(Duration.ofMinutes(6));
        p1.friends.expireRequests();
        p2.friends.expireRequests();
        assertEquals(1, alice.all().split("Your friend request to Bob has expired.", -1).length - 1);
        assertEquals(1, bob.all().split("The friend request from Alice has expired.", -1).length - 1);
    }

    @Test
    void tabCompletionAndAddSeeTheWholeNetworkButNotInvisiblePlayers() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p2.join("Carol");
        assertEquals(List.of("Bob", "Carol"), p1.suggest(alice, "add", ""));

        p2.run(bob, "status offline");
        assertEquals(List.of("Carol"), p1.suggest(alice, "add", ""));
        p1.run(alice, "add bob"); // still addable by name, like an offline player
        assertContains(alice.last(), "You sent a friend request to Bob!");
    }

    @Test
    void aProxyStartedLaterGetsTheSharedStateFromTheSnapshot() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "accept Alice");
        var carol = p1.join("Carol");
        p1.run(carol, "add Dave");            // Dave is unknown: nothing happens
        p1.quit(p1.join("Dave"));             // Dave has played before
        p1.run(carol, "add Dave");            // request pending for an offline player

        var p3 = proxy("p3");
        var dave = p3.join("Dave");
        assertContains(dave.all(), "You have 1 pending friend request.");
        p3.run(dave, "accept Carol");
        assertContains(carol.last(), "You are now friends with Dave");

        p3.run(dave, "add Alice");
        p1.run(alice, "accept Dave");
        p3.run(dave, "list");
        assertContains(dave.last(), "Alice is in lobby");
    }

    @Test
    void aLateLeaveFromTheOldProxyDoesNotHideAPlayerWhoMovedProxies() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "accept Alice");

        hub.hold = true;           // Bob moves p2 -> p1, but p2's "left" is delivered after p1's "joined"
        p2.quit(bob);
        hub.hold = false;
        bob = p1.join("Bob");
        hub.release();

        p1.run(alice, "list");
        assertContains(alice.last(), "Bob is in lobby");
        var p3 = proxy("p3"); // and the shared state agrees
        var carol = p3.join("Carol");
        assertTrue(p3.suggest(carol, "add", "").contains("Bob"));
    }

    @Test
    void aProxyGoingDownMarksItsPlayersOffline() {
        var alice = p1.join("Alice");
        var bob = p2.join("Bob");
        p1.run(alice, "add Bob");
        p2.run(bob, "accept Alice");

        p1.friends.event(new Event.ProxyDown("p2")); // what RedisNetwork delivers once p2's heartbeat expires
        assertEquals("Friend > Bob left.", alice.last());
        p1.run(alice, "list");
        assertContains(alice.last(), "Bob is currently offline");
    }

    @Test
    void crossingAcceptsOnTwoProxiesCannotResurrectARemovedFriendship() throws Exception {
        // Slow databases, and hold deliveries so each step is explicit.
        var db1 = new TestSupport.LaggingDb();
        var db2 = new TestSupport.LaggingDb();
        var q1 = new Harness(storage, 5000, hub.node("q1"), db1);
        var q2 = new Harness(storage, 5000, hub.node("q2"), db2);
        var alice = connect(q1, db1, "Alice");
        var bob = connect(q2, db2, "Bob");

        hub.hold = true; // both ask at once: neither proxy has seen the other's request yet
        q1.command.execute(alice.online(), "add Bob".split(" "));
        q2.command.execute(bob.online(), "add Alice".split(" "));
        db1.drain();
        db2.drain();
        hub.release(); // now both requests are known everywhere

        q1.command.execute(alice.online(), "accept Bob".split(" "));
        q2.command.execute(bob.online(), "accept Alice".split(" "));
        db2.queue.poll().run();  // q2 decides first, but its insert stays stuck behind a slow disk
        db1.drain();             // q1 decides too (unless q2's claim stops it) and its insert lands
        hub.release();
        q1.command.execute(alice.online(), "remove Bob".split(" "));  // no-op unless q1 befriended
        db1.drain();
        hub.release();
        db2.drain();             // q2's late insert lands last
        hub.release();
        hub.hold = false;

        // Whoever won, both caches must agree with the database.
        List<String> stored = storage.load(alice.id()).friends().stream().map(Friend::name).toList();
        assertEquals(stored, q1.friends.friendNames(alice.id()), "Alice's cache vs database");
        assertEquals(storage.load(bob.id()).friends().stream().map(Friend::name).toList(),
                q2.friends.friendNames(bob.id()), "Bob's cache vs database");
    }

    private static TestPlayer connect(Harness h, TestSupport.LaggingDb db, String name) {
        var o = new Platform.Online(TestSupport.uuid(name), name, null, "lobby", new TestSupport.Inbox());
        h.platform.online.put(o.id(), o);
        var loaded = h.friends.connect(o);
        db.drain();
        loaded.join();
        return new TestPlayer(o, (TestSupport.Inbox) o.audience());
    }

    @Test
    void resyncKeepsAndReannouncesThisProxysOwnPlayers() {
        var alice = p1.join("Alice");
        p2.join("Bob");

        hub.online.clear(); // e.g. Redis restarted and lost its data
        p1.friends.resync(hub.snapshot());
        assertTrue(hub.online.containsKey(alice.id()), "p1 re-published Alice");
        assertTrue(p2.suggest(p2.join("Carol"), "add", "").contains("Alice"));
    }
}
