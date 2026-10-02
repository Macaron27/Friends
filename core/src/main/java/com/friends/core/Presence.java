package com.friends.core;

import com.friends.api.Status;

/** Where an online player is on the network. {@code server} is null until they reach a backend. */
public record Presence(String name, String prefix, String proxy, String server, Status status) {

    Presence withServer(String server) {
        return new Presence(name, prefix, proxy, server, status);
    }

    Presence withStatus(Status status) {
        return new Presence(name, prefix, proxy, server, status);
    }
}
