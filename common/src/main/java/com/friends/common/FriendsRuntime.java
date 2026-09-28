package com.friends.common;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

/** The core wired up (one ordered DB thread, network, commands); every platform plugin is glue around this. */
public final class FriendsRuntime implements AutoCloseable {
    public final Friends friends;
    public final FriendCommand command;
    private final ExecutorService db = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("friends-db").daemon().factory());
    private final Storage storage;
    private final Network network;
    private final Logger log;

    /** Takes ownership of {@code storage} and {@code network} (closed by {@link #close}). */
    public FriendsRuntime(Settings settings, Storage storage, Network network, Platform platform, Logger log) {
        this.storage = storage;
        this.network = network;
        this.log = log;
        this.friends = new Friends(storage, platform, network, db, Clock.systemUTC(), settings.requestExpiry(), settings.maxFriends(), log);
        this.command = new FriendCommand(friends);
        friends.start();
    }

    /** Call once players are disconnected: finishes queued writes (and the publishes behind them), then leaves. */
    @Override
    public void close() {
        db.shutdown();
        try {
            if (!db.awaitTermination(10, TimeUnit.SECONDS)) log.warn("Friends: gave up waiting for pending database writes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        network.close();
        storage.close();
    }
}
