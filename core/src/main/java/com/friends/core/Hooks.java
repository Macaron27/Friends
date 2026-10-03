package com.friends.core;

import java.time.Instant;

import com.friends.api.PlayerActivity;
import com.friends.api.Status;
import com.friends.core.Storage.PlayerRow;

/**
 * Lets other plugins veto and observe changes made on this proxy/server: each platform turns these into its own
 * events. The vetoes ({@code ...ing}) run on the acting thread, never under the core's lock nor on the database
 * thread, and may block on listeners; the action is re-checked afterwards. The {@code ...ed} calls come exactly once
 * per change, in the order changes were applied: {@link Friends} makes them under its lock, {@link FriendsRuntime}
 * delivers them from one thread of its own (so there, they may block too).
 */
public interface Hooks {

    /** {@code from} is about to send {@code to} a request. False cancels it. */
    default boolean requesting(PlayerRow from, PlayerRow to) {
        return true;
    }

    /** {@code player} is about to accept {@code sender}'s request. False cancels it (the request stays pending). */
    default boolean befriending(PlayerRow player, PlayerRow sender) {
        return true;
    }

    default void befriended(PlayerRow player, PlayerRow sender, Instant since) {}

    /** {@code player} is about to remove {@code friend}. False keeps the friendship. */
    default boolean unfriending(PlayerRow player, Friend friend) {
        return true;
    }

    default void unfriended(PlayerRow player, Friend friend) {}

    /**
     * {@code from} is about to send their friend {@code to} a private message. Returns the text to deliver (plugins
     * may rewrite it), or null to cancel: then nothing is delivered, echoed or remembered for {@code /r}.
     */
    default String messaging(PlayerRow from, PlayerRow to, String message) {
        return message;
    }

    /** False keeps the current status. */
    default boolean statusChanging(PlayerRow player, Status from, Status to) {
        return true;
    }

    /**
     * What a player on this proxy is doing changed (null: nothing a {@code presence.rules} rule knows): they joined,
     * switched server or left.
     */
    default void activityChanged(PlayerRow player, PlayerActivity from, PlayerActivity to) {}

    Hooks NONE = new Hooks() {};
}
