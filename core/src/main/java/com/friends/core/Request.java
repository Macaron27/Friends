package com.friends.core;

import java.time.Instant;
import java.util.UUID;

import com.friends.core.Storage.PlayerRow;

/** A pending friend request. */
public record Request(PlayerRow from, PlayerRow to, Instant expires) {

    record Key(UUID from, UUID to) {}

    Key key() {
        return new Key(from.id(), to.id());
    }
}
