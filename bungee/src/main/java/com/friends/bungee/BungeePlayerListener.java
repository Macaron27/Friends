package com.friends.bungee;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.friends.common.service.FriendService;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

public class BungeePlayerListener implements Listener {
    private final FriendsBungee plugin;
    private final FriendService friendService;

    public BungeePlayerListener(FriendsBungee plugin, FriendService friendService) {
        this.plugin = plugin;
        this.friendService = friendService;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        // Extract server name safely via reflection to avoid NoSuchMethodError on different runtime API shapes
        String server = null;
        try {
            Object serverObj = null;
            try {
                java.lang.reflect.Method m = event.getClass().getMethod("getServer");
                serverObj = m.invoke(event);
            } catch (NoSuchMethodException ignored) {
                try {
                    java.lang.reflect.Method m2 = event.getClass().getMethod("getTarget");
                    serverObj = m2.invoke(event);
                } catch (NoSuchMethodException ignored2) {
                    // no-op
                }
            }
            if (serverObj != null) {
                try {
                    java.lang.reflect.Method nameMeth = serverObj.getClass().getMethod("getName");
                    Object nameVal = nameMeth.invoke(serverObj);
                    if (nameVal != null) server = nameVal.toString();
                } catch (NoSuchMethodException nmex) {
                    // ignore
                }
            }
        } catch (Throwable ex) {
            plugin.getLogger().fine("Could not extract server name from event: " + ex.getMessage());
        }
        friendService.updatePlayerInfo(player.getUniqueId(), player.getName(), server);

        // Notify online friends via proxy-level direct message and plugin messaging to target servers
        List<java.util.UUID> friends = friendService.listFriends(player.getUniqueId(), 1, Integer.MAX_VALUE);
        for (java.util.UUID f : friends) {
            ProxiedPlayer friend = plugin.getProxy().getPlayer(f);
            if (!friendService.getNotifications(f)) continue;

            if (friend != null && friend.isConnected()) {
                String prefix = friendService.getPrefix(f);
                if (prefix == null || prefix.isEmpty()) prefix = "Friend >";
                String display = com.friends.common.util.LuckPermsIntegration.formatDisplay(player.getUniqueId(), player.getName());
                String msg = prefix + " " + display + " &ajoined !";
                try {
                    friend.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', msg));
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to send join message to " + f + ": " + t.getMessage());
                }

                // Send plugin message to the server the friend is on to allow Paper to render richer notifications (use reflection to avoid NoSuchMethodError)
                try {
                    java.lang.reflect.Method m = friend.getClass().getMethod("getServer");
                    Object serverObj = m.invoke(friend);
                    if (serverObj != null) {
                        java.lang.reflect.Method sendData = serverObj.getClass().getMethod("sendData", String.class, byte[].class);
                        String payload = "JOIN|" + player.getUniqueId().toString() + "|" + com.friends.common.util.LuckPermsIntegration.formatDisplay(player.getUniqueId(), player.getName()) + "|" + server;
                        sendData.invoke(serverObj, "friends:notify", payload.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (NoSuchMethodException nsme) {
                    // runtime does not expose server/sendData in a compatible way; ignore
                } catch (Throwable ex) {
                    plugin.getLogger().fine("Could not send plugin notify to friend's server: " + ex.getMessage());
                }
            } else {
                // Friend is offline or not connected — nothing to send right now
                plugin.getLogger().fine("Friend " + f + " offline or not connected; skipping join notification");
            }
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        // Update last seen, clear server
        friendService.updatePlayerInfo(player.getUniqueId(), player.getName(), null);

        List<java.util.UUID> friends = friendService.listFriends(player.getUniqueId(), 1, Integer.MAX_VALUE);
        for (java.util.UUID f : friends) {
            ProxiedPlayer friend = plugin.getProxy().getPlayer(f);
            if (!friendService.getNotifications(f)) continue;

            if (friend != null && friend.isConnected()) {
                // Additionally, send leave message to the friend now with the new format
                String prefix = friendService.getPrefix(f);
                if (prefix == null || prefix.isEmpty()) prefix = "Friend >";
                String display = com.friends.common.util.LuckPermsIntegration.formatDisplay(player.getUniqueId(), player.getName());
                String leaveMsg = prefix + " " + display + " &aleaved !";
                try {
                    friend.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', leaveMsg));
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to send formatted leave message to " + f + ": " + t.getMessage());
                }
            } else {
                plugin.getLogger().fine("Friend " + f + " offline or not connected; skipping leave notification");
            }
        }
    }
}
