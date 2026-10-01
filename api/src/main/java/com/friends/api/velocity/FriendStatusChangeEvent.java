package com.friends.api.velocity;

import java.util.Objects;
import java.util.UUID;

import com.friends.api.Status;
import com.velocitypowered.api.event.ResultedEvent;

/**
 * A player is about to change the status their friends see ({@code /status} or the API). Cancel to keep the old one.
 * Fired on Friends' threads, like every Friends event (see {@link FriendEvent}).
 */
public final class FriendStatusChangeEvent implements ResultedEvent<ResultedEvent.GenericResult> {
    private final UUID playerId;
    private final String playerName;
    private final Status oldStatus;
    private final Status newStatus;
    private GenericResult result = GenericResult.allowed();

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
     * Whether the action may go ahead.
     *
     * @return {@code allowed()} unless a plugin denied it
     */
    @Override
    public GenericResult getResult() {
        return result;
    }

    /**
     * {@code GenericResult.denied()} cancels the action. Friends tells nobody: the cancelling plugin should explain it
     * to the player.
     *
     * @param result {@code allowed()} or {@code denied()}
     */
    @Override
    public void setResult(GenericResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }
}
