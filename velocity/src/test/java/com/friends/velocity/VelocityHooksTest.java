package com.friends.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.friends.api.Status;
import com.friends.api.velocity.FriendAddEvent;
import com.friends.api.velocity.FriendAddedEvent;
import com.friends.api.velocity.FriendEvent;
import com.friends.api.velocity.FriendRemoveEvent;
import com.friends.api.velocity.FriendRemovedEvent;
import com.friends.api.velocity.FriendRequestSendEvent;
import com.friends.api.velocity.FriendStatusChangeEvent;
import com.friends.common.Friend;
import com.friends.common.Storage.PlayerRow;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.ResultedEvent.GenericResult;

class VelocityHooksTest {
    final PlayerRow alice = new PlayerRow(UUID.randomUUID(), "Alice", null, Instant.EPOCH);
    final PlayerRow bob = new PlayerRow(UUID.randomUUID(), "Bob", null, Instant.EPOCH);
    final Friend bobAsFriend = new Friend(bob.id(), "Bob", null, Instant.EPOCH, false, null, Instant.EPOCH);
    final List<Object> fired = new CopyOnWriteArrayList<>();
    volatile Predicate<Object> deny = _ -> false;

    /** Like Velocity's EventManager.fire: listeners run on another thread, the future completes after them. */
    @SuppressWarnings("unchecked")
    final VelocityHooks hooks = new VelocityHooks(event -> CompletableFuture.supplyAsync(() -> {
        try {
            Thread.sleep(20); // a slow listener: the verdict must be read after it
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        fired.add(event);
        if (deny.test(event)) ((ResultedEvent<GenericResult>) event).setResult(GenericResult.denied());
        return event;
    }));

    @Test
    void vetoesWaitForListenersAndMapEveryField() {
        assertTrue(hooks.requesting(alice, bob));
        assertTrue(hooks.befriending(bob, alice));
        assertTrue(hooks.unfriending(alice, bobAsFriend));
        assertTrue(hooks.statusChanging(alice, Status.ONLINE, Status.BUSY));
        deny = _ -> true;
        assertFalse(hooks.requesting(alice, bob));
        assertFalse(hooks.befriending(bob, alice));
        assertFalse(hooks.unfriending(alice, bobAsFriend));
        assertFalse(hooks.statusChanging(alice, Status.ONLINE, Status.BUSY));

        var sent = (FriendRequestSendEvent) fired.get(0);
        assertEquals(List.of(alice.id(), "Alice", bob.id(), "Bob"), fields(sent));
        assertEquals(List.of(bob.id(), "Bob", alice.id(), "Alice"), fields((FriendAddEvent) fired.get(1)));
        assertEquals(List.of(alice.id(), "Alice", bob.id(), "Bob"), fields((FriendRemoveEvent) fired.get(2)));
        var status = (FriendStatusChangeEvent) fired.get(3);
        assertEquals(List.of(alice.id(), "Alice", Status.ONLINE, Status.BUSY),
                List.of(status.getPlayerId(), status.getPlayerName(), status.getOldStatus(), status.getNewStatus()));
        assertTrue(sent.getResult().isAllowed());
    }

    @Test
    void doneEventsAreFiredAndAwaited() {
        Instant since = Instant.parse("2026-10-01T12:00:00Z");
        hooks.befriended(bob, alice, since);
        hooks.unfriended(alice, bobAsFriend);
        assertEquals(2, fired.size()); // both delivered before returning
        var added = (FriendAddedEvent) fired.get(0);
        assertEquals(List.of(bob.id(), "Bob", alice.id(), "Alice"), fields(added));
        assertEquals(since, added.getSince());
        assertEquals(List.of(alice.id(), "Alice", bob.id(), "Bob"), fields((FriendRemovedEvent) fired.get(1)));
    }

    private static List<Object> fields(FriendEvent e) {
        return List.of(e.getPlayerId(), e.getPlayerName(), e.getTargetId(), e.getTargetName());
    }
}
