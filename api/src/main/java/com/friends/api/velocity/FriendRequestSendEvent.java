package com.friends.api.velocity;

import java.util.Objects;
import java.util.UUID;

import com.velocitypowered.api.event.ResultedEvent;

/**
 * A player is about to send a friend request (command or API). Cancel to block it.
 *
 * <p>Seen by a last-order listener it is about to happen, unless a simultaneous change gets there first (then the
 * action fails its re-check). Not fired when the target had already asked: that is a {@link FriendAddEvent}.
 */
public final class FriendRequestSendEvent extends FriendEvent implements ResultedEvent<ResultedEvent.GenericResult> {
    private GenericResult result = GenericResult.allowed();

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
