package com.friends.core;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import com.friends.core.Storage.PlayerRow;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.SslOptions;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Multi-proxy {@link Network} over Redis. Shared state: a hash of online players ({@code <ns>:online}), one key per
 * pending request with a TTL ({@code <ns>:request:<from>:<to>}), and a heartbeat key per proxy. Events go through
 * one pub/sub channel. A proxy that stops heartbeating is reaped by the others (its players are marked offline).
 * The SQL database stays the source of truth for friendships and must be shared by every proxy (MySQL/MariaDB).
 */
public final class RedisNetwork implements Network {
    private static final String LEAVE_IF_OWNED = """
            local v = redis.call('HGET', KEYS[1], ARGV[1])
            if v and cjson.decode(v).proxy == ARGV[2] then return redis.call('HDEL', KEYS[1], ARGV[1]) end
            return 0""";
    private static final Map<String, Class<?>> TYPES = Arrays.stream(Event.class.getPermittedSubclasses())
            .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

    private record Envelope(String origin, String type, JsonElement event) {}

    private final RedisClient redis;
    private final String proxy;
    private final String channel;
    private final String onlineKey;
    private final String requestPrefix;
    private final String claimPrefix;
    private final String proxiesKey;
    private final String heartbeatPrefix;
    private final Duration beatEvery;
    private final Duration beatTtl;
    private final Logger log;
    static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (i, _, _) -> new JsonPrimitive(i.toEpochMilli()))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (j, _, _) -> Instant.ofEpochMilli(j.getAsLong()))
            .create();
    private final ExecutorService outbound = single("friends-redis-out"); // keeps our writes + publishes in order
    private final ExecutorService inbound = single("friends-redis-in");   // keeps resyncs + received events in order
    final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("friends-redis-heartbeat").daemon().factory());
    private volatile Listener listener;
    private volatile JedisPubSub subscription;
    private volatile boolean closed;

    public RedisNetwork(String host, int port, String password, int database, boolean ssl, String namespace,
                        String proxyId, Logger log) throws IOException {
        this(host, port, password, database, ssl, namespace, proxyId, Duration.ofSeconds(10), Duration.ofSeconds(30), log);
    }

    RedisNetwork(String host, int port, String password, int database, boolean ssl, String namespace, String proxyId,
                 Duration beatEvery, Duration beatTtl, Logger log) throws IOException {
        this.proxy = proxyId;
        this.channel = namespace + ":events";
        this.onlineKey = namespace + ":online";
        this.requestPrefix = namespace + ":request:";
        this.claimPrefix = namespace + ":claim:";
        this.proxiesKey = namespace + ":proxies";
        this.heartbeatPrefix = namespace + ":proxy:";
        this.beatEvery = beatEvery;
        this.beatTtl = beatTtl;
        this.log = log;
        var config = DefaultJedisClientConfig.builder()
                .password(password == null || password.isEmpty() ? null : password)
                .database(database)
                .clientName("friends-" + proxyId);
        if (ssl) config.sslOptions(SslOptions.defaults()); // JDK trust store, full certificate + hostname checks
        this.redis = RedisClient.builder().hostAndPort(host, port).clientConfig(config.build()).build();
        try {
            redis.ping(); // fail fast on a wrong host/password, like the SQL pool does
        } catch (RuntimeException e) {
            redis.close();
            throw new IOException("could not connect to Redis at " + host + ":" + port, e);
        }
    }

    /** {@link Network#LOCAL} unless {@code redis.enabled}; multi-proxy needs MySQL shared by every proxy. */
    public static Network open(Settings settings, Logger log) throws IOException {
        Settings.Redis r = settings.redis();
        if (!r.enabled()) return Network.LOCAL;
        if (!settings.usesMysql()) throw new IOException("redis (multi-proxy) needs storage.type: mysql, shared by every proxy");
        String id = r.proxyId().isBlank() ? UUID.randomUUID().toString().substring(0, 8) : r.proxyId();
        return new RedisNetwork(r.host(), r.port(), r.password(), r.database(), r.ssl(), r.namespace(), id, log);
    }

    private static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform().name(name).daemon().factory());
    }

    @Override
    public String proxyId() {
        return proxy;
    }

    /** Blocks (up to 5s) until the first snapshot has been delivered. */
    @Override
    public void start(Listener listener) {
        this.listener = listener;
        CountDownLatch synced = new CountDownLatch(1);
        // A previous run of this proxy may have crashed: drop what it left behind, and tell the others.
        outbound.execute(() -> {
            try {
                forgetProxy(proxy);
                redis.publish(channel, encode(new Event.ProxyDown(proxy)));
            } catch (RuntimeException e) {
                log.warn("Friends: could not clear this proxy's stale Redis state ({})", e.getMessage());
            }
        });
        Thread.ofPlatform().name("friends-redis-sub").daemon().start(() -> subscribeLoop(synced));
        heartbeat.scheduleAtFixedRate(this::beat, 0, beatEvery.toMillis(), TimeUnit.MILLISECONDS);
        try {
            if (!synced.await(5, TimeUnit.SECONDS)) log.warn("Friends: no Redis snapshot yet, continuing; it will sync once connected");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void subscribeLoop(CountDownLatch synced) {
        while (!closed) {
            JedisPubSub sub = new JedisPubSub() {
                @Override
                public void onSubscribe(String ch, int count) {
                    // Subscribed first, snapshot second: nothing published in between is lost.
                    inbound.execute(() -> {
                        resync();
                        synced.countDown();
                    });
                }

                @Override
                public void onMessage(String ch, String message) {
                    inbound.execute(() -> receive(message));
                }
            };
            subscription = sub;
            try {
                redis.subscribe(sub, channel); // blocks until unsubscribed or the connection drops
            } catch (RuntimeException e) {
                if (closed) return;
                log.warn("Friends: lost the Redis subscription, retrying in 1s ({})", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void resync() {
        try {
            listener.resync(snapshot());
        } catch (RuntimeException e) {
            log.warn("Friends: could not read the shared state from Redis ({})", e.getMessage());
        }
    }

    Snapshot snapshot() {
        Map<UUID, Presence> online = new HashMap<>();
        redis.hgetAll(onlineKey).forEach((id, json) -> online.put(UUID.fromString(id), GSON.fromJson(json, Presence.class)));
        List<Request> requests = new ArrayList<>();
        ScanParams match = new ScanParams().match(requestPrefix + "*").count(500);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> page = redis.scan(cursor, match);
            cursor = page.getCursor();
            if (page.getResult().isEmpty()) continue;
            for (String json : redis.mget(page.getResult().toArray(String[]::new))) {
                if (json != null) requests.add(GSON.fromJson(json, Request.class)); // null: expired meanwhile
            }
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        return new Snapshot(online, requests);
    }

    private void receive(String message) {
        try {
            Envelope envelope = GSON.fromJson(message, Envelope.class);
            if (proxy.equals(envelope.origin())) return; // our own publish, already applied
            Class<?> type = TYPES.get(envelope.type());
            if (type == null) {
                log.warn("Friends: ignoring unknown event type {} from proxy {}", envelope.type(), envelope.origin());
                return;
            }
            listener.event((Event) GSON.fromJson(envelope.event(), type));
        } catch (JsonParseException | IllegalArgumentException e) {
            log.warn("Friends: ignoring a malformed Redis message ({})", e.getMessage());
        }
    }

    String encode(Event event) {
        return GSON.toJson(new Envelope(proxy, event.getClass().getSimpleName(), GSON.toJsonTree(event)));
    }

    @Override
    public void publish(Event event) {
        outbound.execute(() -> {
            try {
                store(event); // state first, so a proxy snapshotting after the message never misses it
                redis.publish(channel, encode(event));
            } catch (RuntimeException e) {
                log.warn("Friends: could not publish {} to Redis ({})", event.getClass().getSimpleName(), e.getMessage());
            }
        });
    }

    /** First proxy to claim a pair wins for 2s, which covers a normal SQL write queue. */
    @Override
    public boolean claim(UUID a, UUID b) {
        String pair = a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
        return redis.set(claimPrefix + pair, proxy, SetParams.setParams().nx().px(2000)) != null; // null: already taken
    }

    private void store(Event event) {
        switch (event) {
            case Event.Joined(UUID id, Presence p) -> redis.hset(onlineKey, id.toString(), GSON.toJson(p));
            case Event.Updated(UUID id, Presence p) -> redis.hset(onlineKey, id.toString(), GSON.toJson(p));
            case Event.Left(UUID id, String from, Instant _) -> leaveIfOwned(id.toString(), from);
            case Event.RequestSent(Request r) -> redis.set(requestKey(r.from().id(), r.to().id()), GSON.toJson(r),
                    SetParams.setParams().px(Math.max(1, Duration.between(Instant.now(), r.expires()).toMillis())));
            case Event.RequestDenied(UUID from, UUID to) -> redis.del(requestKey(from, to));
            case Event.Befriended(PlayerRow a, PlayerRow b, Instant _) ->
                    redis.del(requestKey(a.id(), b.id()), requestKey(b.id(), a.id()));
            case Event.Unfriended _, Event.ProxyDown _ -> {}
        }
    }

    private String requestKey(UUID from, UUID to) {
        return requestPrefix + from + ":" + to;
    }

    /** Only removes the entry if the player is still on {@code from} (they may have moved proxies). */
    private void leaveIfOwned(String id, String from) {
        redis.eval(LEAVE_IF_OWNED, List.of(onlineKey), List.of(id, from));
    }

    private void forgetProxy(String id) {
        redis.hgetAll(onlineKey).forEach((player, json) -> {
            if (id.equals(GSON.fromJson(json, Presence.class).proxy())) leaveIfOwned(player, id);
        });
    }

    /** Refreshes our heartbeat and reaps proxies whose heartbeat expired. */
    private void beat() {
        try {
            boolean wasAlive = redis.exists(heartbeatPrefix + proxy);
            redis.set(heartbeatPrefix + proxy, "1", SetParams.setParams().px(beatTtl.toMillis()));
            redis.sadd(proxiesKey, proxy);
            // We missed our own deadline (e.g. a long pause): others may have reaped us, so re-announce our players.
            if (!wasAlive && listener != null) inbound.execute(this::resync);
            for (String other : redis.smembers(proxiesKey)) {
                if (other.equals(proxy) || redis.exists(heartbeatPrefix + other)) continue;
                if (redis.srem(proxiesKey, other) == 0) continue; // another proxy reaped it first
                log.warn("Friends: proxy {} stopped heartbeating; marking its players offline", other);
                forgetProxy(other);
                redis.publish(channel, encode(new Event.ProxyDown(other)));
                inbound.execute(() -> listener.event(new Event.ProxyDown(other)));
            }
        } catch (RuntimeException e) {
            log.warn("Friends: Redis heartbeat failed ({})", e.getMessage());
        }
    }

    /** Flushes pending publishes, then tells the others we are gone. Call after players have disconnected. */
    @Override
    public void close() {
        closed = true;
        heartbeat.shutdownNow();
        outbound.shutdown();
        try {
            if (!outbound.awaitTermination(5, TimeUnit.SECONDS)) log.warn("Friends: gave up flushing Redis publishes");
            forgetProxy(proxy);
            redis.publish(channel, encode(new Event.ProxyDown(proxy)));
            redis.del(heartbeatPrefix + proxy);
            redis.srem(proxiesKey, proxy);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Friends: could not clean up this proxy's Redis state ({})", e.getMessage());
        }
        JedisPubSub sub = subscription;
        if (sub != null && sub.isSubscribed()) sub.unsubscribe();
        inbound.shutdownNow();
        redis.close();
    }
}
