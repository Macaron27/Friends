package com.friends.core;

import java.util.Optional;
import java.util.UUID;

import net.kyori.adventure.audience.Audience;

/** What the core needs from the proxy/server it runs on. */
public interface Platform {

    /** A player connected to this proxy. */
    Optional<Online> player(UUID id);

    /**
     * A connected player. {@code audience} doubles as the session identity; {@code prefix} is a legacy
     * ('&') rank prefix or null; {@code server} is null while not connected to a backend.
     */
    record Online(UUID id, String name, String prefix, String server, Audience audience) {}
}
