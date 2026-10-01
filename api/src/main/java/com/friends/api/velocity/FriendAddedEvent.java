package com.friends.api.velocity;

import java.time.Instant;
import java.util.UUID;

/**
 * Two players just became friends. Fired exactly once per new friendship, after the change is applied (the database
 * write is queued). Not cancellable: see {@link FriendAddEvent}.
 */
public final class FriendAddedEvent extends FriendEvent {
    private final Instant since;

    /**
     * Created by Friends.
     *
     * @param playerId   the player who accepted
     * @param playerName their name
     * @param targetId   the player who sent the request
     * @param targetName their name
     * @param since      when they became friends
     */
    public FriendAddedEvent(UUID playerId, String playerName, UUID targetId, String targetName, Instant since) {
        super(playerId, playerName, targetId, targetName);
        this.since = since;
    }

    /**
     * When they became friends.
     *
     * @return never null
     */
    public Instant getSince() {
        return since;
    }
}
