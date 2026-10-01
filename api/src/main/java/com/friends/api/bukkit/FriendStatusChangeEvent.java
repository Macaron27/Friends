package com.friends.api.bukkit;

import java.util.UUID;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.friends.api.Status;

/**
 * A player is about to change the status their friends see ({@code /status} or the API). Cancel to keep the old one.
 * Asynchronous, like every Friends event (see {@link FriendEvent}).
 */
public final class FriendStatusChangeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
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
        super(true);
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
     * Whether a plugin cancelled the change.
     *
     * @return true if cancelled
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancels (or un-cancels) the change. Friends tells nobody: the cancelling plugin should explain it to the player.
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
