package net.md_5.bungee.api.plugin;

import net.md_5.bungee.api.ProxyServer;

public abstract class Plugin {
    public ProxyServer getProxy() { return ProxyServer.getInstance(); }
    public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getLogger(getClass().getName()); }
    public java.io.File getDataFolder() { return new java.io.File("plugins", getClass().getSimpleName()); }
    public java.io.InputStream getResourceAsStream(String name) { return getClass().getResourceAsStream("/" + name); }
    public void onEnable() {}
    public void onDisable() {}
}
