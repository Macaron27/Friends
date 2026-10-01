package com.friends.api.bungee;

import java.util.UUID;

import net.md_5.bungee.api.plugin.Event;

/**
 * Base of Friends' BungeeCord events about two players: {@code player} acts on {@code target}.
 *
 * <p>Called on Friends' threads, never on BungeeCord's network threads. On a multi-proxy network an event fires only
 * on the proxy where the change happens, so once network-wide. Events hold UUIDs and names, never
 * {@code ProxiedPlayer} objects; look players up with {@code ProxyServer.getInstance().getPlayer(uuid)} if needed.
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
