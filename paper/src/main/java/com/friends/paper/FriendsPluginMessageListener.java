package com.friends.paper;

import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.friends.common.service.FriendService;

public class FriendsPluginMessageListener implements PluginMessageListener {
    private final FriendService friendService;

    public FriendsPluginMessageListener(FriendService friendService) {
        this.friendService = friendService;
    }

    @Override
    public void onPluginMessageReceived(String channel, org.bukkit.entity.Player player, byte[] message) {
        if (!"friends:notify".equals(channel)) return;
        String payload = new String(message, StandardCharsets.UTF_8);
        // format: TYPE|uuid|username|server
        String[] parts = payload.split("\\|", 4);
        if (parts.length < 4) return;
        String type = parts[0];
        String username = parts[2];
        String server = parts[3];

        // Broadcast to players on this server who have notifications enabled and are friends with uuid
        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Friends"), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                // Use FriendService to check if p has notifications enabled and is friend
                try {
                    java.util.UUID actor = java.util.UUID.fromString(parts[1]);
                    if (!friendService.getNotifications(p.getUniqueId())) continue;
                    if (!friendService.listFriends(p.getUniqueId(), 1, Integer.MAX_VALUE).contains(actor)) continue;

                    if ("JOIN".equalsIgnoreCase(type)) {
                        String prefix = friendService.getPrefix(p.getUniqueId());
                        String actorDisplay = com.friends.common.util.LuckPermsIntegration.formatDisplay(java.util.UUID.fromString(parts[1]), username);
                        String msg = prefix + " " + "&a" + actorDisplay + " &ajoined!";
                        p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                    } else if ("LEAVE".equalsIgnoreCase(type)) {
                        String prefix = friendService.getPrefix(p.getUniqueId());
                        String actorDisplay = com.friends.common.util.LuckPermsIntegration.formatDisplay(java.util.UUID.fromString(parts[1]), username);
                        String msg = prefix + " " + "&a" + actorDisplay + " &leaved!";
                        p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                    }
                } catch (Exception ignored) { }
            }
        });
    }
}
