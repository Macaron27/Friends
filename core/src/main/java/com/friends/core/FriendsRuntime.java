package com.friends.core;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.friends.api.FriendsProvider;
import com.friends.api.PlayerActivity;
import com.friends.api.Status;
import com.friends.core.Storage.PlayerRow;

/**
 * The core wired up (one ordered DB thread, network, commands, hooks, request expiry); every platform plugin is glue
 * around this.
 */
public final class FriendsRuntime implements AutoCloseable {
    public final Friends friends;
    public final FriendCommand command;
    public final FriendsApi api;
    private final ExecutorService db = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("friends-db").daemon().factory());
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor(); // asks plugins, never on db
    private final ExecutorService done = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("friends-events").factory());
    // Our own timer, not the platform's: Folia has no Bukkit scheduler, and every platform then behaves the same.
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("friends-timer").daemon().factory());
    private final Storage storage;
    private final Network network;
    private final Logger log;

    /** Takes ownership of {@code storage} and {@code network} (closed by {@link #close}); serves {@code FriendsAPI.get()}. */
    public FriendsRuntime(Settings settings, Storage storage, Network network, Platform platform, Hooks hooks, Logger log) {
        this(settings, storage, network, platform, hooks, log, Clock.systemUTC());
    }

    FriendsRuntime(Settings settings, Storage storage, Network network, Platform platform, Hooks hooks, Logger log, Clock clock) {
        this.storage = storage;
        this.network = network;
        this.log = log;
        settings.warnings().forEach(w -> log.warn("Friends config: {}", w));
        log.info("Friends: {} presence rule(s); private messages {} (rate limit: {})", settings.activities().size(),
                settings.privateMessages() ? "on /msg and /r" : "off", settings.rateLimit());
        this.friends = new Friends(storage, platform, network, db, async, clock, settings.requestExpiry(),
                settings.maxFriends(), settings.rateLimit(), settings.activities(), inOrder(hooks), log);
        this.command = new FriendCommand(friends, settings.privateMessages());
        this.api = new FriendsApi(friends, platform, async);
        friends.start();
        timer.scheduleWithFixedDelay(() -> {
            try {
                friends.expireRequests();
            } catch (RuntimeException e) { // an escaping exception would cancel every later run
                log.error("Friends: expiring requests failed", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
        FriendsProvider.register(api);
    }

    /** Call once players are disconnected: finishes queued writes (and the publishes behind them), then leaves. */
    @Override
    public void close() {
        FriendsProvider.unregister(api); // other plugins must not keep using (or pinning) a stopped instance
        timer.close(); // waits for a running expiry: it may still queue writes
        async.shutdown(); // running actions may still queue writes: let them finish before the database thread stops
        try {
            if (!async.awaitTermination(5, TimeUnit.SECONDS)) log.warn("Friends: gave up waiting for plugins' event listeners");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        done.shutdown(); // deliver the "done" events still queued
        try {
            if (!done.awaitTermination(5, TimeUnit.SECONDS)) log.warn("Friends: gave up delivering events");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.shutdown();
        try {
            if (!db.awaitTermination(10, TimeUnit.SECONDS)) log.warn("Friends: gave up waiting for pending database writes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        network.close();
        storage.close();
    }

    /** The core makes "done" calls under its lock: hand them to one thread, which delivers them in order. */
    private Hooks inOrder(Hooks hooks) {
        return new Hooks() {
            @Override public boolean requesting(PlayerRow from, PlayerRow to) { return hooks.requesting(from, to); }
            @Override public boolean befriending(PlayerRow player, PlayerRow sender) { return hooks.befriending(player, sender); }
            @Override public void befriended(PlayerRow player, PlayerRow sender, Instant since) { done.execute(() -> hooks.befriended(player, sender, since)); }
            @Override public boolean unfriending(PlayerRow player, Friend friend) { return hooks.unfriending(player, friend); }
            @Override public void unfriended(PlayerRow player, Friend friend) { done.execute(() -> hooks.unfriended(player, friend)); }
            @Override public boolean statusChanging(PlayerRow player, Status from, Status to) { return hooks.statusChanging(player, from, to); }
            @Override public String messaging(PlayerRow from, PlayerRow to, String message) { return hooks.messaging(from, to, message); }
            @Override public void activityChanged(PlayerRow player, PlayerActivity from, PlayerActivity to) { done.execute(() -> hooks.activityChanged(player, from, to)); }
        };
    }
}
