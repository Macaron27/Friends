package com.friends.api.bukkit;

import java.util.UUID;

import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Two players are about to become friends: {@code player} accepted {@code target}'s request (or asked back while one
 * was pending). Cancel to prevent it; the request then stays pending.
 *
 * <p>To react to the friendship itself (rewards, statistics), listen to {@link FriendAddedEvent}, which fires exactly
 * once per friendship that actually starts.
 */
public final class FriendAddEvent extends FriendEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled;

    /**
     * Created by Friends.
     *
     * @param playerId   the player accepting
     * @param playerName their name
     * @param targetId   the player who sent the request
     * @param targetName their name
     */
    public FriendAddEvent(UUID playerId, String playerName, UUID targetId, String targetName) {
        super(playerId, playerName, targetId, targetName);
    }

    /**
     * Whether a plugin cancelled the action.
     *
     * @return true if cancelled
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancels (or un-cancels) the action. Friends tells nobody: the cancelling plugin should explain it to the player.
     *
     * @param cancel true to cancel
     */
    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    /**
     * Bukkit's handler list of this event.
     *
     * @return the handlers
     */
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list of this event (looked up by Bukkit when registering listeners).
     *
     * @return the handlers
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
