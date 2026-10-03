package com.friends.core;

import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GOLD;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.friends.api.LastSeen;
import com.friends.api.PlayerActivity;
import com.friends.api.Status;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Hypixel-style chat output. Player-typed text only ever enters through {@code unparsed}/{@code text}. */
final class Messages {
    private Messages() {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    static final Component LINE = text("-----------------------------------------------------", NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH);

    // status null = offline (or appearing offline); server and activity null when unknown
    record Entry(Friend friend, Status status, String server, PlayerActivity activity) {}

    // --- building blocks ---

    static Component box(Component... lines) {
        var b = text().append(LINE);
        for (Component line : lines) b.append(newline()).append(line);
        return b.append(newline()).append(LINE).build();
    }

    private static Component mm(String template, TagResolver... resolvers) {
        return MM.deserialize(template, resolvers);
    }

    private static TagResolver player(Component name) {
        return Placeholder.component("player", name);
    }

    /** Rank prefix + name. Names/nicknames are validated to contain no '&', so legacy parsing is safe. */
    static Component name(String prefix, String name) {
        return prefix == null || prefix.isBlank()
                ? text(name, GRAY)
                : LEGACY.deserialize(prefix.replace('§', '&') + name);
    }

    static Component name(Storage.PlayerRow p) {
        return name(p.prefix(), p.name());
    }

    /** How the owner sees a friend: their private nickname if set. */
    static Component friendName(Friend f) {
        return name(f.prefix(), f.nickname() != null ? f.nickname() : f.name());
    }

    private static Component button(String label, NamedTextColor color, String command, String hover) {
        return text(label, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(text(hover, GRAY)));
    }

    // --- command replies ---

    // Fixed messages are built once (MiniMessage parsing costs microseconds per call).
    private static final Component[] HELP_LINES = {
            mm("<green>Friend Commands:"),
            mm("<yellow>/f add \\<player> <gray>- <aqua>Send a friend request"),
            mm("<yellow>/f accept \\<player> <gray>- <aqua>Accept a friend request"),
            mm("<yellow>/f deny \\<player> <gray>- <aqua>Decline a friend request"),
            mm("<yellow>/f list [best] [page] <gray>- <aqua>List your friends"),
            mm("<yellow>/f requests <gray>- <aqua>View pending friend requests"),
            mm("<yellow>/f remove \\<player> <gray>- <aqua>Remove a friend"),
            mm("<yellow>/f best \\<player> <gray>- <aqua>Toggle best friend"),
            mm("<yellow>/f nickname \\<player> [nickname] <gray>- <aqua>Set a nickname only you can see"),
            mm("<yellow>/f removeall <gray>- <aqua>Remove all friends except best friends"),
            mm("<yellow>/f notifications <gray>- <aqua>Toggle friend join/leave messages"),
            mm("<yellow>/f ignore [player] <gray>- <aqua>Ignore a player (alone: list who you ignore)"),
            mm("<yellow>/f unignore \\<player> <gray>- <aqua>Stop ignoring a player"),
            mm("<yellow>/status [online|away|busy|offline] <gray>- <aqua>Set your online status")};
    private static final Component HELP = box(HELP_LINES);
    private static final Component HELP_WITH_MESSAGES = box(Stream.concat(Arrays.stream(HELP_LINES), Stream.of(
            mm("<yellow>/msg \\<friend> \\<message> <gray>- <aqua>Send a friend a private message"),
            mm("<yellow>/r \\<message> <gray>- <aqua>Reply to your last private message"))).toArray(Component[]::new));

    /** {@code privateMessages}: with {@code /msg} and {@code /r}. */
    static Component help(boolean privateMessages) {
        return privateMessages ? HELP_WITH_MESSAGES : HELP;
    }

    static Component usage(String usage) {
        return box(mm("<red>Usage: <usage>", Placeholder.unparsed("usage", usage)));
    }

    private static final Component ERROR = box(mm("<red>Something went wrong, please try again later."));

    static Component error() {
        return ERROR;
    }

    private static final Component NOT_LOADED = box(mm("<red>Your friends data isn't loaded yet, please try again in a moment."));

    static Component notLoaded() {
        return NOT_LOADED;
    }

    static Component notFound(String input) {
        return box(mm("<red>Can't find a player by the name of '<name>'", Placeholder.unparsed("name", input)));
    }

    private static final Component ADD_SELF = box(mm("<red>You can't add yourself as a friend!"));

    static Component addSelf() {
        return ADD_SELF;
    }

    static Component alreadyFriends(Component p) {
        return box(mm("<red>You're already friends with <player><red>!", player(p)));
    }

    static Component alreadySent(Component p) {
        return box(mm("<red>You've already sent a friend request to <player><red>!", player(p)));
    }

    static Component limitSelf(int max) {
        return box(mm("<red>You can't have more than <max> friends!", Placeholder.unparsed("max", Integer.toString(max))));
    }

    static Component limitOther(Component p) {
        return box(mm("<player> <red>has reached the maximum number of friends!", player(p)));
    }

    static Component sent(Component p, long minutes) {
        return box(mm("<yellow>You sent a friend request to <player><yellow>! They have <minutes> minutes to accept it!",
                player(p), Placeholder.unparsed("minutes", Long.toString(minutes))));
    }

    static Component received(Component from, String fromName) {
        return box(
                mm("<yellow>Friend request from <player>", player(from)),
                text().append(button("[ACCEPT]", GREEN, "/f accept " + fromName, "Click to accept the friend request"))
                        .append(text(" - ", DARK_GRAY))
                        .append(button("[DENY]", RED, "/f deny " + fromName, "Click to deny the friend request"))
                        .build());
    }

    static Component nowFriends(Component p) {
        return box(mm("<green>You are now friends with <player>", player(p)));
    }

    static Component noRequest(String input) {
        return box(mm("<red>You don't have a friend request from '<name>'!", Placeholder.unparsed("name", input)));
    }

    static Component declined(Component p) {
        return box(mm("<yellow>Declined <player><yellow>'s friend request!", player(p)));
    }

    static Component expiredOutgoing(Component p) {
        return box(mm("<yellow>Your friend request to <player> <yellow>has expired.", player(p)));
    }

    static Component expiredIncoming(Component p) {
        return box(mm("<yellow>The friend request from <player> <yellow>has expired.", player(p)));
    }

    static Component notFriend(String input) {
        return box(mm("<red>'<name>' isn't on your friends list!", Placeholder.unparsed("name", input)));
    }

    static Component removed(Component p) {
        return box(mm("<yellow>You removed <player> <yellow>from your friends list!", player(p)));
    }

    static Component best(Component p, boolean best) {
        return box(best
                ? mm("<player> <green>is now one of your best friends!", player(p))
                : mm("<player> <yellow>is no longer one of your best friends.", player(p)));
    }

    static Component nickname(Component p, String nickname) {
        return box(nickname == null
                ? mm("<yellow>Cleared your nickname for <player><yellow>.", player(p))
                : mm("<green>You'll now see <player> <green>as <yellow><nick><green>.", player(p), Placeholder.unparsed("nick", nickname)));
    }

    private static final Component INVALID_NICKNAME = box(mm("<red>Nicknames must be 1-16 letters, digits, spaces or underscores."));

    static Component invalidNickname() {
        return INVALID_NICKNAME;
    }

    static Component removeAllConfirm(int count) {
        return box(
                mm("<yellow>This will remove <red><count></red> friends (best friends are kept).",
                        Placeholder.unparsed("count", Integer.toString(count))),
                button("[CONFIRM]", RED, "/f removeall confirm", "Click to remove them"));
    }

    static Component removedAll(int count) {
        return box(mm("<yellow>Removed <count> friends from your friends list.", Placeholder.unparsed("count", Integer.toString(count))));
    }

    private static final Component NOTHING_TO_REMOVE = box(mm("<yellow>You don't have any friends to remove (best friends are kept)."));

    static Component nothingToRemove() {
        return NOTHING_TO_REMOVE;
    }

    static Component notifications(boolean enabled) {
        return box(enabled
                ? mm("<green>Enabled friend join/leave notifications.")
                : mm("<yellow>Disabled friend join/leave notifications."));
    }

    static Component status(Status status) {
        return text(label(status), color(status));
    }

    private static NamedTextColor color(Status status) {
        return switch (status) {
            case ONLINE -> GREEN;
            case AWAY -> YELLOW;
            case BUSY -> RED;
            case OFFLINE -> GRAY;
        };
    }

    private static String label(Status status) {
        return switch (status) {
            case ONLINE -> "Online";
            case AWAY -> "Away";
            case BUSY -> "Busy";
            case OFFLINE -> "Appear Offline";
        };
    }

    static Component statusSet(Status status) {
        return box(mm("<yellow>Your status is now <status><yellow>.", Placeholder.component("status", status(status))));
    }

    static Component statusMenu(Status current) {
        var buttons = text();
        for (Status s : Status.values()) {
            buttons.append(button("[" + label(s) + "]", color(s), "/status " + s.name().toLowerCase(), "Set your status to " + label(s)))
                    .append(text(" "));
        }
        return box(mm("<yellow>Your status is <status><yellow>. Click to change it:", Placeholder.component("status", status(current))),
                buttons.build());
    }

    // --- private messages ---

    // Hypixel's look: "From [VIP] Bob: hi" / "To [VIP] Bob: hi", the text in gray. Clicking a line starts a reply.
    private static final Component FROM = text("From ", LIGHT_PURPLE);
    private static final Component TO = text("To ", LIGHT_PURPLE);
    private static final Component COLON = text(": ", GRAY);

    /** {@code text} is the sender's: plain text, never parsed. {@code name} is how the receiver sees them. */
    static Component messageFrom(Component name, String username, String text) {
        return privateLine(FROM, name, username, text);
    }

    static Component messageTo(Component name, String username, String text) {
        return privateLine(TO, name, username, text);
    }

    private static Component privateLine(Component direction, Component name, String username, String text) {
        return text().append(direction).append(name).append(COLON).append(text(text, GRAY))
                .clickEvent(ClickEvent.suggestCommand("/msg " + username + " "))
                .hoverEvent(HoverEvent.showText(text("Click to reply", GRAY)))
                .build();
    }

    static Component offline(Component friend, Instant lastSeen, Instant now) {
        return box(mm("<player> <red>is offline. <gray>(<seen>)", player(friend),
                Placeholder.unparsed("seen", LastSeen.format(lastSeen, now))));
    }

    /** {@code wait}: until the oldest message in the window leaves it (rounded up to whole seconds). */
    static Component tooFast(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return box(mm("<red>You're sending messages too fast! Try again in <n> second<s>.",
                Placeholder.unparsed("n", Long.toString(seconds)), Placeholder.unparsed("s", seconds == 1 ? "" : "s")));
    }

    private static final Component AFK_TEXT = text("I'm currently AFK, and will answer when I am available.", GRAY, TextDecoration.ITALIC);
    private static final Component AUTO_REPLY = text(" (auto-reply)", DARK_GRAY);

    /** What a friend whose status is Away "answers": a private message line, marked as automatic. */
    static Component afkReply(Component name, String username) {
        return text().append(FROM).append(name).append(COLON).append(AFK_TEXT).append(AUTO_REPLY)
                .clickEvent(ClickEvent.suggestCommand("/msg " + username + " "))
                .hoverEvent(HoverEvent.showText(text("Click to reply", GRAY)))
                .build();
    }

    private static final Component NOBODY_TO_REPLY = box(mm("<red>You have nobody to reply to! Use <yellow>/msg \\<friend> \\<message>"));

    static Component nobodyToReply() {
        return NOBODY_TO_REPLY;
    }

    // --- ignore ---

    private static final Component IGNORE_SELF = box(mm("<red>You can't ignore yourself!"));

    static Component ignoreSelf() {
        return IGNORE_SELF;
    }

    static Component alreadyIgnoring(Component p) {
        return box(mm("<yellow>You're already ignoring <player><yellow>.", player(p)));
    }

    static Component ignored(Component p, boolean wasFriend) {
        Component done = mm("<green>You're now ignoring <player><green>: they can't message you or send you friend requests.", player(p));
        return wasFriend ? box(done, mm("<gray>They were removed from your friends list (they aren't told).")) : box(done);
    }

    static Component notIgnoring(String input) {
        return box(mm("<red>You aren't ignoring '<name>'!", Placeholder.unparsed("name", input)));
    }

    static Component unignored(Component p) {
        return box(mm("<green>You're no longer ignoring <player><green>.", player(p)));
    }

    private static final Component NOBODY_IGNORED = box(mm("<yellow>You aren't ignoring anyone. To block a player: <aqua>/f ignore \\<player>"));

    /** Each name suggests (doesn't run) {@code /f unignore}: one click shouldn't undo an ignore. */
    static Component ignoreList(List<String> names) {
        if (names.isEmpty()) return NOBODY_IGNORED;
        var line = text();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) line.append(text(", ", DARK_GRAY));
            line.append(text(names.get(i), GRAY).clickEvent(ClickEvent.suggestCommand("/f unignore " + names.get(i)))
                    .hoverEvent(HoverEvent.showText(text("Click to unignore", GRAY))));
        }
        return box(mm("<yellow>Ignored players (<count>):", Placeholder.unparsed("count", Integer.toString(names.size()))), line.build());
    }

    static Component youIgnore(Component p, String username) {
        return box(mm("<red>You're ignoring <player><red>! Use <yellow>/f unignore <name></yellow> first.", player(p),
                Placeholder.unparsed("name", username)));
    }

    static Component cantRequest(Component p) {
        return box(mm("<red>You can't send <player> <red>a friend request.", player(p)));
    }

    // --- notifications ---

    // Sent to every online friend on each join/leave: plain builders, no parsing (same styling as
    // "<green>Friend > <player> <yellow>joined.": the name inherits green unless it has its own colour).
    private static final Component FRIEND_PREFIX = text("Friend > ");
    private static final Component SPACE = text(" ");
    private static final Component JOINED = text("joined.", YELLOW);
    private static final Component LEFT = text("left.", YELLOW);

    static Component joined(Component p) {
        return text().color(GREEN).append(FRIEND_PREFIX).append(p).append(SPACE).append(JOINED).build();
    }

    static Component left(Component p) {
        return text().color(GREEN).append(FRIEND_PREFIX).append(p).append(SPACE).append(LEFT).build();
    }

    static Component pending(long count) {
        return mm("<yellow>You have <green><count></green> pending friend request<s>. ",
                Placeholder.unparsed("count", Long.toString(count)), Placeholder.unparsed("s", count == 1 ? "" : "s"))
                .append(button("[VIEW]", GREEN, "/f requests", "Click to view your friend requests"));
    }

    // --- lists ---

    private static final Component NO_BEST_FRIENDS = box(mm("<yellow>You don't have any best friends yet! Use <aqua>/f best \\<player>"));
    private static final Component NO_FRIENDS = box(mm("<yellow>You don't have any friends yet! Add some with <aqua>/f add \\<player>"));

    static Component noFriends(boolean best) {
        return best ? NO_BEST_FRIENDS : NO_FRIENDS;
    }

    static Component list(List<Entry> entries, int page, int pages, boolean best, Instant now) {
        String command = best ? "/f list best " : "/f list ";
        var header = text("                 ");
        header = header.append(page > 1
                ? text("<< ", GOLD).clickEvent(ClickEvent.runCommand(command + (page - 1))).hoverEvent(HoverEvent.showText(text("Previous page", GRAY)))
                : text("   "));
        header = header.append(text((best ? "Best Friends" : "Friends") + " (Page " + page + " of " + pages + ")", GOLD));
        if (page < pages) {
            header = header.append(text(" >>", GOLD).clickEvent(ClickEvent.runCommand(command + (page + 1)))
                    .hoverEvent(HoverEvent.showText(text("Next page", GRAY))));
        }
        Component[] lines = new Component[entries.size() + 1];
        lines[0] = header;
        for (int i = 0; i < entries.size(); i++) lines[i + 1] = entry(entries.get(i), now);
        return box(lines);
    }

    // ponytail: "●" (U+25CF) in the status colour, not the emoji 🟢: it is in the Basic Multilingual Plane, which 1.8
    // clients' fonts cover; supplementary-plane emoji may render as boxes there.
    private static final Component DOT_OFFLINE = text("● ", DARK_GRAY);
    private static final Component DASH = text(" — ", DARK_GRAY);

    /** "● Bob — Playing BedWars Solo", "● Bob — Away · In lobby-1", "● Bob — Last seen 17 minutes ago". */
    private static Component entry(Entry e, Instant now) {
        Friend f = e.friend();
        Component name = friendName(f);
        if (f.best()) name = name.decorate(TextDecoration.BOLD);
        var hover = text();
        if (f.nickname() != null) hover.append(text("Username: ", GRAY)).append(name(f.prefix(), f.name())).append(newline());
        hover.append(text("Friends since " + DATE.format(f.since()), GRAY));
        if (e.status() == null) {
            return text().append(DOT_OFFLINE).append(name).append(DASH).append(text(LastSeen.format(f.lastSeen(), now), GRAY))
                    .hoverEvent(HoverEvent.showText(hover.build())).build();
        }
        String where = e.activity() != null ? e.activity().describe() : e.server() != null ? "In " + e.server() : "Online";
        String detail = switch (e.status()) {
            case AWAY, BUSY -> label(e.status()) + " · " + where;
            default -> where;
        };
        if (e.activity() != null && e.server() != null) hover.append(newline()).append(text("Server: " + e.server(), GRAY));
        return text().append(text("● ", color(e.status()))).append(name).append(DASH).append(text(detail, e.status() == Status.BUSY ? RED : YELLOW))
                .hoverEvent(HoverEvent.showText(hover.build())).build();
    }

    private static final Component NO_REQUESTS = box(mm("<yellow>You don't have any pending friend requests."));

    static Component noRequests() {
        return NO_REQUESTS;
    }

    static Component requests(List<Request> incoming, List<Request> outgoing, Instant now) {
        int shown = Math.min(incoming.size(), Friends.PAGE_SIZE);
        var lines = new java.util.ArrayList<Component>();
        lines.add(mm("<green>Friend Requests:"));
        for (Request r : incoming.subList(0, shown)) {
            lines.add(text().append(name(r.from())).append(text(" ")).append(button("[ACCEPT]", GREEN, "/f accept " + r.from().name(), "Click to accept"))
                    .append(text(" ")).append(button("[DENY]", RED, "/f deny " + r.from().name(), "Click to deny")).build());
        }
        if (incoming.size() > shown) lines.add(text("...and " + (incoming.size() - shown) + " more", GRAY));
        for (Request r : outgoing) {
            long left = Math.max(0, Duration.between(now, r.expires()).toSeconds());
            lines.add(text().append(text("To ", GRAY)).append(name(r.to()))
                    .append(text(" (expires in " + (left / 60) + "m " + (left % 60) + "s)", GRAY)).build());
        }
        return box(lines.toArray(Component[]::new));
    }
}
