package com.friends.core;

import com.friends.api.PlayerActivity;
import com.friends.api.Status;

/**
 * Where an online player is on the network. {@code server} is null until they reach a backend; {@code activity} is
 * what {@code presence.rules} make of that server, or null. Shared through Redis as JSON: proxies running an older
 * version ignore {@code activity}, and entries they wrote read back with a null one.
 */
public record Presence(String name, String prefix, String proxy, String server, Status status, PlayerActivity activity) {

    Presence withServer(String server, PlayerActivity activity) {
        return new Presence(name, prefix, proxy, server, status, activity);
    }

    Presence withStatus(Status status) {
        return new Presence(name, prefix, proxy, server, status, activity);
    }
}
