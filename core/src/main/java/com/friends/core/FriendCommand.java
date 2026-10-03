package com.friends.core;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.friends.api.Result;
import com.friends.api.Status;
import com.friends.core.Friends.Target;
import com.friends.core.Platform.Online;

/**
 * Parses {@code /friend <sub> ...} arguments. {@code /fl}, {@code /status}, {@code /msg} and {@code /r} arrive as
 * "list", "status", "msg" and "reply".
 */
public final class FriendCommand {
    private static final List<String> SUBCOMMANDS = List.of("add", "accept", "deny", "list", "requests", "remove",
            "best", "nickname", "removeall", "notifications", "status", "ignore", "unignore", "help");
    private static final int MAX_SUGGESTIONS = 100;

    private final Friends friends;
    private final boolean privateMessages;

    /** {@code privateMessages}: {@code private-messages.enabled} (else "msg" and "reply" are not commands). */
    public FriendCommand(Friends friends, boolean privateMessages) {
        this.friends = friends;
        this.privateMessages = privateMessages;
    }

    /** {@code /fl}, {@code /status}, {@code /msg} and {@code /r} are {@code /friend <sub>}: prepend {@code sub} (if any). */
    public static String[] withSub(String sub, String[] args) {
        if (sub == null) return args;
        String[] out = new String[args.length + 1];
        out[0] = sub;
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }

    /** Some platforms pass no argument while the first one is still empty; completion wants that empty argument. */
    public static String[] completing(String[] args) {
        return args.length == 0 ? new String[] {""} : args;
    }

    public CompletableFuture<Result> execute(Online s, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        Target arg = args.length > 1 ? Target.named(args[1]) : null;
        if (!privateMessages && (sub.equals("msg") || sub.equals("reply"))) sub = "help";
        CompletableFuture<Result> result = switch (sub) {
            case "help" -> friends.help(s, privateMessages);
            case "msg" -> args.length < 3 ? usage(s, "/msg <friend> <message>") : friends.message(s, arg, rest(args, 2));
            case "reply" -> args.length < 2 ? usage(s, "/r <message>") : friends.replyMessage(s, rest(args, 1));
            case "add" -> arg == null ? usage(s, "/f add <player>") : friends.add(s, arg);
            case "accept" -> arg == null ? usage(s, "/f accept <player>") : friends.accept(s, arg);
            case "deny", "decline" -> arg == null ? usage(s, "/f deny <player>") : friends.deny(s, arg);
            case "remove", "delete" -> arg == null ? usage(s, "/f remove <player>") : friends.remove(s, arg);
            case "best" -> arg == null ? usage(s, "/f best <player>") : friends.best(s, arg, null);
            case "nickname", "nick" -> arg == null
                    ? usage(s, "/f nickname <player> [nickname]")
                    : friends.nickname(s, arg, args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null);
            case "list" -> list(s, args);
            case "requests" -> friends.requests(s);
            case "removeall" -> friends.removeAll(s, args.length > 1 && "confirm".equalsIgnoreCase(args[1]));
            case "notifications", "notification", "notif" -> friends.toggleNotifications(s);
            case "status" -> status(s, args.length > 1 ? args[1] : null);
            case "ignore" -> arg == null ? friends.ignoreList(s) : friends.ignore(s, arg);
            case "unignore" -> arg == null ? usage(s, "/f unignore <player>") : friends.unignore(s, arg);
            default -> args.length == 1 ? friends.add(s, Target.named(args[0])) : friends.help(s, privateMessages); // "/f Steve" = "/f add Steve"
        };
        return result.exceptionally(_ -> {
            s.audience().sendMessage(Messages.error());
            return Result.ERROR;
        });
    }

    private static String rest(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private CompletableFuture<Result> list(Online s, String[] args) {
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

    private CompletableFuture<Result> status(Online s, String arg) {
        if (arg == null) return friends.status(s, null);
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "online" -> friends.status(s, Status.ONLINE);
            case "away" -> friends.status(s, Status.AWAY);
            case "busy" -> friends.status(s, Status.BUSY);
            case "offline", "invisible", "appearoffline" -> friends.status(s, Status.OFFLINE);
            default -> usage(s, "/status [online|away|busy|offline]");
        };
    }

    private static CompletableFuture<Result> usage(Online s, String usage) {
        s.audience().sendMessage(Messages.usage(usage));
        return completedFuture(Result.INVALID_ARGUMENT);
    }

    public List<String> suggest(Online s, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            return filter(Stream.concat(SUBCOMMANDS.stream(), friends.onlineNames().stream().filter(n -> !n.equalsIgnoreCase(s.name()))), prefix);
        }
        String prefix = args[args.length - 1];
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length > 2) return List.of();
        return switch (sub) {
            case "add" -> {
                var friendNames = friends.friendNames(s.id()).stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                yield filter(friends.onlineNames().stream()
                        .filter(n -> !n.equalsIgnoreCase(s.name()) && !friendNames.contains(n.toLowerCase(Locale.ROOT))), prefix);
            }
            case "accept", "deny", "decline" -> filter(friends.requesterNames(s.id()).stream(), prefix);
            case "ignore" -> {
                var ignored = friends.ignoredNames(s.id()).stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                yield filter(friends.onlineNames().stream()
                        .filter(n -> !n.equalsIgnoreCase(s.name()) && !ignored.contains(n.toLowerCase(Locale.ROOT))), prefix);
            }
            case "unignore" -> filter(friends.ignoredNames(s.id()).stream(), prefix);
            case "remove", "delete", "best", "nickname", "nick" -> filter(friends.friendNames(s.id()).stream(), prefix);
            case "msg" -> privateMessages ? filter(friends.onlineFriendNames(s.id()).stream(), prefix) : List.of();
            case "list" -> filter(Stream.of("best"), prefix);
            case "removeall" -> filter(Stream.of("confirm"), prefix);
            case "status" -> filter(Stream.of("online", "away", "busy", "offline"), prefix);
            default -> List.of();
        };
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER).limit(MAX_SUGGESTIONS).toList();
    }
}
