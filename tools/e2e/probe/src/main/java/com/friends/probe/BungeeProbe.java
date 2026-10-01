package com.friends.probe;

import com.friends.api.FriendsAPI;
import com.friends.api.bungee.FriendAddEvent;
import com.friends.api.bungee.FriendAddedEvent;
import com.friends.api.bungee.FriendRemoveEvent;
import com.friends.api.bungee.FriendRemovedEvent;
import com.friends.api.bungee.FriendRequestSendEvent;
import com.friends.api.bungee.FriendStatusChangeEvent;

import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public final class BungeeProbe extends Plugin implements Listener {
    private FriendsAPI api;

    @Override
    public void onEnable() {
        api = FriendsAPI.get();
        log("probe: api ok");
        getProxy().getPluginManager().registerListener(this, this);
    }

    private void log(String line) {
        getLogger().info(line);
    }

    @EventHandler
    public void on(FriendRequestSendEvent e) {
        log(Probe.pair("FriendRequestSendEvent", e.getPlayerName(), e.getTargetName()));
    }

    @EventHandler
    public void on(FriendAddEvent e) {
        log(Probe.pair("FriendAddEvent", e.getPlayerName(), e.getTargetName()));
    }

    @EventHandler
    public void on(FriendAddedEvent e) {
        log(Probe.pair("FriendAddedEvent", e.getPlayerName(), e.getTargetName()));
        Probe.befriended(api, e.getPlayerId(), e.getPlayerName(), e.getTargetId(), this::log, Runnable::run);
    }

    @EventHandler
    public void on(FriendRemoveEvent e) {
        log(Probe.pair("FriendRemoveEvent", e.getPlayerName(), e.getTargetName()));
    }

    @EventHandler
    public void on(FriendRemovedEvent e) {
        log(Probe.pair("FriendRemovedEvent", e.getPlayerName(), e.getTargetName()));
    }

    @EventHandler
    public void on(FriendStatusChangeEvent e) {
        e.setCancelled(Probe.cancels(e.getNewStatus()));
        log(Probe.status(e.getPlayerName(), e.getOldStatus(), e.getNewStatus()));
    }
}
