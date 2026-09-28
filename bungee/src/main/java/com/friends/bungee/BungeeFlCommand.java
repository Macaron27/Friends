package com.friends.bungee;

import java.util.List;
import java.util.UUID;

import com.friends.common.service.FriendService;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public class BungeeFlCommand extends Command {
    private final FriendService friendService;

    public BungeeFlCommand(FriendService friendService) {
        super("fl", null, new String[0]);
        this.friendService = friendService;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command");
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        // Page 1
        List<UUID> friends = friendService.listFriends(p.getUniqueId(), 1, 10);
        if (friends.isEmpty()) { p.sendMessage(ChatColor.YELLOW + "No friends."); return; }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(ChatColor.GREEN + "Friends (page 1):");
        for (UUID u : friends) {
            ProxiedPlayer online = ProxyServer.getInstance().getPlayer(u);
            if (online != null && online.isConnected()) {
                String display = null;
                try {
                    java.lang.reflect.Method m = online.getClass().getMethod("getDisplayName");
                    Object val = m.invoke(online);
                    if (val != null) display = val.toString();
                } catch (Throwable ignored) {}
                if (display == null) display = online.getName();
                display = com.friends.common.util.LuckPermsIntegration.formatDisplay(u, display);
                String srv = friendService.getLastServer(u);
                if (srv != null && !srv.isEmpty()) {
                    lines.add(ChatColor.AQUA + display + ChatColor.YELLOW + " - Currently in " + ChatColor.GREEN + srv);
                } else {
                    lines.add(ChatColor.AQUA + display + ChatColor.YELLOW + " - Currently online");
                }
            } else {
                String lastName = friendService.getLastUsername(u);
                java.time.Instant lastSeen = friendService.getLastSeen(u);
                String name = lastName != null ? lastName : u.toString();
                String lastSeenText = com.friends.common.util.TimeUtil.formatRelative(lastSeen);
                lines.add(ChatColor.AQUA + name + ChatColor.RED + " - Last seen " + lastSeenText + ".");
            }
        }
        // Send wrapped
        String sep = "&9&o&m--------------------------------------------------";
        sender.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', sep));
        for (String l : lines) sender.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', l));
        sender.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', sep));
    }
}
