package com.friends.bungee;

import java.util.List;
import java.util.UUID;

import com.friends.common.service.FriendService;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public class BungeeFriendsCommand extends Command {
    private final FriendService friendService;

    public BungeeFriendsCommand(FriendService friendService) {
        super("f", null, "friends", "fl");
        this.friendService = friendService;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            sendWrappedSingle(sender, ChatColor.RED + "Only players can use this command");
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        if (args.length == 0) {
            sendHelp(p);
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f add <player>"); return; }
                handleAdd(p, args[1]);
                break;
            case "remove":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f remove <player>"); return; }
                handleRemove(p, args[1]);
                break;
            case "accept":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f accept <player>"); return; }
                handleAccept(p, args[1]);
                break;
            case "deny":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f deny <player>"); return; }
                handleDeny(p, args[1]);
                break;
            case "help":
                sendHelp(p);
                break;
            case "list":
                handleList(p, 1);
                break;
            case "requests":
                int page = 1;
                if (args.length >= 2) {
                    try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }
                handleRequests(p, page);
                break;
            case "removeall":
                handleRemoveAll(p, args);
                break;
            case "notifications":
            case "notif":
                handleToggleNotifications(p);
                break;
            case "settings":
                handleSettings(p, args);
                break;
            default:
                sendHelp(p);
        }
    }

    private void sendHelp(ProxiedPlayer p) {
        sendWrapped(p,
                ChatColor.GREEN + "Friends commands:",
                ChatColor.YELLOW + "/f add <player>" + ChatColor.GRAY + " - Send a friend request",
                ChatColor.YELLOW + "/f remove <player>" + ChatColor.GRAY + " - Remove a friend",
                ChatColor.YELLOW + "/f accept <player>" + ChatColor.GRAY + " - Accept a request",
                ChatColor.YELLOW + "/f deny <player>" + ChatColor.GRAY + " - Deny a request",
                ChatColor.YELLOW + "/f list" + ChatColor.GRAY + " - List your friends",
                ChatColor.YELLOW + "/f requests <page>" + ChatColor.GRAY + " - Show incoming requests",
                ChatColor.YELLOW + "/f removeall" + ChatColor.GRAY + " - Remove all friends (confirm)",
                ChatColor.YELLOW + "/f notifications|notif" + ChatColor.GRAY + " - Toggle join/leave notifications",
                ChatColor.YELLOW + "/f settings" + ChatColor.GRAY + " - Show or change settings"
        );
    }

    private void sendWrapped(net.md_5.bungee.api.CommandSender sender, String... lines) {
        String sep = "&9&o&m--------------------------------------------------";
        sender.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', sep));
        for (String l : lines) {
            sender.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', l));
        }
        sender.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', sep));
    }

    private void sendWrappedSingle(net.md_5.bungee.api.CommandSender sender, String line) {
        sendWrapped(sender, line);
    }

    private void handleAdd(ProxiedPlayer sender, String targetName) {
        // Prefer proxy-wide lookup (works regardless of server API shape)
        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetName);
        if (target == null) {
            // Fallback: search all players for a matching name
            target = ProxyServer.getInstance().getPlayers().stream()
                    .filter(p -> p.getName().equalsIgnoreCase(targetName)).findFirst().orElse(null);
        }
        if (target == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.sendRequest(sender.getUniqueId(), target.getUniqueId());
        if (ok) {
            sendWrappedSingle(sender, ChatColor.GREEN + "Friend request sent to " + target.getName());
            // Format: {player} &esend you a friend request!\n&a&l[ACCEPT] &c&l[DENY]
            // Attempt to send clickable components (Bungee supports this). If not supported, fall back to text.
            String sep = "&9&o&m--------------------------------------------------";
            try {
                net.md_5.bungee.api.chat.TextComponent sepComp = new net.md_5.bungee.api.chat.TextComponent(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', sep));
                net.md_5.bungee.api.chat.TextComponent base = new net.md_5.bungee.api.chat.TextComponent(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', sender.getName() + " &esend you a friend request!"));
                net.md_5.bungee.api.chat.TextComponent accept = new net.md_5.bungee.api.chat.TextComponent(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', "&a&l[ACCEPT]"));
                accept.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/f accept " + sender.getName()));
                accept.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.ComponentBuilder("Click to accept").create()));
                net.md_5.bungee.api.chat.TextComponent deny = new net.md_5.bungee.api.chat.TextComponent(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', "&c&l[DENY]"));
                deny.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/f deny " + sender.getName()));
                deny.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.ComponentBuilder("Click to deny").create()));
                target.sendMessage(sepComp);
                target.sendMessage(base);
                target.sendMessage(accept, new net.md_5.bungee.api.chat.TextComponent(" "), deny);
                target.sendMessage(sepComp);
            } catch (Throwable t) {
                String base = ChatColor.YELLOW + sender.getName() + ChatColor.RESET + ChatColor.translateAlternateColorCodes('&', " &esend you a friend request!");
                String buttons = ChatColor.translateAlternateColorCodes('&', "&a&l[ACCEPT] &c&l[DENY]");
                sendWrapped(target, base, buttons);
            }
        } else {
            sendWrappedSingle(sender, ChatColor.RED + "Could not send friend request (maybe already friends or request exists).");
        }
    }

    private void handleRemove(ProxiedPlayer sender, String targetName) {
        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetName);
        java.util.UUID targetId = null;
        if (target != null) targetId = target.getUniqueId();
        else targetId = friendService.findUuidForName(targetName);
        if (targetId == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.removeFriend(sender.getUniqueId(), targetId);
        if (ok) sendWrappedSingle(sender, ChatColor.GREEN + "Removed " + targetName + " from your friends.");
        else sendWrappedSingle(sender, ChatColor.RED + "You are not friends with " + targetName);
    }

    private void handleAccept(ProxiedPlayer sender, String targetName) {
        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetName);
        java.util.UUID targetId = null;
        if (target != null) targetId = target.getUniqueId();
        else targetId = friendService.findUuidForName(targetName);
        if (targetId == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.acceptRequest(sender.getUniqueId(), targetId);
        if (ok) {
            sendWrappedSingle(sender, ChatColor.GREEN + "You accepted " + targetName + "'s friend request.");
            ProxiedPlayer t = target != null ? target : ProxyServer.getInstance().getPlayer(targetId);
            if (t != null) sendWrappedSingle(t, ChatColor.GREEN + sender.getName() + " accepted your friend request.");
        } else sendWrappedSingle(sender, ChatColor.RED + "No pending request from " + targetName);
    }

    private void handleDeny(ProxiedPlayer sender, String targetName) {
        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetName);
        java.util.UUID targetId = null;
        if (target != null) targetId = target.getUniqueId();
        else targetId = friendService.findUuidForName(targetName);
        if (targetId == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.denyRequest(sender.getUniqueId(), targetId);
        if (ok) sendWrappedSingle(sender, ChatColor.GREEN + "Denied friend request from " + targetName);
        else sendWrappedSingle(sender, ChatColor.RED + "No pending request from " + targetName);
    }

    private void handleList(ProxiedPlayer sender, int page) {
        List<UUID> friends = friendService.listFriends(sender.getUniqueId(), page, 10);
        if (friends.isEmpty()) { sendWrappedSingle(sender, ChatColor.YELLOW + "No friends."); return; }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(ChatColor.GREEN + "Friends (page " + page + "):");
        for (UUID u : friends) {
            net.md_5.bungee.api.connection.ProxiedPlayer online = ProxyServer.getInstance().getPlayer(u);
            if (online != null && online.isConnected()) {
                // Try to retrieve display name (may be provided by a proxy plugin). Use reflection so we don't depend on specific methods.
                String display = null;
                try {
                    java.lang.reflect.Method m = online.getClass().getMethod("getDisplayName");
                    Object val = m.invoke(online);
                    if (val != null) display = val.toString();
                } catch (Throwable ignored) {}
                if (display == null) display = online.getName();
                display = com.friends.common.util.LuckPermsIntegration.formatDisplay(u, display);

                // Prefer last-known server from the service if available (avoid calling getServer() which may be missing at runtime)
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
        sendWrapped(sender, lines.toArray(new String[0]));
    }

    private void handleRequests(ProxiedPlayer sender, int page) {
        List<UUID> requests = friendService.listRequests(sender.getUniqueId(), page, 10);
        if (requests.isEmpty()) { sendWrappedSingle(sender, ChatColor.YELLOW + "No requests."); return; }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(ChatColor.GREEN + "Friend requests (page " + page + "):");
        for (UUID u : requests) {
            lines.add(ChatColor.AQUA + u.toString());
        }
        sendWrapped(sender, lines.toArray(new String[0]));
    }

    private void handleRemoveAll(ProxiedPlayer sender, String[] args) {
        if (args.length >= 2 && "confirm".equalsIgnoreCase(args[1])) {
            List<UUID> list = friendService.listFriends(sender.getUniqueId(), 1, Integer.MAX_VALUE);
            for (UUID f : list) {
                friendService.removeFriend(sender.getUniqueId(), f);
            }
            sendWrappedSingle(sender, ChatColor.GREEN + "All friends removed.");
        } else {
            sendWrappedSingle(sender, ChatColor.YELLOW + "This will remove ALL friends. To confirm: /f removeall confirm");
        }
    }

    private void handleToggleNotifications(ProxiedPlayer sender) {
        boolean current = friendService.getNotifications(sender.getUniqueId());
        friendService.toggleNotifications(sender.getUniqueId(), !current);
        sendWrappedSingle(sender, ChatColor.GREEN + "Notifications " + (!current ? "enabled" : "disabled"));
    }

    private void handleSettings(ProxiedPlayer sender, String[] args) {
        if (args.length == 1) {
            int expiry = friendService.getRequestExpiry(sender.getUniqueId());
            boolean notif = friendService.getNotifications(sender.getUniqueId());
            sendWrapped(sender,
                    ChatColor.GREEN + "Settings:",
                    ChatColor.YELLOW + "request_expiry_minutes: " + ChatColor.WHITE + expiry,
                    ChatColor.YELLOW + "notifications: " + ChatColor.WHITE + notif,
                    ChatColor.GRAY + "Use /f settings expiry <minutes> to change expiry, /f settings notifications <on|off> to toggle",
                    ChatColor.GRAY + "Use /f settings use_short_prefix <on|off> to toggle short prefix"
            );
            return;
        }
        if (args.length >= 3) {
            String key = args[1].toLowerCase();
            switch (key) {
                case "expiry":
                    try {
                        int m = Integer.parseInt(args[2]);
                        friendService.setRequestExpiry(sender.getUniqueId(), m);
                        sendWrappedSingle(sender, ChatColor.GREEN + "Request expiry set to " + m + " minutes.");
                    } catch (NumberFormatException e) {
                        sendWrappedSingle(sender, ChatColor.RED + "Invalid number.");
                    }
                    break;
                case "notifications":
                    String v = args[2].toLowerCase();
                    if ("on".equals(v) || "true".equals(v)) {
                        friendService.toggleNotifications(sender.getUniqueId(), true);
                        sendWrappedSingle(sender, ChatColor.GREEN + "Notifications enabled.");
                    } else if ("off".equals(v) || "false".equals(v)) {
                        friendService.toggleNotifications(sender.getUniqueId(), false);
                        sendWrappedSingle(sender, ChatColor.GREEN + "Notifications disabled.");
                    } else {
                        sendWrappedSingle(sender, ChatColor.RED + "Use on/off");
                    }
                    break;
                case "prefix":
                    String prefix = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                    friendService.setPrefix(sender.getUniqueId(), prefix);
                    sendWrappedSingle(sender, ChatColor.GREEN + "Prefix set to: " + ChatColor.WHITE + prefix);
                    break;
                case "use_short_prefix":
                    String val = args[2].toLowerCase();
                    if ("on".equals(val) || "true".equals(val)) {
                        friendService.setUseShortPrefix(true);
                        sendWrappedSingle(sender, ChatColor.GREEN + "Short prefix enabled.");
                    } else if ("off".equals(val) || "false".equals(val)) {
                        friendService.setUseShortPrefix(false);
                        sendWrappedSingle(sender, ChatColor.GREEN + "Short prefix disabled.");
                    } else sendWrappedSingle(sender, ChatColor.RED + "Use on/off");
                    break;
                default:
                    sendWrappedSingle(sender, ChatColor.RED + "Unknown setting.");
            }
        } else {
            sendWrappedSingle(sender, ChatColor.RED + "Usage: /f settings <expiry|notifications> <value>");
        }
    }
}
