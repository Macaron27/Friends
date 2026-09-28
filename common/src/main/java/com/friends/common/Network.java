package com.friends.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Connects proxies. {@link #LOCAL} is a single proxy; {@link RedisNetwork} shares state through Redis. */
public interface Network extends AutoCloseable {

    /** The shared state a proxy starts from (and re-syncs to after a reconnect). */
    record Snapshot(Map<UUID, Presence> online, List<Request> requests) {}

    interface Listener {
        /** An event from another proxy. */
        void event(Event event);

        /** Replace mirrored shared state; called on start and after every reconnect. */
        void resync(Snapshot snapshot);
    }

    String proxyId();

    /** Start delivering to {@code listener}; it receives a first {@code resync} before any event. */
    void start(Listener listener);

    /** Share an event (already applied locally) with the other proxies. */
    void publish(Event event);

    /**
     * Reserve befriending {@code a} and {@code b} network-wide for a moment. False if another proxy just did: e.g. both
     * accepted each other's crossing requests at once, where a lagging insert could outlive a later removal.
     */
    default boolean claim(UUID a, UUID b) {
        return true; // one proxy: the local lock already serialises accepts
    }

    @Override
    void close();

    Network LOCAL = new Network() {
        @Override public String proxyId() { return "local"; }
        @Override public void start(Listener listener) {}
        @Override public void publish(Event event) {}
        @Override public void close() {}
    };
}
