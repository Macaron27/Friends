package com.friends.api.bukkit;

import java.util.UUID;

import org.bukkit.event.HandlerList;

/**
 * A friendship just ended (for both sides). Fired exactly once per friendship, after the change is applied (the
 * database write is queued). Not cancellable: see {@link FriendRemoveEvent}.
 */
public final class FriendRemovedEvent extends FriendEvent {
    private static final HandlerList HANDLERS = new HandlerList();

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
