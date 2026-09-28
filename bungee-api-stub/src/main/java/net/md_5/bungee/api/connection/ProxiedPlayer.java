package net.md_5.bungee.api.connection;

import java.util.UUID;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;

public interface ProxiedPlayer extends CommandSender {
    UUID getUniqueId();
    String getName();
    boolean isConnected();
    ServerInfo getServer();
    default void sendMessage(BaseComponent... components) { /* stub */ }
}
