package com.friends.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Human-readable "last seen" times and presence lines, as Friends shows them in chat; for menus, scoreboards, websites
 * and other plugins' UIs. Example: {@code LastSeen.format(friend.getLastSeen())} gives
 * {@code "Last seen 17 minutes ago"}, and {@link #line} gives {@code "🟢 Bob — Playing BedWars Solo"}.
 */
public final class LastSeen {
    private static final long[] SIZES = {60, 60, 24, 30, 12};
    private static final String[] UNITS = {"minute", "hour", "day", "month", "year"};
    // ponytail: emoji for outside the game only. Chat in game uses a coloured "●" (U+25CF): 1.8 clients' fonts have
    // no supplementary-plane emoji like 🟢 (U+1F7E2) and may draw boxes.
    private static final String ONLINE = "\uD83D\uDFE2 ";
    private static final String OFFLINE = "\u26AB ";
    private static final String DASH = " \u2014 ";

    private LastSeen() {}

    /**
     * How long ago {@code then} was, in its largest whole unit (a month counts 30 days).
     *
     * @param then a past instant
     * @param now  the current instant
     * @return {@code "just now"} under a minute (or for a future {@code then}), else e.g. {@code "1 minute ago"},
     *         {@code "17 minutes ago"}, {@code "3 days ago"}, {@code "2 years ago"}
     */
    public static String ago(Instant then, Instant now) {
        long n = Math.max(0, Duration.between(then, now).getSeconds());
        if (n < 60) return "just now";
        int unit = -1;
        while (unit + 1 < UNITS.length && n >= SIZES[unit + 1]) n /= SIZES[++unit];
        return n + " " + UNITS[unit] + (n == 1 ? "" : "s") + " ago";
    }

    /**
     * {@link #ago} as a sentence.
     *
     * @param then a past instant
     * @param now  the current instant
     * @return e.g. {@code "Last seen 17 minutes ago"} or {@code "Last seen just now"}
     */
    public static String format(Instant then, Instant now) {
        return "Last seen " + ago(then, now);
    }

    /**
     * {@link #format(Instant, Instant)} as of now.
     *
     * @param then a past instant, such as {@link Friend#getLastSeen()}
     * @return e.g. {@code "Last seen 3 days ago"}
     */
    public static String format(Instant then) {
        return format(then, Instant.now());
    }

    /**
     * A player's presence in a few words, with an emoji.
     *
     * @param online   whether they are visibly online ({@code FriendsAPI.getStatus} is present and not
     *                 {@link Status#OFFLINE})
     * @param lastSeen when they were last online, e.g. {@link Friend#getLastSeen()}; unused if {@code online}
     * @param now      the current instant
     * @return {@code "🟢 Online"}, or e.g. {@code "⚫ Last seen 17 minutes ago"} ({@code "⚫ Offline"} if
     *         {@code lastSeen} is null)
     */
    public static String presence(boolean online, Instant lastSeen, Instant now) {
        if (online) return ONLINE + "Online";
        return OFFLINE + (lastSeen == null ? "Offline" : format(lastSeen, now));
    }

    /**
     * {@link #presence(boolean, Instant, Instant)} as of now.
     *
     * @param online   whether they are visibly online
     * @param lastSeen when they were last online; unused if {@code online}
     * @return e.g. {@code "🟢 Online"} or {@code "⚫ Last seen 3 days ago"}
     */
    public static String presence(boolean online, Instant lastSeen) {
        return presence(online, lastSeen, Instant.now());
    }

    /**
     * Rich presence, like a line of {@code /f list}: what an online player is doing, else when they were last seen.
     *
     * @param name     the name to show (e.g. {@link Friend#getName()} or the nickname)
     * @param online   whether they are visibly online
     * @param activity what they are doing ({@code FriendsAPI.getActivity}), or null; unused if offline
     * @param lastSeen when they were last online; unused if {@code online}
     * @param now      the current instant
     * @return e.g. {@code "🟢 Bob — Playing BedWars Solo"}, {@code "🟢 Bob — Online"} (no activity) or
     *         {@code "⚫ Bob — Last seen 3 days ago"}
     */
    public static String line(String name, boolean online, PlayerActivity activity, Instant lastSeen, Instant now) {
        if (online) return ONLINE + name + DASH + (activity != null ? activity.describe() : "Online");
        return OFFLINE + name + DASH + (lastSeen == null ? "Offline" : format(lastSeen, now));
    }

    /**
     * {@link #line(String, boolean, PlayerActivity, Instant, Instant)} as of now.
     *
     * @param name     the name to show
     * @param online   whether they are visibly online
     * @param activity what they are doing, or null; unused if offline
     * @param lastSeen when they were last online; unused if {@code online}
     * @return e.g. {@code "🟢 Bob — Playing BedWars Solo"} or {@code "⚫ Bob — Last seen 17 minutes ago"}
     */
    public static String line(String name, boolean online, PlayerActivity activity, Instant lastSeen) {
        return line(name, online, activity, lastSeen, Instant.now());
    }
}
