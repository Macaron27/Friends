package com.friends.api;

import java.util.Objects;

/**
 * What a player is doing, derived from the backend server they are on ({@code presence.rules} in Friends' config: a
 * server-name pattern maps to a game and a mode). Generic on purpose: Friends knows no game, only these two labels.
 * Immutable, like every Friends model: it is shared by every reader and synchronised across proxies.
 */
public final class PlayerActivity {
    private final String game;
    private final String mode;

    /**
     * Created by Friends from its config; plugins have no reason to build one except in tests.
     *
     * @param game the game, e.g. {@code "BedWars"}; never null
     * @param mode the mode, e.g. {@code "Solo"}, or null if the rule has none
     */
    public PlayerActivity(String game, String mode) {
        this.game = Objects.requireNonNull(game, "game");
        this.mode = mode;
    }

    /**
     * The game.
     *
     * @return e.g. {@code "BedWars"}, never null
     */
    public String getGame() {
        return game;
    }

    /**
     * The mode within the game.
     *
     * @return e.g. {@code "Solo"}, or null if none
     */
    public String getMode() {
        return mode;
    }

    /**
     * How Friends shows it in chat.
     *
     * @return e.g. {@code "Playing BedWars Solo"}, or {@code "Playing BedWars"} without a mode
     */
    public String describe() {
        return mode == null ? "Playing " + game : "Playing " + game + " " + mode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerActivity)) return false;
        PlayerActivity a = (PlayerActivity) o;
        return game.equals(a.game) && Objects.equals(mode, a.mode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(game, mode);
    }

    @Override
    public String toString() {
        return "PlayerActivity{game=" + game + ", mode=" + mode + "}";
    }
}
