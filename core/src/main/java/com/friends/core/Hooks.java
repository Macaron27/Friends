package com.friends.core;

import java.time.Instant;

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

    /** False keeps the current status. */
    default boolean statusChanging(PlayerRow player, Status from, Status to) {
        return true;
    }

    Hooks NONE = new Hooks() {};
}
