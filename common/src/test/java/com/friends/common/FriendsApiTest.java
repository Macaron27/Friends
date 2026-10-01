package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.friends.api.FriendRequest;
import com.friends.api.Result;
import com.friends.api.Status;
import com.friends.common.Storage.PlayerRow;
import com.friends.common.TestSupport.Harness;

class FriendsApiTest {
    @TempDir
    Path dir;
    final GatedDb db = new GatedDb();
    final RecordingHooks hooks = new RecordingHooks();
    Harness h;
    FriendsApi api;

    @BeforeEach
    void setUp() throws Exception {
        h = harness(5000);
    }

    @AfterEach
    void tearDown() {
        if (h != null) h.close();
    }

    private Harness harness(int maxFriends) throws Exception {
        if (h != null) h.close();
        h = new Harness(Storage.sqlite(dir.resolve("friends.db")), maxFriends, Network.LOCAL, db, Runnable::run, hooks);
        api = h.api;
        return h;
    }

    private static Result result(CompletableFuture<Result> f) {
        return f.join(); // direct executors: already complete
    }

    private List<String> friendNames(UUID id) {
        return api.getFriends(id).stream().map(com.friends.api.Friend::getName).sorted().toList();
    }

    // --- reads ---

    @Test
    void readsComeFromLoadedPlayersMemory() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);
        h.run(alice, "best Bob");
        h.run(alice, "nickname Bob Bobby");

        assertTrue(api.isLoaded(alice.id()));
        var friends = api.getFriends(alice.id());
        assertEquals(1, friends.size());
        var f = friends.getFirst();
        assertEquals(bob.id(), f.getUniqueId());
        assertEquals("Bob", f.getName());
        assertTrue(f.isBestFriend());
        assertEquals("Bobby", f.getNickname());
        assertEquals(h.clock.instant(), f.getSince());
        assertThrows(UnsupportedOperationException.class, () -> friends.add(f));
        assertTrue(api.areFriends(alice.id(), bob.id()));
        assertTrue(api.areFriends(bob.id(), alice.id()));
        assertFalse(api.getFriends(bob.id()).getFirst().isBestFriend()); // best and nickname are private to Alice

        UUID carol = TestSupport.uuid("Carol"); // never joined
        assertFalse(api.isLoaded(carol));
        assertEquals(List.of(), api.getFriends(carol));
        assertFalse(api.areFriends(alice.id(), carol));

        h.quit(bob);
        assertFalse(api.isLoaded(bob.id()));
        assertEquals(List.of(), api.getFriends(bob.id())); // not loaded: use loadFriends
        assertTrue(api.areFriends(bob.id(), alice.id())); // Alice is loaded
    }

    @Test
    void statusAndServer() {
        var alice = h.join("Alice");
        assertEquals(Optional.of(Status.ONLINE), api.getStatus(alice.id()));
        assertEquals(Optional.of("lobby"), api.getServer(alice.id()));

        h.run(alice, "status busy");
        assertEquals(Optional.of(Status.BUSY), api.getStatus(alice.id()));
        h.run(alice, "status offline");
        assertEquals(Optional.of(Status.OFFLINE), api.getStatus(alice.id()));
        assertEquals(Optional.empty(), api.getServer(alice.id())); // appearing offline hides the server

        h.quit(alice);
        assertEquals(Optional.empty(), api.getStatus(alice.id()));
        assertEquals(Optional.empty(), api.getServer(alice.id()));
    }

    @Test
    void requestsAreListedUntilTheyExpire() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");

        var expected = new FriendRequest(alice.id(), "Alice", bob.id(), "Bob", h.clock.instant().plus(Duration.ofMinutes(5)));
        assertEquals(List.of(expected), api.getOutgoingRequests(alice.id()));
        assertEquals(List.of(expected), api.getIncomingRequests(bob.id()));
        assertEquals(List.of(), api.getIncomingRequests(alice.id()));
        assertEquals(List.of(), api.getOutgoingRequests(bob.id()));

        h.clock.advance(Duration.ofMinutes(6));
        assertEquals(List.of(), api.getIncomingRequests(bob.id()));
        assertEquals(List.of(), api.getOutgoingRequests(alice.id()));
    }

    // --- actions ---

    @Test
    void actionsWorkLikeCommandsWithoutReplyingToTheActor() throws Exception {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        alice.clear();
        bob.clear();

        assertEquals(Result.SUCCESS, result(api.sendRequest(alice.id(), bob.id())));
        assertEquals(List.of(), alice.inbox().messages); // the result is the reply
        assertTrue(bob.last().contains("Friend request from Alice"), bob.all()); // the target is still told
        assertEquals(Result.ALREADY_REQUESTED, result(api.sendRequest(alice.id(), bob.id())));

        assertEquals(Result.SUCCESS, result(api.acceptRequest(bob.id(), alice.id())));
        assertTrue(alice.last().contains("You are now friends with Bob"), alice.all());
        assertTrue(api.areFriends(alice.id(), bob.id()));
        assertEquals(List.of(bob.id()), h.storage.load(alice.id()).friends().stream().map(Friend::id).toList());
        assertEquals(Result.ALREADY_FRIENDS, result(api.sendRequest(alice.id(), bob.id())));
        assertEquals(Result.NO_REQUEST, result(api.acceptRequest(bob.id(), alice.id())));

        assertEquals(Result.SUCCESS, result(api.setBestFriend(alice.id(), bob.id(), true)));
        assertEquals(Result.SUCCESS, result(api.setBestFriend(alice.id(), bob.id(), true))); // set, not toggle
        assertTrue(api.getFriends(alice.id()).getFirst().isBestFriend());
        assertEquals(Result.SUCCESS, result(api.setBestFriend(alice.id(), bob.id(), false)));
        assertFalse(api.getFriends(alice.id()).getFirst().isBestFriend());

        assertEquals(Result.SUCCESS, result(api.setNickname(alice.id(), bob.id(), "Bobby")));
        assertEquals("Bobby", api.getFriends(alice.id()).getFirst().getNickname());
        assertEquals(Result.INVALID_ARGUMENT, result(api.setNickname(alice.id(), bob.id(), "no way!!")));
        assertEquals(Result.SUCCESS, result(api.setNickname(alice.id(), bob.id(), null)));
        assertEquals(null, api.getFriends(alice.id()).getFirst().getNickname());

        assertEquals(Result.SUCCESS, result(api.setStatus(alice.id(), Status.AWAY)));
        assertEquals(Optional.of(Status.AWAY), api.getStatus(alice.id()));

        assertEquals(Result.SUCCESS, result(api.removeFriend(alice.id(), bob.id())));
        assertFalse(api.areFriends(alice.id(), bob.id()));
        assertEquals(List.of(), h.storage.load(bob.id()).friends());
        assertEquals(Result.NOT_FRIENDS, result(api.removeFriend(alice.id(), bob.id())));
        assertEquals(Result.NOT_FRIENDS, result(api.setBestFriend(alice.id(), bob.id(), true)));
        assertEquals(Result.NOT_FRIENDS, result(api.setNickname(alice.id(), bob.id(), "x")));

        assertEquals(Result.SUCCESS, result(api.sendRequest(bob.id(), alice.id())));
        assertEquals(Result.SUCCESS, result(api.denyRequest(alice.id(), bob.id())));
        assertEquals(Result.NO_REQUEST, result(api.denyRequest(alice.id(), bob.id())));
        assertEquals(Result.NO_REQUEST, result(api.acceptRequest(alice.id(), bob.id())));
        assertEquals(List.of(), alice.inbox().messages.stream().map(TestSupport::plain)
                .filter(m -> m.contains("declined") || m.contains("sent a friend request")).toList());
    }

    @Test
    void askingBackMakesThemFriends() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        assertEquals(Result.SUCCESS, result(api.sendRequest(alice.id(), bob.id())));
        assertEquals(Result.BECAME_FRIENDS, result(api.sendRequest(bob.id(), alice.id())));
        assertTrue(api.areFriends(alice.id(), bob.id()));
    }

    @Test
    void targetsMayBeOfflineButActorsMustBeLoaded() {
        var alice = h.join("Alice");
        var carol = h.join("Carol");
        h.quit(carol);

        assertEquals(Result.SUCCESS, result(api.sendRequest(alice.id(), carol.id()))); // joined before: known
        assertEquals(Result.PLAYER_NOT_FOUND, result(api.sendRequest(alice.id(), UUID.randomUUID())));
        assertEquals(Result.SELF, result(api.sendRequest(alice.id(), alice.id())));

        UUID offline = carol.id();
        UUID other = alice.id();
        List<Supplier<CompletableFuture<Result>>> actions = List.of(
                () -> api.sendRequest(offline, other), () -> api.acceptRequest(offline, other),
                () -> api.denyRequest(offline, other), () -> api.removeFriend(offline, other),
                () -> api.setBestFriend(offline, other, true), () -> api.setNickname(offline, other, "x"),
                () -> api.setStatus(offline, Status.BUSY));
        for (var action : actions) assertEquals(Result.NOT_LOADED, result(action.get()));
    }

    @Test
    void friendLimitsApply() throws Exception {
        harness(1);
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        var carol = h.join("Carol");
        h.befriend(alice, bob);
        assertEquals(Result.LIMIT_REACHED, result(api.sendRequest(alice.id(), carol.id())));
        assertEquals(Result.TARGET_LIMIT_REACHED, result(api.sendRequest(carol.id(), alice.id())));
    }

    @Test
    void nullsAndBadInputNeverThrow() {
        UUID id = h.join("Alice").id();
        assertFalse(api.isLoaded(null));
        assertEquals(List.of(), api.getFriends(null));
        assertFalse(api.areFriends(null, id));
        assertFalse(api.areFriends(id, null));
        assertEquals(Optional.empty(), api.getStatus(null));
        assertEquals(Optional.empty(), api.getServer(null));
        assertEquals(List.of(), api.getIncomingRequests(null));
        assertEquals(List.of(), api.getOutgoingRequests(null));
        assertEquals(List.of(), api.loadFriends(null).join());

        List<Supplier<CompletableFuture<Result>>> actions = List.of(
                () -> api.sendRequest(null, id), () -> api.sendRequest(id, null),
                () -> api.acceptRequest(null, id), () -> api.acceptRequest(id, null),
                () -> api.denyRequest(null, id), () -> api.denyRequest(id, null),
                () -> api.removeFriend(null, id), () -> api.removeFriend(id, null),
                () -> api.setBestFriend(null, id, true), () -> api.setBestFriend(id, null, true),
                () -> api.setNickname(null, id, "x"), () -> api.setNickname(id, null, "x"),
                () -> api.setStatus(null, Status.BUSY), () -> api.setStatus(id, null));
        for (var action : actions) assertEquals(Result.INVALID_ARGUMENT, result(action.get()));
    }

    @Test
    void databaseErrorsBecomeResultsNotExceptions() {
        var alice = h.join("Alice");
        h.storage.close();
        assertEquals(Result.ERROR, result(api.sendRequest(alice.id(), UUID.randomUUID()))); // needs a lookup
        var e = assertThrows(CompletionException.class, () -> api.loadFriends(UUID.randomUUID()).join());
        assertTrue(e.getCause() instanceof java.sql.SQLException, e::toString);
    }

    @Test
    void actionsAfterShutdownFailCleanly() {
        ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
        var stopped = new FriendsApi(h.friends, h.platform, async);
        UUID id = h.join("Alice").id();
        async.shutdown();
        assertEquals(Result.ERROR, result(stopped.setStatus(id, Status.BUSY)));
    }

    // --- offline cache ---

    @Test
    void offlineListsComeFromTheDatabaseThenTheCache() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);
        h.quit(bob);

        db.held = true;
        var first = api.loadFriends(bob.id());
        var second = api.loadFriends(bob.id());
        assertEquals(1, db.queue.size()); // one read for both
        db.release();
        assertEquals(List.of("Alice"), first.join().stream().map(com.friends.api.Friend::getName).toList());
        assertEquals(first.join(), second.join());

        db.held = true;
        var cached = api.loadFriends(bob.id());
        assertTrue(cached.isDone());
        assertEquals(0, db.queue.size());

        h.clock.advance(Duration.ofSeconds(31)); // expired
        api.loadFriends(bob.id());
        assertEquals(1, db.queue.size());
        db.release();
    }

    @Test
    void aChangeDropsTheCachedListAndAReadItRacedIsNotKept() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);
        h.quit(bob);
        api.loadFriends(bob.id()).join(); // cached

        h.clock.advance(Duration.ofSeconds(31));
        db.held = true;
        // The removal lands right as the read is queued: the worst moment for the cache.
        db.onQueue = () -> h.run(alice, "remove Bob");
        var raced = api.loadFriends(bob.id());
        assertEquals(2, db.queue.size()); // the read, then the removal's write
        db.release();

        assertEquals(1, raced.join().size()); // asked before the removal: read before its write
        assertEquals(List.of(), api.loadFriends(bob.id()).join()); // the raced read was not cached
    }

    @Test
    void befriendingAnOfflinePlayerDropsTheirCachedList() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");
        h.quit(alice);
        assertEquals(List.of(), api.loadFriends(alice.id()).join()); // cached: no friends
        h.run(bob, "accept Alice");
        assertEquals(List.of("Bob"), api.loadFriends(alice.id()).join().stream().map(com.friends.api.Friend::getName).toList());
    }

    @Test
    void loadedPlayersAreServedFromMemory() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);
        db.held = true;
        var friends = api.loadFriends(alice.id());
        assertTrue(friends.isDone());
        assertEquals(0, db.queue.size());
        assertEquals(List.of("Bob"), friends.join().stream().map(com.friends.api.Friend::getName).toList());
        db.release();
    }

    // --- hooks (plugins' events) ---

    @Test
    void pluginsCanCancelEveryAction() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");

        hooks.veto = call -> call.startsWith("requesting");
        assertEquals(Result.CANCELLED, result(api.sendRequest(alice.id(), bob.id())));
        h.run(alice, "add Bob"); // commands too
        assertEquals(List.of(), api.getIncomingRequests(bob.id()));
        assertFalse(bob.all().contains("Friend request"));

        hooks.veto = call -> call.startsWith("befriending");
        assertEquals(Result.SUCCESS, result(api.sendRequest(alice.id(), bob.id())));
        assertEquals(Result.CANCELLED, result(api.acceptRequest(bob.id(), alice.id())));
        assertEquals(1, api.getIncomingRequests(bob.id()).size()); // still pending
        assertFalse(api.areFriends(alice.id(), bob.id()));
        hooks.veto = _ -> false;
        assertEquals(Result.SUCCESS, result(api.acceptRequest(bob.id(), alice.id())));

        hooks.veto = call -> call.startsWith("unfriending");
        assertEquals(Result.CANCELLED, result(api.removeFriend(alice.id(), bob.id())));
        assertTrue(api.areFriends(alice.id(), bob.id()));

        hooks.veto = call -> call.startsWith("status");
        assertEquals(Result.CANCELLED, result(api.setStatus(alice.id(), Status.BUSY)));
        assertEquals(Optional.of(Status.ONLINE), api.getStatus(alice.id()));
        hooks.calls.clear();
        assertEquals(Result.SUCCESS, result(api.setStatus(alice.id(), Status.ONLINE))); // no change: nobody is asked
        assertEquals(List.of(), hooks.calls);
    }

    @Test
    void removeAllAsksAboutEachFriend() throws Exception {
        var alice = h.join("Alice");
        for (String name : List.of("Bob", "Carol", "Dave")) h.befriend(alice, h.join(name));
        hooks.calls.clear();
        hooks.veto = call -> call.equals("unfriending Alice Carol");

        h.run(alice, "removeall confirm");
        assertEquals(List.of("Carol"), friendNames(alice.id()));
        assertEquals(List.of("Carol"), h.storage.load(alice.id()).friends().stream().map(Friend::name).toList());
        assertEquals(List.of("unfriended Alice Bob", "unfriended Alice Dave"),
                hooks.calls.stream().filter(c -> c.startsWith("unfriended")).sorted().toList());
        assertTrue(alice.last().contains("Removed 2 friends"), alice.all());
    }

    @Test
    void doneHooksFireOnceWhenTheSameRequestIsAcceptedTwiceAtOnce() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<CompletableFuture<Result>> inner = new AtomicReference<>();
        // While plugins look at the first accept, a second one of the same request goes through.
        hooks.during = call -> {
            if (call.startsWith("befriending") && started.compareAndSet(false, true)) inner.set(api.acceptRequest(bob.id(), alice.id()));
        };

        assertEquals(Result.NO_REQUEST, result(api.acceptRequest(bob.id(), alice.id()))); // re-checked: already used
        assertEquals(Result.SUCCESS, result(inner.get()));
        assertEquals(1, hooks.calls.stream().filter(c -> c.equals("befriended Bob Alice")).count());
        assertEquals(1, api.getFriends(alice.id()).size());
    }

    @Test
    void aRequestCrossingAnotherWhilePluginsDecideBecomesAFriendship() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        boolean[] crossed = {false};
        hooks.during = call -> {
            if (call.equals("requesting Alice Bob") && !crossed[0]) {
                crossed[0] = true;
                assertEquals(Result.SUCCESS, result(api.sendRequest(bob.id(), alice.id())));
            }
        };

        assertEquals(Result.BECAME_FRIENDS, result(api.sendRequest(alice.id(), bob.id())));
        assertTrue(api.areFriends(alice.id(), bob.id()));
        assertEquals(List.of("requesting Alice Bob", "requesting Bob Alice", "befriending Alice Bob", "befriended Alice Bob"),
                hooks.calls);
    }

    @Test
    void listenersRunOffTheDatabaseThreadAndMayUseTheApi() throws Exception {
        h.close();
        ExecutorService realDb = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-db"));
        ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
        h = new Harness(Storage.sqlite(dir.resolve("real.db")), 5000, Network.LOCAL, realDb, async, hooks);
        api = h.api;
        try {
            var alice = h.join("Alice");
            var bob = h.join("Bob");
            h.run(alice, "add Bob");
            h.quit(alice); // offline requester: accepting reads her friend count on the database thread
            List<String> threads = new CopyOnWriteArrayList<>();
            hooks.during = call -> {
                threads.add(Thread.currentThread().getName());
                api.loadFriends(alice.id()).join(); // would deadlock on the database thread
            };

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals(Result.SUCCESS, api.acceptRequest(bob.id(), alice.id()).join()));
            assertEquals(1, threads.size());
            assertNotEquals("test-db", threads.getFirst());
        } finally {
            async.shutdown();
            realDb.shutdown();
        }
    }

    /** Runs SQL inline, or queues it while {@code held} so a test can interleave reads and writes. */
    static final class GatedDb implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean held;
        Runnable onQueue; // runs once, right after the next task is queued

        @Override
        public void execute(Runnable task) {
            if (!held) {
                task.run();
                return;
            }
            queue.add(task);
            Runnable once = onQueue;
            onQueue = null;
            if (once != null) once.run();
        }

        void release() {
            held = false;
            while (!queue.isEmpty()) queue.poll().run();
        }
    }

    /** Records every hook call ("requesting Alice Bob", "befriended Bob Alice", ...); vetoes what {@code veto} matches. */
    static final class RecordingHooks implements Hooks {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile Predicate<String> veto = _ -> false;
        volatile Consumer<String> during = _ -> {};

        private boolean ask(String call) {
            calls.add(call);
            during.accept(call);
            return !veto.test(call);
        }

        @Override
        public boolean requesting(PlayerRow from, PlayerRow to) {
            return ask("requesting " + from.name() + " " + to.name());
        }

        @Override
        public boolean befriending(PlayerRow player, PlayerRow sender) {
            return ask("befriending " + player.name() + " " + sender.name());
        }

        @Override
        public void befriended(PlayerRow player, PlayerRow sender, Instant since) {
            calls.add("befriended " + player.name() + " " + sender.name());
        }

        @Override
        public boolean unfriending(PlayerRow player, Friend friend) {
            return ask("unfriending " + player.name() + " " + friend.name());
        }

        @Override
        public void unfriended(PlayerRow player, Friend friend) {
            calls.add("unfriended " + player.name() + " " + friend.name());
        }

        @Override
        public boolean statusChanging(PlayerRow player, Status from, Status to) {
            return ask("status " + player.name() + " " + from + " " + to);
        }
    }
}
