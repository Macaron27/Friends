package com.friends.api;

/** Outcome of a {@link FriendsAPI} action. */
public enum Result {
    /** Done: the request was sent, accepted or denied, the friend removed, or the setting changed. */
    SUCCESS,
    /** {@link FriendsAPI#sendRequest}: the target had already asked the sender, so they are now friends. */
    BECAME_FRIENDS,
    /** The acting player is not online on this server (proxy), or their data is still loading. */
    NOT_LOADED,
    /** The target player has never joined. */
    PLAYER_NOT_FOUND,
    /** The player targeted themselves. */
    SELF,
    /** They are already friends. */
    ALREADY_FRIENDS,
    /** The sender already has a pending request to the target. */
    ALREADY_REQUESTED,
    /** No pending request from that player (never sent, already answered or expired). */
    NO_REQUEST,
    /** They are not friends. */
    NOT_FRIENDS,
    /** The acting player has reached the friend limit ({@code max-friends}). */
    LIMIT_REACHED,
    /** The target has reached the friend limit ({@code max-friends}). */
    TARGET_LIMIT_REACHED,
    /** A null or invalid argument (for nicknames: 1 to 16 letters, digits, spaces or underscores). */
    INVALID_ARGUMENT,
    /** A plugin cancelled the action's event. */
    CANCELLED,
    /** A database error (Friends logs it). */
    ERROR,
    /** The target is offline, or appearing offline (private messages). Last, so earlier ordinals never move. */
    NOT_ONLINE;

    /**
     * Whether the action happened.
     *
     * @return true for {@link #SUCCESS} and {@link #BECAME_FRIENDS}
     */
    public boolean isSuccess() {
        return this == SUCCESS || this == BECAME_FRIENDS;
    }
}
