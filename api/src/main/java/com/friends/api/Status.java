package com.friends.api;

/** Online status a player shows their friends, like Hypixel's {@code /status}. */
public enum Status {
    /** Online and available (the default). */
    ONLINE,
    /** Online, away from keyboard. */
    AWAY,
    /** Online, do not disturb. */
    BUSY,
    /** Online but appearing offline: friends see them offline and get no join/leave messages. */
    OFFLINE
}
