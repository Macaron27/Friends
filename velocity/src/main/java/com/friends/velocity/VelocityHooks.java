package com.friends.velocity;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.friends.api.Status;
import com.friends.api.velocity.FriendAddEvent;
import com.friends.api.velocity.FriendAddedEvent;
import com.friends.api.velocity.FriendRemoveEvent;
import com.friends.api.velocity.FriendRemovedEvent;
import com.friends.api.velocity.FriendRequestSendEvent;
import com.friends.api.velocity.FriendStatusChangeEvent;
import com.friends.common.Friend;
import com.friends.common.Hooks;
import com.friends.common.Storage.PlayerRow;
import com.velocitypowered.api.event.ResultedEvent;

/** The core's hooks as Velocity events. Hooks run on Friends' threads, so waiting for listeners blocks nothing of Velocity's. */
final class VelocityHooks implements Hooks {
    private final Function<Object, CompletableFuture<?>> fire; // EventManager::fire

    VelocityHooks(Function<Object, CompletableFuture<?>> fire) {
        this.fire = fire;
    }

    @Override
    public boolean requesting(PlayerRow from, PlayerRow to) {
        return allowed(new FriendRequestSendEvent(from.id(), from.name(), to.id(), to.name()));
    }

    @Override
    public boolean befriending(PlayerRow player, PlayerRow sender) {
        return allowed(new FriendAddEvent(player.id(), player.name(), sender.id(), sender.name()));
    }

    @Override
    public void befriended(PlayerRow player, PlayerRow sender, Instant since) {
        fire.apply(new FriendAddedEvent(player.id(), player.name(), sender.id(), sender.name(), since)).join();
    }

    @Override
    public boolean unfriending(PlayerRow player, Friend friend) {
        return allowed(new FriendRemoveEvent(player.id(), player.name(), friend.id(), friend.name()));
    }

    @Override
    public void unfriended(PlayerRow player, Friend friend) {
        fire.apply(new FriendRemovedEvent(player.id(), player.name(), friend.id(), friend.name())).join();
    }

    @Override
    public boolean statusChanging(PlayerRow player, Status from, Status to) {
        return allowed(new FriendStatusChangeEvent(player.id(), player.name(), from, to));
    }

    /** Waits for every listener, then reads their verdict. */
    private boolean allowed(ResultedEvent<ResultedEvent.GenericResult> event) {
        fire.apply(event).join();
        return event.getResult().isAllowed();
    }
}
