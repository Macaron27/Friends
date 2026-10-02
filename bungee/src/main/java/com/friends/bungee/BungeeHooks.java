package com.friends.bungee;

import java.time.Instant;
import java.util.function.Consumer;

import com.friends.api.Status;
import com.friends.api.bungee.FriendAddEvent;
import com.friends.api.bungee.FriendAddedEvent;
import com.friends.api.bungee.FriendRemoveEvent;
import com.friends.api.bungee.FriendRemovedEvent;
import com.friends.api.bungee.FriendRequestSendEvent;
import com.friends.api.bungee.FriendStatusChangeEvent;
import com.friends.core.Friend;
import com.friends.core.Hooks;
import com.friends.core.Storage.PlayerRow;

import net.md_5.bungee.api.plugin.Cancellable;
import net.md_5.bungee.api.plugin.Event;

/** The core's hooks as BungeeCord events, called on Friends' threads (never on BungeeCord's network threads). */
final class BungeeHooks implements Hooks {
    private final Consumer<Event> call; // PluginManager::callEvent

    BungeeHooks(Consumer<Event> call) {
        this.call = call;
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
        call.accept(new FriendAddedEvent(player.id(), player.name(), sender.id(), sender.name(), since));
    }

    @Override
    public boolean unfriending(PlayerRow player, Friend friend) {
        return allowed(new FriendRemoveEvent(player.id(), player.name(), friend.id(), friend.name()));
    }

    @Override
    public void unfriended(PlayerRow player, Friend friend) {
        call.accept(new FriendRemovedEvent(player.id(), player.name(), friend.id(), friend.name()));
    }

    @Override
    public boolean statusChanging(PlayerRow player, Status from, Status to) {
        return allowed(new FriendStatusChangeEvent(player.id(), player.name(), from, to));
    }

    private <E extends Event & Cancellable> boolean allowed(E event) {
        call.accept(event); // BungeeCord's event bus catches (and logs) listeners' exceptions
        return !event.isCancelled();
    }
}
