package net.md_5.bungee.api.event;

import net.md_5.bungee.api.connection.ProxiedPlayer;

public class PlayerDisconnectEvent {
    private final ProxiedPlayer player;

    public PlayerDisconnectEvent(ProxiedPlayer player) {
        this.player = player;
    }

    public ProxiedPlayer getPlayer() { return player; }
}
