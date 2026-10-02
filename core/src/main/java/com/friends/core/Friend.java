package com.friends.core;

import java.time.Instant;
import java.util.UUID;

/**
 * One side of a friendship as seen by its owner. {@code best} and {@code nickname} are private to the owner;
 * {@code name}, {@code prefix} and {@code lastSeen} describe the friend (cached so lists need no queries).
 */
public record Friend(UUID id, String name, String prefix, Instant since, boolean best, String nickname, Instant lastSeen) {

    Friend withBest(boolean best) {
        return new Friend(id, name, prefix, since, best, nickname, lastSeen);
    }

    Friend withNickname(String nickname) {
        return new Friend(id, name, prefix, since, best, nickname, lastSeen);
    }

    Friend seen(String name, String prefix, Instant lastSeen) {
        return new Friend(id, name, prefix, since, best, nickname, lastSeen);
    }
}
