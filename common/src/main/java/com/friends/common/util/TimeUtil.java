package com.friends.common.util;

import java.time.Duration;
import java.time.Instant;

public final class TimeUtil {
    private TimeUtil() {}

    public static String formatRelative(Instant then) {
        if (then == null) return "unknown";
        Duration d = Duration.between(then, Instant.now());
        long s = Math.abs(d.getSeconds());
        if (s < 60) return s + " seconds ago";
        long m = s / 60;
        if (m < 60) return m + " minutes ago";
        long h = m / 60;
        if (h < 24) return h + " hours ago";
        long days = h / 24;
        if (days < 30) return days + " days ago";
        long months = days / 30;
        if (months < 12) return months + " months ago";
        long years = months / 12;
        return years + " years ago";
    }
}