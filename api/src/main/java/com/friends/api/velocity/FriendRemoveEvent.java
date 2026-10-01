package com.friends.api.velocity;

import java.util.Objects;
import java.util.UUID;

import com.velocitypowered.api.event.ResultedEvent;

/**
 * A player is about to remove a friend ({@code /f remove}, {@code /f removeall} or the API). Fired once per friend:
 * cancelling one keeps that friendship, the others in a {@code removeall} still go.
 *
 * <p>To react to the removal itself, listen to {@link FriendRemovedEvent}.
 */
public final class FriendRemoveEvent extends FriendEvent implements ResultedEvent<ResultedEvent.GenericResult> {
    private GenericResult result = GenericResult.allowed();

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
