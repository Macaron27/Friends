package com.friends.common;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.friends.common.Platform.Online;

/** Parses {@code /friend <sub> ...} arguments. {@code /fl} and {@code /status} arrive as "list"/"status". */
public final class FriendCommand {
    private static final List<String> SUBCOMMANDS = List.of("add", "accept", "deny", "list", "requests", "remove",
            "best", "nickname", "removeall", "notifications", "status", "help");
    private static final int MAX_SUGGESTIONS = 100;

    private final Friends friends;
    private final Platform platform;

    public FriendCommand(Friends friends, Platform platform) {
        this.friends = friends;
        this.platform = platform;
    }

    public CompletableFuture<Void> execute(Online s, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String arg = args.length > 1 ? args[1] : null;
        CompletableFuture<Void> result = switch (sub) {
            case "help" -> friends.help(s);
            case "add" -> arg == null ? usage(s, "/f add <player>") : friends.add(s, arg);
            case "accept" -> arg == null ? usage(s, "/f accept <player>") : friends.accept(s, arg);
            case "deny", "decline" -> arg == null ? usage(s, "/f deny <player>") : friends.deny(s, arg);
            case "remove", "delete" -> arg == null ? usage(s, "/f remove <player>") : friends.remove(s, arg);
            case "best" -> arg == null ? usage(s, "/f best <player>") : friends.best(s, arg);
            case "nickname", "nick" -> arg == null
                    ? usage(s, "/f nickname <player> [nickname]")
                    : friends.nickname(s, arg, args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null);
            case "list" -> list(s, args);
            case "requests" -> friends.requests(s);
            case "removeall" -> friends.removeAll(s, "confirm".equalsIgnoreCase(arg));
            case "notifications", "notification", "notif" -> friends.toggleNotifications(s);
            case "status" -> status(s, arg);
            default -> args.length == 1 ? friends.add(s, args[0]) : friends.help(s); // "/f Steve" = "/f add Steve"
        };
        return result.exceptionally(_ -> {
            s.audience().sendMessage(Messages.error());
            return null;
        });
    }

    private CompletableFuture<Void> list(Online s, String[] args) {
        boolean best = args.length > 1 && args[1].equalsIgnoreCase("best");
        int pageArg = best ? 2 : 1;
        int page = 1;
        if (args.length > pageArg) {
            try {
                page = Integer.parseInt(args[pageArg]);
            } catch (NumberFormatException _) {
                return usage(s, "/f list [best] [page]");
            }
        }
        return friends.list(s, page, best);
    }

    private CompletableFuture<Void> status(Online s, String arg) {
        if (arg == null) return friends.status(s, null);
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "online" -> friends.status(s, Status.ONLINE);
            case "away" -> friends.status(s, Status.AWAY);
            case "busy" -> friends.status(s, Status.BUSY);
            case "offline", "invisible", "appearoffline" -> friends.status(s, Status.OFFLINE);
            default -> usage(s, "/status [online|away|busy|offline]");
        };
    }

    private static CompletableFuture<Void> usage(Online s, String usage) {
        s.audience().sendMessage(Messages.usage(usage));
        return completedFuture(null);
    }

    public List<String> suggest(Online s, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            return filter(Stream.concat(SUBCOMMANDS.stream(), platform.onlineNames().stream().filter(n -> !n.equalsIgnoreCase(s.name()))), prefix);
        }
        String prefix = args[args.length - 1];
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length > 2) return List.of();
        return switch (sub) {
            case "add" -> filter(platform.onlineNames().stream().filter(n -> !n.equalsIgnoreCase(s.name()) && !isFriend(s, n)), prefix);
            case "accept", "deny", "decline" -> filter(friends.requesterNames(s.id()).stream(), prefix);
            case "remove", "delete", "best", "nickname", "nick" -> filter(friends.friendNames(s.id()).stream(), prefix);
            case "list" -> filter(Stream.of("best"), prefix);
            case "removeall" -> filter(Stream.of("confirm"), prefix);
            case "status" -> filter(Stream.of("online", "away", "busy", "offline"), prefix);
            default -> List.of();
        };
    }

    private boolean isFriend(Online s, String name) {
        return platform.player(name).map(o -> friends.isFriend(s.id(), o.id())).orElse(false);
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).limit(MAX_SUGGESTIONS).toList();
    }
}
