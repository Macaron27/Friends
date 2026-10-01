package com.friends.api.bungee;

import java.util.UUID;

import net.md_5.bungee.api.plugin.Cancellable;

/**
 * A player is about to remove a friend ({@code /f remove}, {@code /f removeall} or the API). Fired once per friend:
 * cancelling one keeps that friendship, the others in a {@code removeall} still go.
 *
 * <p>To react to the removal itself, listen to {@link FriendRemovedEvent}.
 */
public final class FriendRemoveEvent extends FriendEvent implements Cancellable {
    private boolean cancelled;

    /**
     * Created by Friends.
     *
     * @param playerId   the player removing a friend
     * @param playerName their name
     * @param targetId   the friend being removed
     * @param targetName their name
     */
    public FriendRemoveEvent(UUID playerId, String playerName, UUID targetId, String targetName) {
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
}
