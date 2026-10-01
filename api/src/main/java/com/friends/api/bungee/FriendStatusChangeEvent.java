package com.friends.api.bungee;

import java.util.UUID;

import com.friends.api.Status;

import net.md_5.bungee.api.plugin.Cancellable;
import net.md_5.bungee.api.plugin.Event;

/**
 * A player is about to change the status their friends see ({@code /status} or the API). Cancel to keep the old one.
 * Called on Friends' threads, like every Friends event (see {@link FriendEvent}).
 */
public final class FriendStatusChangeEvent extends Event implements Cancellable {
    private final UUID playerId;
    private final String playerName;
    private final Status oldStatus;
    private final Status newStatus;
    private boolean cancelled;

    /**
     * Created by Friends.
     *
     * @param playerId   the player
     * @param playerName their name
     * @param oldStatus  their current status
     * @param newStatus  the status they are switching to
     */
    public FriendStatusChangeEvent(UUID playerId, String playerName, Status oldStatus, Status newStatus) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }

    /**
     * The player changing status.
     *
     * @return their UUID, never null
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * The player's name.
     *
     * @return never null
     */
    public String getPlayerName() {
        return playerName;
    }

    /**
     * The status before the change.
     *
     * @return never null
     */
    public Status getOldStatus() {
        return oldStatus;
    }

    /**
     * The status after the change.
     *
     * @return never null
     */
    public Status getNewStatus() {
        return newStatus;
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
