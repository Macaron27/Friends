package com.friends.api.bungee;

import java.util.UUID;

import net.md_5.bungee.api.plugin.Cancellable;

/**
 * A player is about to send a friend request (command or API). Cancel to block it.
 *
 * <p>Seen by a last-order listener it is about to happen, unless a simultaneous change gets there first (then the
 * action fails its re-check). Not fired when the target had already asked: that is a {@link FriendAddEvent}.
 */
public final class FriendRequestSendEvent extends FriendEvent implements Cancellable {
    private boolean cancelled;

    /**
     * Created by Friends.
     *
     * @param playerId   the player sending the request
     * @param playerName their name
     * @param targetId   the player who would receive it
     * @param targetName their name
     */
    public FriendRequestSendEvent(UUID playerId, String playerName, UUID targetId, String targetName) {
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
