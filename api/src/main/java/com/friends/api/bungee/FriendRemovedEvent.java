package com.friends.api.bungee;

import java.util.UUID;

/**
 * A friendship just ended (for both sides). Fired exactly once per friendship, after the change is applied (the
 * database write is queued). Not cancellable: see {@link FriendRemoveEvent}.
 */
public final class FriendRemovedEvent extends FriendEvent {
    /**
     * Created by Friends.
     *
     * @param playerId   the player who removed the friend
     * @param playerName their name
     * @param targetId   the removed friend
     * @param targetName their name
     */
    public FriendRemovedEvent(UUID playerId, String playerName, UUID targetId, String targetName) {
        super(playerId, playerName, targetId, targetName);
    }
}
