package com.friends.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Human-readable "last seen" times, as Friends shows them in chat; for menus, scoreboards and other plugins' UIs.
 * Example: {@code LastSeen.format(friend.getLastSeen())} gives {@code "Last seen 17 minutes ago"}.
 */
public final class LastSeen {
    private static final long[] SIZES = {60, 60, 24, 30, 12};
    private static final String[] UNITS = {"minute", "hour", "day", "month", "year"};

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
}
