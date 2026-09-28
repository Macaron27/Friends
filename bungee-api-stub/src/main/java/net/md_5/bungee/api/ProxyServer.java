package net.md_5.bungee.api;

import java.util.UUID;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.PluginManager;

public class ProxyServer {
    private static final ProxyServer INSTANCE = new ProxyServer();
    public static ProxyServer getInstance() { return INSTANCE; }
    public PluginManager getPluginManager() { return new PluginManager(); }
    public ProxiedPlayer getPlayer(UUID id) { return null; }
    public ProxiedPlayer getPlayer(String name) { return null; }
    public java.util.Collection<ProxiedPlayer> getPlayers() { return java.util.Collections.emptyList(); }
}
