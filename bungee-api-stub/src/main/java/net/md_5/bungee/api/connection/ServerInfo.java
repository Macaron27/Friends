package net.md_5.bungee.api.connection;

import java.util.Collection;

public interface ServerInfo {
    String getName();
    Collection<ProxiedPlayer> getPlayers();
    void sendData(String channel, byte[] data);
}
