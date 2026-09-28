package com.friends.common;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import net.kyori.adventure.audience.Audience;

/** What the core needs from the proxy/server it runs on. */
public interface Platform {

    Optional<Online> player(UUID id);

    /** Case-insensitive exact name lookup among online players. */
    Optional<Online> player(String name);

    Collection<String> onlineNames();

    /**
     * A connected player. {@code audience} doubles as the session identity; {@code prefix} is a legacy
     * ('&') rank prefix or null; {@code server} is null while not connected to a backend.
     */
    record Online(UUID id, String name, String prefix, String server, Audience audience) {}
}
