package com.friends.common;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.friends.common.Storage.PlayerRow;

/**
 * A change other proxies must know about. The proxy where it happens applies it, then publishes it; every other
 * proxy applies the same event, so single- and multi-proxy setups share one code path ({@code Friends.apply}).
 */
public sealed interface Event {

    /** Joined the network. */
    record Joined(UUID id, Presence presence) implements Event {}

    /** Changed server or status (no notification). */
    record Updated(UUID id, Presence presence) implements Event {}

    /** Left the network from {@code proxy} (ignored if they are already on another proxy). */
    record Left(UUID id, String proxy, Instant lastSeen) implements Event {}

    record RequestSent(Request request) implements Event {}

    record RequestDenied(UUID from, UUID to) implements Event {}

    record Befriended(PlayerRow a, PlayerRow b, Instant since) implements Event {}

    record Unfriended(UUID player, List<UUID> friends) implements Event {}

    /** A proxy (re)started or stopped heartbeating: forget everyone who was on it. */
    record ProxyDown(String proxy) implements Event {}
}
