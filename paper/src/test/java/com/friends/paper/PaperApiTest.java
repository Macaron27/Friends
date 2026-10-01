package com.friends.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.friends.api.FriendsAPI;
import com.friends.api.Result;
import com.friends.api.Status;
import com.friends.api.bukkit.FriendAddEvent;
import com.friends.api.bukkit.FriendAddedEvent;
import com.friends.api.bukkit.FriendRemoveEvent;
import com.friends.api.bukkit.FriendRemovedEvent;
import com.friends.api.bukkit.FriendRequestSendEvent;
import com.friends.api.bukkit.FriendStatusChangeEvent;

/** The real Paper wiring ({@link PaperFriends}) on a MockBukkit server: API registration and Bukkit events. */
class PaperApiTest {
    ServerMock server;
    TestPlugin plugin;
    FriendsAPI api;
    final Recorder events = new Recorder();

    /** Runs {@link PaperFriends} like {@code FriendsBootstrap} does, minus the embedded jar (not final: MockBukkit subclasses it). */
    public static class TestPlugin extends JavaPlugin {
        PaperFriends friends;

        @Override
        public void onEnable() {
            friends = new PaperFriends(this);
            friends.enable();
        }

        @Override
        public void onDisable() {
            friends.disable();
        }
    }

    /** Records every Friends event (and the thread it ran on); cancels those {@code cancel} matches. */
    public static final class Recorder implements Listener {
        final List<Event> seen = new CopyOnWriteArrayList<>();
        final List<Boolean> onServerThread = new CopyOnWriteArrayList<>();
        volatile Predicate<Event> cancel = _ -> false;
        volatile Runnable during = () -> {};

        private void record(Event e) {
            seen.add(e);
            onServerThread.add(MockBukkit.getMock().isPrimaryThread());
            during.run();
            if (e instanceof Cancellable c && cancel.test(e)) c.setCancelled(true);
        }

        @EventHandler public void on(FriendRequestSendEvent e) { record(e); }
        @EventHandler public void on(FriendAddEvent e) { record(e); }
        @EventHandler public void on(FriendAddedEvent e) { record(e); }
        @EventHandler public void on(FriendRemoveEvent e) { record(e); }
        @EventHandler public void on(FriendRemovedEvent e) { record(e); }
        @EventHandler public void on(FriendStatusChangeEvent e) { record(e); }

        <E extends Event> List<E> of(Class<E> type) {
            return seen.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        try (InputStream yml = PaperApiTest.class.getResourceAsStream("/plugin.yml")) {
            plugin = MockBukkit.loadWith(TestPlugin.class, yml);
        }
        server.getPluginManager().registerEvents(events, plugin);
        api = FriendsAPI.get();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) MockBukkit.unmock();
    }

    private PlayerMock join(String name) {
        PlayerMock p = server.addPlayer(name);
        p.addAttachment(plugin, "friends.use", true); // MockBukkit ignores plugin.yml's "default: true"
        await(() -> api.isLoaded(p.getUniqueId()));
        return p;
    }

    private static <T> T get(CompletableFuture<T> f) {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out");
            Thread.onSpinWait();
        }
    }

    @Test
    void theApiIsAServiceAndGoesAwayOnDisable() {
        assertSame(api, server.getServicesManager().load(FriendsAPI.class));
        MockBukkit.unmock(); // disables the plugin
        assertEquals(Optional.empty(), FriendsAPI.find());
    }

    @Test
    void eventsFireAsynchronouslyAndCanCancelEveryAction() {
        assertTrue(server.isPrimaryThread()); // the test calls the API from the server thread
        PlayerMock alice = join("Alice");
        PlayerMock bob = join("Bob");

        events.cancel = e -> e instanceof FriendRequestSendEvent;
        assertEquals(Result.CANCELLED, get(api.sendRequest(alice.getUniqueId(), bob.getUniqueId())));
        assertEquals(List.of(), api.getIncomingRequests(bob.getUniqueId()));

        events.cancel = e -> e instanceof FriendAddEvent;
        assertEquals(Result.SUCCESS, get(api.sendRequest(alice.getUniqueId(), bob.getUniqueId())));
        FriendRequestSendEvent sent = events.of(FriendRequestSendEvent.class).getLast();
        assertEquals(alice.getUniqueId(), sent.getPlayerId());
        assertEquals("Alice", sent.getPlayerName());
        assertEquals(bob.getUniqueId(), sent.getTargetId());
        assertEquals("Bob", sent.getTargetName());
        assertEquals(Result.CANCELLED, get(api.acceptRequest(bob.getUniqueId(), alice.getUniqueId())));
        assertFalse(api.areFriends(alice.getUniqueId(), bob.getUniqueId()));

        events.cancel = _ -> false;
        assertEquals(Result.SUCCESS, get(api.acceptRequest(bob.getUniqueId(), alice.getUniqueId())));
        await(() -> events.of(FriendAddedEvent.class).size() == 1);
        FriendAddedEvent added = events.of(FriendAddedEvent.class).getFirst();
        assertEquals(bob.getUniqueId(), added.getPlayerId());
        assertEquals(alice.getUniqueId(), added.getTargetId());
        assertEquals(api.getFriends(alice.getUniqueId()).getFirst().getSince(), added.getSince());

        events.cancel = e -> e instanceof FriendStatusChangeEvent;
        assertEquals(Result.CANCELLED, get(api.setStatus(alice.getUniqueId(), Status.BUSY)));
        assertEquals(Optional.of(Status.ONLINE), api.getStatus(alice.getUniqueId()));
        FriendStatusChangeEvent status = events.of(FriendStatusChangeEvent.class).getLast();
        assertEquals(Status.ONLINE, status.getOldStatus());
        assertEquals(Status.BUSY, status.getNewStatus());

        events.cancel = e -> e instanceof FriendRemoveEvent;
        assertEquals(Result.CANCELLED, get(api.removeFriend(alice.getUniqueId(), bob.getUniqueId())));
        events.cancel = _ -> false;
        assertEquals(Result.SUCCESS, get(api.removeFriend(alice.getUniqueId(), bob.getUniqueId())));
        await(() -> events.of(FriendRemovedEvent.class).size() == 1);
        assertEquals(bob.getUniqueId(), events.of(FriendRemovedEvent.class).getFirst().getTargetId());

        assertTrue(events.seen.stream().allMatch(Event::isAsynchronous));
        assertEquals(List.of(), events.onServerThread.stream().filter(b -> b).toList()); // never on the server thread
        assertEquals(1, events.of(FriendAddedEvent.class).size()); // exactly once
    }

    @Test
    void commandsFireTheSameEvents() {
        PlayerMock alice = join("Alice");
        PlayerMock bob = join("Bob");
        alice.performCommand("f add Bob");
        await(() -> api.getIncomingRequests(bob.getUniqueId()).size() == 1);
        assertEquals(1, events.of(FriendRequestSendEvent.class).size());

        events.cancel = e -> e instanceof FriendAddEvent;
        bob.performCommand("f accept Alice");
        await(() -> events.of(FriendAddEvent.class).size() == 1);
        events.cancel = _ -> false;
        bob.performCommand("f accept Alice");
        await(() -> api.areFriends(alice.getUniqueId(), bob.getUniqueId()));
        await(() -> events.of(FriendAddedEvent.class).size() == 1);
    }

    @Test
    void listenersMayUseTheApi() {
        PlayerMock alice = join("Alice");
        PlayerMock bob = join("Bob");
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        events.during = () -> sizes.add(get(api.loadFriends(bob.getUniqueId())).size()); // no deadlock, no server thread
        assertEquals(Result.SUCCESS, get(api.sendRequest(alice.getUniqueId(), bob.getUniqueId())));
        assertEquals(List.of(0), sizes);
    }
}
