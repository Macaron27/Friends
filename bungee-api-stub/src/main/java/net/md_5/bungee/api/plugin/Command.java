package net.md_5.bungee.api.plugin;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;

public abstract class Command {
    private final String name;
    private final String permission;
    private final String[] aliases;

    public Command(String name, String permission, String... aliases) {
        this.name = name;
        this.permission = permission;
        this.aliases = aliases;
    }

    public String getName() { return name; }

    public abstract void execute(CommandSender sender, String[] args);

    // Convenience accessors used by plugins in our project
    public ProxyServer getProxy() { return ProxyServer.getInstance(); }
}
