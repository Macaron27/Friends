package com.friends.api.bungee;

import java.util.Optional;
import java.util.UUID;

import com.friends.api.FriendsAPI;
import com.friends.api.PlayerActivity;

import net.md_5.bungee.api.plugin.Event;

/**
 * What a player is doing changed: they joined, switched backend server or left, and the {@code presence.rules} of
 * Friends' config give them another {@link PlayerActivity} (or none) than before. Switching between two servers of
 * the same game and mode fires nothing. Not cancellable: fired after the change, on Friends' threads, on the proxy
 * the player is on only (so once network-wide; players of a proxy that stops responding are forgotten without it).
 *
 * <p>This reports the activity as is, also for players appearing offline, whose activity
 * {@link FriendsAPI#getActivity} hides from their friends: check {@link FriendsAPI#getStatus} before showing it.
 */
public final class PlayerActivityChangeEvent extends Event {
    private final UUID playerId;
    private final String playerName;
    private final PlayerActivity oldActivity;
    private final PlayerActivity newActivity;

    /**
     * Created by Friends.
     *
     * @param playerId    the player
     * @param playerName  their name
     * @param oldActivity what they were doing, or null for nothing (just joined, or no rule matched)
     * @param newActivity what they are doing now, or null for nothing (left, or no rule matches)
     */
    public PlayerActivityChangeEvent(UUID playerId, String playerName, PlayerActivity oldActivity, PlayerActivity newActivity) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.oldActivity = oldActivity;
        this.newActivity = newActivity;
    }

    /**
     * The player.
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
     * The activity before the change.
     *
     * @return e.g. BedWars / Solo, or empty if they were doing nothing a rule knows (or had just joined)
     */
    public Optional<PlayerActivity> getOldActivity() {
        return Optional.ofNullable(oldActivity);
    }

    /**
     * The activity after the change.
     *
     * @return e.g. SkyWars, or empty if they are doing nothing a rule knows (or left)
     */
    public Optional<PlayerActivity> getNewActivity() {
        return Optional.ofNullable(newActivity);
    }

    @Override
    public String toString() {
        return "PlayerActivityChangeEvent{player=" + playerName + ", old=" + oldActivity + ", new=" + newActivity + "}";
    }
}
