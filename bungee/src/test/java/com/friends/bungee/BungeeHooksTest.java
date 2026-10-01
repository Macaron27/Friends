package com.friends.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.friends.api.Status;
import com.friends.api.bungee.FriendAddEvent;
import com.friends.api.bungee.FriendAddedEvent;
import com.friends.api.bungee.FriendEvent;
import com.friends.api.bungee.FriendRemoveEvent;
import com.friends.api.bungee.FriendRemovedEvent;
import com.friends.api.bungee.FriendRequestSendEvent;
import com.friends.api.bungee.FriendStatusChangeEvent;
import com.friends.common.Friend;
import com.friends.common.Storage.PlayerRow;

import net.md_5.bungee.api.plugin.Cancellable;
import net.md_5.bungee.api.plugin.Event;
import net.md_5.bungee.event.EventBus;
import net.md_5.bungee.event.EventHandler;

/** Through BungeeCord's real event bus (what PluginManager.callEvent posts to). */
class BungeeHooksTest {
    final PlayerRow alice = new PlayerRow(UUID.randomUUID(), "Alice", null, Instant.EPOCH);
    final PlayerRow bob = new PlayerRow(UUID.randomUUID(), "Bob", null, Instant.EPOCH);
    final Friend bobAsFriend = new Friend(bob.id(), "Bob", null, Instant.EPOCH, false, null, Instant.EPOCH);
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    final Recorder listener = new Recorder();
    final BungeeHooks hooks;

    BungeeHooksTest() {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { logged.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        EventBus bus = new EventBus(log);
        bus.register(listener);
        hooks = new BungeeHooks(bus::post);
    }

    public static final class Recorder {
        final List<Event> seen = new CopyOnWriteArrayList<>();
        volatile Predicate<Event> cancel = _ -> false;
        volatile boolean explode;

        private void record(Event e) {
            seen.add(e);
            if (explode) throw new IllegalStateException("a broken listener");
            if (e instanceof Cancellable c && cancel.test(e)) c.setCancelled(true);
        }

        @EventHandler public void on(FriendRequestSendEvent e) { record(e); }
        @EventHandler public void on(FriendAddEvent e) { record(e); }
        @EventHandler public void on(FriendAddedEvent e) { record(e); }
        @EventHandler public void on(FriendRemoveEvent e) { record(e); }
        @EventHandler public void on(FriendRemovedEvent e) { record(e); }
        @EventHandler public void on(FriendStatusChangeEvent e) { record(e); }
    }

    @Test
    void listenersCanCancelAndSeeEveryField() {
        assertTrue(hooks.requesting(alice, bob));
        assertTrue(hooks.befriending(bob, alice));
        assertTrue(hooks.unfriending(alice, bobAsFriend));
        assertTrue(hooks.statusChanging(alice, Status.ONLINE, Status.BUSY));
        listener.cancel = _ -> true;
        assertFalse(hooks.requesting(alice, bob));
        assertFalse(hooks.befriending(bob, alice));
        assertFalse(hooks.unfriending(alice, bobAsFriend));
        assertFalse(hooks.statusChanging(alice, Status.ONLINE, Status.BUSY));

        assertEquals(List.of(alice.id(), "Alice", bob.id(), "Bob"), fields((FriendRequestSendEvent) listener.seen.get(0)));
        assertEquals(List.of(bob.id(), "Bob", alice.id(), "Alice"), fields((FriendAddEvent) listener.seen.get(1)));
        assertEquals(List.of(alice.id(), "Alice", bob.id(), "Bob"), fields((FriendRemoveEvent) listener.seen.get(2)));
        var status = (FriendStatusChangeEvent) listener.seen.get(3);
        assertEquals(List.of(alice.id(), "Alice", Status.ONLINE, Status.BUSY),
                List.of(status.getPlayerId(), status.getPlayerName(), status.getOldStatus(), status.getNewStatus()));
    }

    @Test
    void doneEventsAreCalled() {
        Instant since = Instant.parse("2026-10-01T12:00:00Z");
        hooks.befriended(bob, alice, since);
        hooks.unfriended(alice, bobAsFriend);
        var added = (FriendAddedEvent) listener.seen.get(0);
        assertEquals(List.of(bob.id(), "Bob", alice.id(), "Alice"), fields(added));
        assertEquals(since, added.getSince());
        assertEquals(List.of(alice.id(), "Alice", bob.id(), "Bob"), fields((FriendRemovedEvent) listener.seen.get(1)));
    }

    @Test
    void aBrokenListenerIsLoggedAndDoesNotCancel() {
        listener.explode = true;
        assertTrue(hooks.requesting(alice, bob)); // no exception reaches Friends
        assertEquals(1, logged.size());
        assertTrue(logged.getFirst().getThrown() instanceof IllegalStateException, logged::toString);
    }

    private static List<Object> fields(FriendEvent e) {
        return List.of(e.getPlayerId(), e.getPlayerName(), e.getTargetId(), e.getTargetName());
    }
}
