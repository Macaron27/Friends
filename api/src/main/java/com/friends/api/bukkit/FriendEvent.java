package com.friends.api.bukkit;

import java.util.UUID;

import org.bukkit.event.Event;

/**
 * Base of Friends' Bukkit events about two players: {@code player} acts on {@code target}. Fired by standalone
 * Paper servers (on a network, Friends runs on the proxy and fires that platform's events).
 *
 * <p>Always asynchronous: listeners run on Friends' threads, never on the server thread, so schedule onto it
 * ({@code Bukkit.getScheduler().runTask}) before using the world or most of the Bukkit API. Events hold UUIDs and
 * names, never {@code Player} objects; look players up with {@code Bukkit.getPlayer(uuid)} if needed.
 */
public abstract class FriendEvent extends Event {
    private final UUID playerId;
    private final String playerName;
    private final UUID targetId;
    private final String targetName;

    /**
     * Created by Friends.
     *
     * @param playerId   the acting player
     * @param playerName their name
     * @param targetId   the other player
     * @param targetName their name
     */
    protected FriendEvent(UUID playerId, String playerName, UUID targetId, String targetName) {
        super(true);
        this.playerId = playerId;
        this.playerName = playerName;
        this.targetId = targetId;
        this.targetName = targetName;
    }

    /**
     * The acting player.
     *
     * @return their UUID, never null
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * The acting player's name.
     *
     * @return never null
     */
    public String getPlayerName() {
        return playerName;
    }

    /**
     * The other player.
     *
     * @return their UUID, never null
     */
    public UUID getTargetId() {
        return targetId;
    }

    /**
     * The other player's name.
     *
     * @return never null
     */
    public String getTargetName() {
        return targetName;
    }
}
