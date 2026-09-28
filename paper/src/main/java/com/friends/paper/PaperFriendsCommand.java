package com.friends.paper;

import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.friends.common.service.FriendService;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

public class PaperFriendsCommand implements CommandExecutor {
    private final FriendService friendService;
    private final FriendsPlugin plugin;

    public PaperFriendsCommand(FriendService friendService, FriendsPlugin plugin) {
        this.friendService = friendService;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("friends.use")) {
            p.sendMessage(ChatColor.RED + "You don't have permission to use friends commands.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(p);
            return true;
        }

        String sub = args.length == 0 && label.equalsIgnoreCase("fl") ? "list" : args[0].toLowerCase();
        switch (sub) {
            case "add":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f add <player>"); return true; }
                handleAdd(p, args[1]);
                break;
            case "remove":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f remove <player>"); return true; }
                handleRemove(p, args[1]);
                break;
            case "accept":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f accept <player>"); return true; }
                handleAccept(p, args[1]);
                break;
            case "deny":
                if (args.length < 2) { sendWrappedSingle(p, ChatColor.RED + "Usage: /f deny <player>"); return true; }
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
        return true;
    }

    private void sendHelp(Player p) {
        sendWrapped(p,
                ChatColor.GREEN + "Friends commands:",
                ChatColor.YELLOW + "/f add <player>" + ChatColor.WHITE + " - Send a friend request",
                ChatColor.YELLOW + "/f remove <player>" + ChatColor.WHITE + " - Remove a friend",
                ChatColor.YELLOW + "/f accept <player>" + ChatColor.WHITE + " - Accept a request",
                ChatColor.YELLOW + "/f deny <player>" + ChatColor.WHITE + " - Deny a request",
                ChatColor.YELLOW + "/f list" + ChatColor.WHITE + " - List your friends",
                ChatColor.YELLOW + "/f requests <page>" + ChatColor.WHITE + " - Show incoming requests",
                ChatColor.YELLOW + "/f removeall" + ChatColor.WHITE + " - Remove all friends (confirm)",
                ChatColor.YELLOW + "/f notifications|notif" + ChatColor.WHITE + " - Toggle join/leave notifications",
                ChatColor.YELLOW + "/f settings" + ChatColor.WHITE + " - Show or change settings"
        );
    }

    private void sendWrapped(CommandSender sender, String... lines) {
        String sep = "&9&o&n--------------------------------------------------";
        sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', sep));
        for (String l : lines) {
            sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', l));
        }
        sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', sep));
    }

    private void sendWrappedSingle(CommandSender sender, String msg) {
        sendWrapped(sender, msg);
    }

    private void handleAdd(Player sender, String targetName) {
        if (!sender.hasPermission("friends.add") && !sender.hasPermission("friends.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to add friends.");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.sendRequest(sender.getUniqueId(), target.getUniqueId());
        if (ok) {
            sendWrappedSingle(sender, ChatColor.GREEN + "Friend request sent to " + target.getName());

            // Send formatted message with clickable accept/deny on Paper
            String baseText = sender.getName() + " &esend you a friend request!";
            TextComponent base = new TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', baseText));

            TextComponent accept = new TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&a&l[ACCEPT]"));
            accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/f accept " + sender.getName()));
            accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to accept").create()));

            TextComponent deny = new TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&c&l[DENY]"));
            deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/f deny " + sender.getName()));
            deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to deny").create()));

            // Send as two lines: base, then buttons (try clickable; fallback to plain wrapped message)
            try {
                String sep = "&9&o&n--------------------------------------------------";
                TextComponent sepComp = new TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', sep));
                target.spigot().sendMessage(sepComp);
                target.spigot().sendMessage(base);
                target.spigot().sendMessage(accept, new TextComponent(" "), deny);
                target.spigot().sendMessage(sepComp);
            } catch (Throwable t) {
                String baseTextFallback = sender.getName() + " &esend you a friend request!";
                String buttonsFallback = "&a&l[ACCEPT] &c&l[DENY]";
                sendWrapped(target, org.bukkit.ChatColor.translateAlternateColorCodes('&', baseTextFallback), org.bukkit.ChatColor.translateAlternateColorCodes('&', buttonsFallback));
            }
        } else {
            sendWrappedSingle(sender, ChatColor.RED + "Could not send friend request (maybe already friends or request exists.");
        }
    }

    private void handleRemove(Player sender, String targetName) {
        if (!sender.hasPermission("friends.remove") && !sender.hasPermission("friends.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to remove friends.");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.removeFriend(sender.getUniqueId(), target.getUniqueId());
        if (ok) sendWrappedSingle(sender, ChatColor.GREEN + "Removed " + target.getName() + " from your friends.");
        else sendWrappedSingle(sender, ChatColor.RED + "You are not friends with " + target.getName());
    }

    private void handleAccept(Player sender, String targetName) {
        if (!sender.hasPermission("friends.accept") && !sender.hasPermission("friends.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to accept requests.");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.acceptRequest(sender.getUniqueId(), target.getUniqueId());
        if (ok) {
            sendWrappedSingle(sender, ChatColor.GREEN + "You accepted " + target.getName() + "'s friend request.");
            sendWrappedSingle(target, ChatColor.GREEN + sender.getName() + " accepted your friend request.");
        } else sendWrappedSingle(sender, ChatColor.RED + "No pending request from " + target.getName());
    }

    private void handleDeny(Player sender, String targetName) {
        if (!sender.hasPermission("friends.deny") && !sender.hasPermission("friends.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to deny requests.");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendWrappedSingle(sender, ChatColor.RED + "Player not found or offline."); return; }
        boolean ok = friendService.denyRequest(sender.getUniqueId(), target.getUniqueId());
        if (ok) sendWrappedSingle(sender, ChatColor.GREEN + "Denied friend request from " + target.getName());
        else sendWrappedSingle(sender, ChatColor.RED + "No pending request from " + target.getName());
    }

    private void handleList(Player sender, int page) {
        if (!sender.hasPermission("friends.list") && !sender.hasPermission("friends.use")) {
            sendWrappedSingle(sender, ChatColor.RED + "You don't have permission to list friends.");
            return;
        }
        // Page size 10
        List<UUID> friends = friendService.listFriends(sender.getUniqueId(), page, 10);
        if (friends.isEmpty()) { sendWrappedSingle(sender, ChatColor.YELLOW + "No friends."); return; }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(ChatColor.GREEN + "Friends (page " + page + "):");
        for (UUID u : friends) {
            Player online = Bukkit.getPlayer(u);
            if (online != null && online.isOnline()) {
                String display = com.friends.common.util.LuckPermsIntegration.formatDisplay(u, online.getDisplayName());
                String srv = friendService.getLastServer(u);
                if (srv != null && !srv.isEmpty()) lines.add(ChatColor.AQUA + display + ChatColor.WHITE + " - Currently in " + ChatColor.GREEN + srv);
                else lines.add(ChatColor.AQUA + display + ChatColor.WHITE + " - Currently online");
            } else {
                String lastName = friendService.getLastUsername(u);
                java.time.Instant lastSeen = friendService.getLastSeen(u);
                String name = lastName != null ? lastName : u.toString();
                String lastSeenText = com.friends.common.util.TimeUtil.formatRelative(lastSeen);
                lines.add(ChatColor.AQUA + name + ChatColor.WHITE + " - Last seen " + lastSeenText + ".");
            }
        }
        sendWrapped(sender, lines.toArray(new String[0]));
    }

    private void handleRequests(Player sender, int page) {
        if (!sender.hasPermission("friends.requests") && !sender.hasPermission("friends.use")) {
            sendWrappedSingle(sender, ChatColor.RED + "You don't have permission to view requests.");
            return;
        }
        List<UUID> requests = friendService.listRequests(sender.getUniqueId(), page, 10);
        if (requests.isEmpty()) { sendWrappedSingle(sender, ChatColor.YELLOW + "No requests."); return; }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(ChatColor.GREEN + "Friend requests (page " + page + "):");
        for (UUID u : requests) lines.add(ChatColor.AQUA + u.toString());
        sendWrapped(sender, lines.toArray(new String[0]));
    }

    private void handleRemoveAll(Player sender, String[] args) {
        // require admin or removeall permission
        if (!sender.hasPermission("friends.removeall") && !sender.hasPermission("friends.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to remove all friends.");
            return;
        }
        // Simple confirm flow: /f removeall confirm
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

    private void handleToggleNotifications(Player sender) {
        if (!sender.hasPermission("friends.notifications") && !sender.hasPermission("friends.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to toggle notifications.");
            return;
        }
        boolean current = friendService.getNotifications(sender.getUniqueId());
        friendService.toggleNotifications(sender.getUniqueId(), !current);
        sendWrappedSingle(sender, ChatColor.GREEN + "Notifications " + (!current ? "enabled" : "disabled"));
    }

    private void handleSettings(Player sender, String[] args) {
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
                    // prefix is remainder of args joined
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
