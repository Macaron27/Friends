package com.friends.probe;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import com.friends.api.FriendsAPI;
import com.friends.api.bukkit.FriendAddEvent;
import com.friends.api.bukkit.FriendAddedEvent;
import com.friends.api.bukkit.FriendRemoveEvent;
import com.friends.api.bukkit.FriendRemovedEvent;
import com.friends.api.bukkit.FriendRequestSendEvent;
import com.friends.api.bukkit.FriendStatusChangeEvent;

public final class PaperProbe extends JavaPlugin implements Listener {
    private FriendsAPI api;

    @Override
    public void onEnable() {
        api = FriendsAPI.get();
        log("probe: api ok service=" + (getServer().getServicesManager().load(FriendsAPI.class) == api));
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void log(String line) {
        getLogger().info(line);
    }

    private static String threads(Event e) {
        return " async=" + e.isAsynchronous() + " main=" + Bukkit.isPrimaryThread();
    }

    @EventHandler
    public void on(FriendRequestSendEvent e) {
        log(Probe.pair("FriendRequestSendEvent", e.getPlayerName(), e.getTargetName()) + threads(e));
    }

    @EventHandler
    public void on(FriendAddEvent e) {
        log(Probe.pair("FriendAddEvent", e.getPlayerName(), e.getTargetName()) + threads(e));
    }

    @EventHandler
    public void on(FriendAddedEvent e) {
        log(Probe.pair("FriendAddedEvent", e.getPlayerName(), e.getTargetName()) + threads(e));
        // The action runs from the server thread: Friends must still fire its (async) event elsewhere.
        Probe.befriended(api, e.getPlayerId(), e.getPlayerName(), e.getTargetId(), this::log,
                task -> getServer().getScheduler().runTask(this, task));
    }

    @EventHandler
    public void on(FriendRemoveEvent e) {
        log(Probe.pair("FriendRemoveEvent", e.getPlayerName(), e.getTargetName()) + threads(e));
    }

    @EventHandler
    public void on(FriendRemovedEvent e) {
        log(Probe.pair("FriendRemovedEvent", e.getPlayerName(), e.getTargetName()) + threads(e));
    }

    @EventHandler
    public void on(FriendStatusChangeEvent e) {
        e.setCancelled(Probe.cancels(e.getNewStatus()));
        log(Probe.status(e.getPlayerName(), e.getOldStatus(), e.getNewStatus()) + threads(e));
    }
}
