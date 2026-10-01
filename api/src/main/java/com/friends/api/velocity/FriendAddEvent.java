package com.friends.api.velocity;

import java.util.Objects;
import java.util.UUID;

import com.velocitypowered.api.event.ResultedEvent;

/**
 * Two players are about to become friends: {@code player} accepted {@code target}'s request (or asked back while one
 * was pending). Cancel to prevent it; the request then stays pending.
 *
 * <p>To react to the friendship itself (rewards, statistics), listen to {@link FriendAddedEvent}, which fires exactly
 * once per friendship that actually starts.
 */
public final class FriendAddEvent extends FriendEvent implements ResultedEvent<ResultedEvent.GenericResult> {
    private GenericResult result = GenericResult.allowed();

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
