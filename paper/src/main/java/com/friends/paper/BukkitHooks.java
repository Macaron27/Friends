package com.friends.paper;

import java.time.Instant;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;

import com.friends.api.Status;
import com.friends.api.bukkit.FriendAddEvent;
import com.friends.api.bukkit.FriendAddedEvent;
import com.friends.api.bukkit.FriendMessageEvent;
import com.friends.api.bukkit.FriendRemoveEvent;
import com.friends.api.bukkit.FriendRemovedEvent;
import com.friends.api.bukkit.FriendRequestSendEvent;
import com.friends.api.bukkit.FriendStatusChangeEvent;
import com.friends.core.Friend;
import com.friends.core.Hooks;
import com.friends.core.Storage.PlayerRow;

/** The core's hooks as Bukkit events, all asynchronous: hooks never run on the server thread. */
final class BukkitHooks implements Hooks {
    private final PluginManager events;

    BukkitHooks(PluginManager events) {
        this.events = events;
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
        events.callEvent(new FriendAddedEvent(player.id(), player.name(), sender.id(), sender.name(), since));
    }

    @Override
    public boolean unfriending(PlayerRow player, Friend friend) {
        return allowed(new FriendRemoveEvent(player.id(), player.name(), friend.id(), friend.name()));
    }

    @Override
    public void unfriended(PlayerRow player, Friend friend) {
        events.callEvent(new FriendRemovedEvent(player.id(), player.name(), friend.id(), friend.name()));
    }

    @Override
    public boolean statusChanging(PlayerRow player, Status from, Status to) {
        return allowed(new FriendStatusChangeEvent(player.id(), player.name(), from, to));
    }

    @Override
    public String messaging(PlayerRow from, PlayerRow to, String message) {
        FriendMessageEvent event = new FriendMessageEvent(from.id(), from.name(), to.id(), to.name(), message);
        return allowed(event) ? event.getMessage() : null;
    }

    private <E extends Event & Cancellable> boolean allowed(E event) {
        events.callEvent(event); // Bukkit catches (and logs) listeners' exceptions
        return !event.isCancelled();
    }
}
