package com.friends.core;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.friends.api.PlayerActivity;
import com.friends.api.Result;
import com.friends.api.Status;
import com.friends.core.Platform.Online;
import com.friends.core.Storage.PlayerRow;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/**
 * Friends core: rules, caches and messaging. Reads come from memory; SQL writes are queued on {@code db}, which must
 * run tasks one at a time in order. Anything other proxies must see is an {@link Event}: applied here via
 * {@link #apply}, then published on the {@link Network}, whose other proxies apply the same event. Commands also back
 * the API: each returns a {@link Result} besides replying to the player, and asks {@link Hooks} (plugins) first.
 */
public final class Friends implements Network.Listener {
    public static final int PAGE_SIZE = 10;
    private static final Pattern NAME = Pattern.compile("[\\w.*]{1,16}");
    private static final Pattern NICKNAME = Pattern.compile("[\\p{L}\\p{N}_ ]{1,16}");
    // Section signs (legacy colour codes) and control characters: a modified client could send them, and chat
    // clients render them inside plain text. The protocol already caps chat at 256 characters.
    private static final Pattern UNPRINTABLE = Pattern.compile("[\u00a7\\p{Cntrl}]");

    /** Cached state of a player on this proxy. {@code audience} is also the session identity. */
    static final class Profile {
        final Audience audience;
        final Map<UUID, Friend> friends = new ConcurrentHashMap<>();
        volatile boolean notifications;
        volatile Target replyTo; // who /r answers: the last private message sent or received (id and name)

        Profile(Audience audience, Storage.Loaded data) {
            this.audience = audience;
            this.notifications = data.notifications();
            data.friends().forEach(f -> friends.put(f.id(), f));
        }
    }

    private interface SqlCall<T> {
        T call() throws SQLException;
    }

    private interface SqlRun {
        void run() throws SQLException;
    }

    private final Storage storage;
    private final Platform platform;
    private final Network network;
    private final String proxy;
    private final Executor db;
    private final Clock clock;
    private final Duration requestExpiry;
    private final int maxFriends;
    private final Activities activities;
    private final Hooks hooks;
    private final Executor async; // asks plugins (hooks) off the database thread
    private final Logger log;
    // Every state change (decide, update caches, queue the SQL write, publish) happens under this lock, so SQL writes
    // are queued in the same order as the cache changes. Reads stay lock-free (concurrent maps).
    // ponytail: one global lock; friend commands are rare and short. Per-player locks if contention ever shows.
    private final Object lock = new Object();
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();        // players on this proxy
    private final Map<UUID, Presence> presence = new ConcurrentHashMap<>();       // players on any proxy
    // Players whose profile is being read; a friendship change for one of them makes the read stale (re-read it).
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final Set<UUID> stale = ConcurrentHashMap.newKeySet();
    // Pending requests, network-wide. ponytail: memory/Redis only; they expire within minutes anyway.
    private final Map<Request.Key, Request> requests = new ConcurrentHashMap<>();
    // Friends of players not loaded here, read for the API. An entry is dropped as soon as a friendship or the presence
    // of its player changes, so only the friends' names and last-seen times can be up to OFFLINE_TTL old.
    // ponytail: evicts arbitrary entries once full; Caffeine if hit rates ever matter.
    private static final int OFFLINE_MAX = 1024;
    private static final Duration OFFLINE_TTL = Duration.ofSeconds(30);
    private final Map<UUID, Cached> offline = new ConcurrentHashMap<>();

    private record Cached(CompletableFuture<List<Friend>> friends, Instant expires) {}

    /** {@code db} runs SQL in order, one task at a time; {@code async} runs whatever asks plugins (any threads). */
    public Friends(Storage storage, Platform platform, Network network, Executor db, Executor async, Clock clock,
                   Duration requestExpiry, int maxFriends, Activities activities, Hooks hooks, Logger log) {
        this.storage = storage;
        this.platform = platform;
        this.network = network;
        this.proxy = network.proxyId();
        this.db = db;
        this.clock = clock;
        this.requestExpiry = requestExpiry;
        this.maxFriends = maxFriends;
        this.activities = activities;
        this.hooks = hooks;
        this.async = async;
        this.log = log;
    }

    /** Joins the network (receives the shared state). Call once, before players connect. */
    public void start() {
        network.start(this);
    }

    // --- events ---

    /** Queue the event's SQL writes before calling this. */
    private void emit(Event event) {
        synchronized (lock) {
            apply(event);
            // Publish through the DB queue: other proxies see an event only once the SQL writes queued before it are
            // committed (so they can't act on a friendship that isn't stored yet), and in the order we applied them.
            if (network != Network.LOCAL) db.execute(() -> network.publish(event));
        }
    }

    @Override
    public void event(Event event) {
        apply(event);
    }

    @Override
    public void resync(Network.Snapshot snapshot) {
        synchronized (lock) {
            resyncLocked(snapshot);
        }
    }

    private void resyncLocked(Network.Snapshot snapshot) {
        // Our own players win: their Redis writes may have been lost while it was unreachable, so re-announce them.
        Map<UUID, Presence> mine = new HashMap<>();
        profiles.keySet().forEach(id -> {
            Presence p = presence.get(id);
            if (p != null && p.proxy().equals(proxy)) mine.put(id, p);
        });
        presence.clear();
        snapshot.online().forEach((id, p) -> {
            if (!p.proxy().equals(proxy)) presence.put(id, p); // entries claiming this proxy are stale unless in `mine`
        });
        presence.putAll(mine);
        requests.clear();
        snapshot.requests().forEach(r -> requests.put(r.key(), r));
        mine.forEach((id, p) -> network.publish(new Event.Updated(id, p)));
    }

    /** The only place shared state changes, for events from this proxy and from others alike. */
    void apply(Event event) {
        synchronized (lock) {
            applyLocked(event);
        }
    }

    private void applyLocked(Event event) {
        switch (event) {
            case Event.Joined(UUID id, Presence p) -> {
                offline.remove(id);
                presence.put(id, p);
                Instant now = clock.instant();
                forFriendsOf(id, f -> f.seen(p.name(), p.prefix(), now), p.status() != Status.OFFLINE, true);
            }
            case Event.Updated(UUID id, Presence p) ->
                    presence.compute(id, (_, old) -> old == null || old.proxy().equals(p.proxy()) ? p : old);
            case Event.Left(UUID id, String from, Instant lastSeen) -> {
                offline.remove(id);
                Presence p = presence.get(id);
                if (p == null || !p.proxy().equals(from) || !presence.remove(id, p)) return; // moved proxies meanwhile
                forFriendsOf(id, f -> f.seen(f.name(), f.prefix(), lastSeen), p.status() != Status.OFFLINE, false);
            }
            case Event.RequestSent(Request r) -> {
                requests.put(r.key(), r);
                message(r.to().id(), Messages.received(Messages.name(r.from()), r.from().name()));
            }
            case Event.RequestDenied(UUID from, UUID to) -> requests.remove(new Request.Key(from, to));
            case Event.Befriended(PlayerRow a, PlayerRow b, Instant since) -> {
                requests.remove(new Request.Key(a.id(), b.id()));
                requests.remove(new Request.Key(b.id(), a.id()));
                offline.remove(a.id());
                offline.remove(b.id());
                link(a, b, since);
                link(b, a, since);
            }
            case Event.Unfriended(UUID player, List<UUID> others) -> {
                Profile me = profiles.get(player);
                if (me == null) markIfLoading(player);
                offline.remove(player);
                for (UUID other : others) {
                    offline.remove(other);
                    if (me != null) me.friends.remove(other);
                    Profile theirs = profiles.get(other);
                    if (theirs != null) theirs.friends.remove(player);
                    else markIfLoading(other);
                }
            }
            case Event.PrivateMessage(PlayerRow from, UUID to, String text) -> {
                // Delivered whatever this proxy thinks of the friendship: the sender's proxy checked it, and a
                // Befriended published through its SQL queue may still be on its way here.
                Profile p = profiles.get(to);
                if (p == null) return; // on another proxy (or just left)
                p.replyTo = new Target(from.id(), from.name());
                Friend view = p.friends.get(from.id());
                p.audience.sendMessage(Messages.messageFrom(view != null ? Messages.friendName(view) : Messages.name(from), from.name(), text));
            }
            case Event.ProxyDown(String down) -> {
                Instant now = clock.instant();
                presence.forEach((id, p) -> {
                    if (p.proxy().equals(down)) applyLocked(new Event.Left(id, down, now));
                });
            }
        }
    }

    /** Refreshes every local player's view of {@code id}, optionally announcing the join/leave. */
    // ponytail: scans this proxy's players per network join/leave; add a reverse index if that ever shows up.
    private void forFriendsOf(UUID id, UnaryOperator<Friend> update, boolean announce, boolean joined) {
        for (Profile other : profiles.values()) {
            Friend view = other.friends.computeIfPresent(id, (_, f) -> update.apply(f));
            if (view == null || !announce || !other.notifications) continue;
            Component name = Messages.friendName(view);
            other.audience.sendMessage(joined ? Messages.joined(name) : Messages.left(name));
        }
    }

    private void markIfLoading(UUID id) {
        if (loading.contains(id)) stale.add(id);
    }

    private void link(PlayerRow owner, PlayerRow friend, Instant since) {
        Profile p = profiles.get(owner.id());
        if (p == null) markIfLoading(owner.id());
        // putIfAbsent: crossing accepts can befriend twice; keep the first entry (and its best/nickname flags).
        if (p == null || p.friends.putIfAbsent(friend.id(),
                new Friend(friend.id(), friend.name(), friend.prefix(), since, false, null, friend.lastSeen())) != null) return;
        p.audience.sendMessage(Messages.nowFriends(Messages.name(friend)));
    }

    // --- lifecycle ---

    /** Loads the player's data, then announces them. Never completes exceptionally. */
    public CompletableFuture<Void> connect(Online p) {
        Instant now = clock.instant();
        synchronized (lock) {
            loading.add(p.id());
            stale.remove(p.id());
        }
        return db(() -> {
            storage.savePlayer(p.id(), p.name(), p.prefix(), now);
            return storage.load(p.id());
        }).thenCompose(data -> install(p, data))
                .exceptionally(_ -> { // logged by db(); the player just has no profile until they rejoin
                    loading.remove(p.id());
                    return null;
                });
    }

    private CompletableFuture<Void> install(Online p, Storage.Loaded data) {
        synchronized (lock) {
            // A friendship changed while we were reading: its write is queued behind us, so read again after it.
            if (stale.remove(p.id())) return db(() -> storage.load(p.id())).thenCompose(fresh -> install(p, fresh));
            loading.remove(p.id());
            // Left (or was replaced by a newer session) while loading: don't resurrect a stale profile.
            if (platform.player(p.id()).filter(o -> o.audience() == p.audience()).isEmpty()) return completedFuture(null);
            profiles.put(p.id(), new Profile(p.audience(), data));
            emit(new Event.Joined(p.id(), new Presence(p.name(), p.prefix(), proxy, p.server(), data.status(), activities.match(p.server()))));
            return completedFuture(null);
        }
    }

    /** Call once the player can receive chat (first backend server): tells them about pending requests. */
    public void greet(Online p) {
        long pending = incoming(p.id()).count();
        if (pending > 0) p.audience().sendMessage(Messages.pending(pending));
    }

    /**
     * Call after a server switch: friends network-wide see the new server and the activity {@code presence.rules}
     * derive from it (an {@link Event.Updated}, i.e. the Redis presence entry plus one publish). Nothing is published
     * if neither changed.
     */
    public void moved(Online p) {
        PlayerActivity activity = activities.match(p.server()); // outside the lock: regexes
        synchronized (lock) {
            Presence old = presence.get(p.id());
            if (!profiles.containsKey(p.id()) || old == null) return;
            Presence now = old.withServer(p.server(), activity);
            if (!now.equals(old)) emit(new Event.Updated(p.id(), now));
        }
    }

    public void disconnect(Online p) {
        synchronized (lock) {
            Profile profile = profiles.get(p.id());
            // Only the session that loaded the profile may drop it (kick-existing-players can reorder events).
            if (profile == null || profile.audience != p.audience() || !profiles.remove(p.id(), profile)) return;
            Instant now = clock.instant();
            write(() -> storage.touch(p.id(), now));
            emit(new Event.Left(p.id(), proxy, now));
        }
    }

    /** Called periodically; validity is also re-checked lazily, so timing isn't critical. */
    public void expireRequests() {
        Instant now = clock.instant();
        synchronized (lock) {
            for (Request r : requests.values()) {
                if (now.isBefore(r.expires()) || !requests.remove(r.key(), r)) continue;
                // Every proxy sweeps its own copy and only tells its own players, so each player hears it once.
                message(r.from().id(), Messages.expiredOutgoing(Messages.name(r.to())));
                message(r.to().id(), Messages.expiredIncoming(Messages.name(r.from())));
            }
        }
    }

    // --- commands (and API actions): each replies to the player and returns what happened ---

    /** Who a command is about: the name a player typed, or a UUID (API). */
    record Target(UUID id, String name) {
        static Target named(String name) {
            return new Target(null, name);
        }

        static Target of(UUID id) {
            return new Target(id, null);
        }

        boolean is(UUID otherId, String otherName) {
            return id != null ? id.equals(otherId) : name.equalsIgnoreCase(otherName);
        }

        /** How messages name it. */
        String label() {
            return name != null ? name : id.toString();
        }
    }

    /** {@code privateMessages}: also list {@code /msg} and {@code /r}. */
    public CompletableFuture<Result> help(Online s, boolean privateMessages) {
        return reply(s, Messages.help(privateMessages), Result.SUCCESS);
    }

    public CompletableFuture<Result> add(Online s, Target target) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        if (target.is(s.id(), s.name())) return reply(s, Messages.addSelf(), Result.SELF);
        return resolve(target).thenCompose(found -> found.isEmpty()
                ? reply(s, Messages.notFound(target.label()), Result.PLAYER_NOT_FOUND)
                : friendCount(found.get().id()).thenComposeAsync(count -> add(s, me, found.get(), count, false), async));
    }

    /** All checks and the send in one locked step, asking plugins in between ({@code vetted}: they agreed). */
    private CompletableFuture<Result> add(Online s, Profile me, PlayerRow target, int count, boolean vetted) {
        Request theirs;
        synchronized (lock) {
            Component targetName = Messages.name(target);
            if (me.friends.containsKey(target.id())) return reply(s, Messages.alreadyFriends(targetName), Result.ALREADY_FRIENDS);
            theirs = valid(requests.get(new Request.Key(target.id(), s.id())));
            if (theirs == null) {
                if (valid(requests.get(new Request.Key(s.id(), target.id()))) != null) {
                    return reply(s, Messages.alreadySent(targetName), Result.ALREADY_REQUESTED);
                }
                if (me.friends.size() >= maxFriends) return reply(s, Messages.limitSelf(maxFriends), Result.LIMIT_REACHED);
                if (count >= maxFriends) return reply(s, Messages.limitOther(targetName), Result.TARGET_LIMIT_REACHED);
                if (vetted) {
                    emit(new Event.RequestSent(new Request(row(s), target, clock.instant().plus(requestExpiry))));
                    return reply(s, Messages.sent(targetName, requestExpiry.toMinutes()), Result.SUCCESS);
                }
            }
        }
        if (theirs != null) { // both asked: just make them friends (outside the lock: it may claim)
            return accept(s, me, theirs).thenApply(r -> r == Result.SUCCESS ? Result.BECAME_FRIENDS : r);
        }
        // Plugins are asked outside the lock (their listeners may be slow), so everything is checked again after.
        return hooks.requesting(row(s), target) ? add(s, me, target, count, true) : completedFuture(Result.CANCELLED);
    }

    public CompletableFuture<Result> accept(Online s, Target target) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        Request r = incoming(s.id()).filter(x -> target.is(x.from().id(), x.from().name())).findFirst().orElse(null);
        return r == null ? reply(s, Messages.noRequest(target.label()), Result.NO_REQUEST) : accept(s, me, r);
    }

    private CompletableFuture<Result> accept(Online s, Profile me, Request r) {
        PlayerRow other = r.from();
        if (me.friends.size() >= maxFriends) return reply(s, Messages.limitSelf(maxFriends), Result.LIMIT_REACHED);
        return friendCount(other.id()).thenComposeAsync(count -> {
            if (count >= maxFriends) return reply(s, Messages.limitOther(Messages.name(other)), Result.TARGET_LIMIT_REACHED);
            PlayerRow mine = row(s);
            if (!hooks.befriending(mine, other)) return completedFuture(Result.CANCELLED);
            if (!network.claim(s.id(), other.id())) return reply(s, Messages.noRequest(other.name()), Result.NO_REQUEST); // lost a crossing accept
            synchronized (lock) {
                if (!requests.remove(r.key(), r)) return reply(s, Messages.noRequest(other.name()), Result.NO_REQUEST); // expired/raced
                if (me.friends.containsKey(other.id())) return completedFuture(Result.ALREADY_FRIENDS); // crossing accepts: already done
                Instant now = clock.instant();
                write(() -> storage.addFriendship(s.id(), other.id(), now));
                emit(new Event.Befriended(other, mine, now)); // tells both sides, wherever they are
                hooks.befriended(mine, other, now);
                return completedFuture(Result.SUCCESS);
            }
        }, async);
    }

    public CompletableFuture<Result> deny(Online s, Target target) {
        synchronized (lock) {
            Request r = incoming(s.id()).filter(x -> target.is(x.from().id(), x.from().name())).findFirst().orElse(null);
            if (r == null || !requests.remove(r.key(), r)) return reply(s, Messages.noRequest(target.label()), Result.NO_REQUEST);
            emit(new Event.RequestDenied(r.from().id(), s.id()));
            return reply(s, Messages.declined(Messages.name(r.from())), Result.SUCCESS);
        }
    }

    public CompletableFuture<Result> remove(Online s, Target target) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        Friend f = find(me, target);
        if (f == null) return reply(s, Messages.notFriend(target.label()), Result.NOT_FRIENDS);
        PlayerRow mine = row(s);
        if (!hooks.unfriending(mine, f)) return completedFuture(Result.CANCELLED);
        synchronized (lock) {
            if (!me.friends.containsKey(f.id())) return reply(s, Messages.notFriend(target.label()), Result.NOT_FRIENDS); // meanwhile
            write(() -> storage.removeFriendships(s.id(), List.of(f.id())));
            emit(new Event.Unfriended(s.id(), List.of(f.id())));
            hooks.unfriended(mine, f);
            return reply(s, Messages.removed(Messages.name(f.prefix(), f.name())), Result.SUCCESS);
        }
    }

    public CompletableFuture<Result> removeAll(Online s, boolean confirmed) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        List<Friend> victims = me.friends.values().stream().filter(f -> !f.best()).toList();
        if (victims.isEmpty()) return reply(s, Messages.nothingToRemove(), Result.NOT_FRIENDS);
        if (!confirmed) return reply(s, Messages.removeAllConfirm(victims.size()), Result.SUCCESS);
        PlayerRow mine = row(s);
        Set<UUID> allowed = victims.stream().filter(f -> hooks.unfriending(mine, f)).map(Friend::id).collect(Collectors.toSet());
        if (allowed.isEmpty()) return completedFuture(Result.CANCELLED);
        synchronized (lock) {
            List<Friend> removed = me.friends.values().stream().filter(f -> !f.best() && allowed.contains(f.id())).toList();
            if (removed.isEmpty()) return reply(s, Messages.nothingToRemove(), Result.NOT_FRIENDS);
            List<UUID> ids = removed.stream().map(Friend::id).toList();
            write(() -> storage.removeFriendships(s.id(), ids));
            emit(new Event.Unfriended(s.id(), ids));
            removed.forEach(f -> hooks.unfriended(mine, f));
            return reply(s, Messages.removedAll(ids.size()), Result.SUCCESS);
        }
    }

    /** {@code best} null toggles. */
    public CompletableFuture<Result> best(Online s, Target target, Boolean best) {
        return withFriend(s, target, (me, f) -> {
            boolean on = best != null ? best : !f.best();
            me.friends.put(f.id(), f.withBest(on));
            write(() -> storage.setBest(s.id(), f.id(), on));
            return reply(s, Messages.best(Messages.name(f.prefix(), f.name()), on), Result.SUCCESS);
        });
    }

    /** {@code nickname} null clears it. */
    public CompletableFuture<Result> nickname(Online s, Target target, String nickname) {
        if (nickname != null && !NICKNAME.matcher(nickname).matches()) return reply(s, Messages.invalidNickname(), Result.INVALID_ARGUMENT);
        return withFriend(s, target, (me, f) -> {
            me.friends.put(f.id(), f.withNickname(nickname));
            write(() -> storage.setNickname(s.id(), f.id(), nickname));
            return reply(s, Messages.nickname(Messages.name(f.prefix(), f.name()), nickname), Result.SUCCESS);
        });
    }

    public CompletableFuture<Result> toggleNotifications(Online s) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        synchronized (lock) {
            boolean enabled = !me.notifications;
            me.notifications = enabled;
            write(() -> storage.setNotifications(s.id(), enabled));
            return reply(s, Messages.notifications(enabled), Result.SUCCESS);
        }
    }

    /** {@code status} null shows the current status with clickable options. */
    public CompletableFuture<Result> status(Online s, Status status) {
        Presence current = presence.get(s.id());
        if (!profiles.containsKey(s.id()) || current == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        if (status == null) return reply(s, Messages.statusMenu(current.status()), Result.SUCCESS);
        if (status != current.status() && !hooks.statusChanging(row(s), current.status(), status)) {
            return completedFuture(Result.CANCELLED);
        }
        synchronized (lock) {
            Presence now = presence.get(s.id()); // re-read: plugins were asked outside the lock
            if (!profiles.containsKey(s.id()) || now == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
            write(() -> storage.setStatus(s.id(), status));
            emit(new Event.Updated(s.id(), now.withStatus(status)));
            return reply(s, Messages.statusSet(status), Result.SUCCESS);
        }
    }

    /**
     * {@code /msg}: a private message to a friend who is visibly online anywhere on the network. Plugins may cancel
     * or rewrite it first ({@link Hooks#messaging}); a cancelled message is left entirely to them.
     */
    public CompletableFuture<Result> message(Online s, Target target, String text) {
        return message(s, target, text, "/msg <friend> <message>");
    }

    private CompletableFuture<Result> message(Online s, Target target, String text, String usage) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        String body = text == null ? "" : UNPRINTABLE.matcher(text).replaceAll("").strip();
        if (body.isEmpty()) return reply(s, Messages.usage(usage), Result.INVALID_ARGUMENT);
        Friend f = find(me, target);
        if (f == null) return reply(s, Messages.notFriend(target.label()), Result.NOT_FRIENDS);
        Presence p = visible(f.id());
        if (p == null) return reply(s, Messages.offline(Messages.friendName(f), f.lastSeen(), clock.instant()), Result.NOT_ONLINE);
        String sent = hooks.messaging(row(s), new PlayerRow(f.id(), p.name(), p.prefix(), f.lastSeen()), body);
        if (sent == null) return completedFuture(Result.CANCELLED);
        // Plugins were asked without the lock: check again that there is still someone to deliver to.
        if (!me.friends.containsKey(f.id())) return reply(s, Messages.notFriend(target.label()), Result.NOT_FRIENDS);
        if (visible(f.id()) == null) return reply(s, Messages.offline(Messages.friendName(f), f.lastSeen(), clock.instant()), Result.NOT_ONLINE);
        me.replyTo = new Target(f.id(), f.name());
        s.audience().sendMessage(Messages.messageTo(Messages.friendName(f), f.name(), sent));
        Event event = new Event.PrivateMessage(row(s), f.id(), sent);
        apply(event); // delivers it if the friend is on this proxy
        // Straight to the network, not through the SQL queue like emit(): no write to wait for, and chat must not lag.
        if (network != Network.LOCAL) network.publish(event);
        return completedFuture(Result.SUCCESS);
    }

    /** {@code /r}: {@link #message} to whoever this player last messaged or heard from. */
    public CompletableFuture<Result> replyMessage(Online s, String text) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        Target to = me.replyTo;
        return to == null ? reply(s, Messages.nobodyToReply(), Result.INVALID_ARGUMENT) : message(s, to, text, "/r <message>");
    }

    /** Online anywhere and not appearing offline, else null. */
    private Presence visible(UUID id) {
        Presence p = presence.get(id);
        return p == null || p.status() == Status.OFFLINE ? null : p;
    }

    public CompletableFuture<Result> list(Online s, int page, boolean bestOnly) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        List<Messages.Entry> entries = me.friends.values().stream()
                .filter(f -> !bestOnly || f.best())
                .map(this::entry)
                .sorted(LIST_ORDER)
                .toList();
        if (entries.isEmpty()) return reply(s, Messages.noFriends(bestOnly), Result.SUCCESS);
        int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int p = Math.clamp(page, 1, pages);
        List<Messages.Entry> shown = entries.subList((p - 1) * PAGE_SIZE, Math.min(entries.size(), p * PAGE_SIZE));
        return reply(s, Messages.list(shown, p, pages, bestOnly, clock.instant()), Result.SUCCESS);
    }

    /** Best friends first, then online (by name), then offline (most recently seen first). */
    private static final Comparator<Messages.Entry> LIST_ORDER = Comparator
            .comparing((Messages.Entry e) -> !e.friend().best())
            .thenComparing(e -> e.status() == null)
            .thenComparing((a, b) -> a.status() == null
                    ? b.friend().lastSeen().compareTo(a.friend().lastSeen())
                    : a.friend().name().compareToIgnoreCase(b.friend().name()));

    private Messages.Entry entry(Friend f) {
        Presence p = visible(f.id());
        return p == null ? new Messages.Entry(f, null, null, null) : new Messages.Entry(f, p.status(), p.server(), p.activity());
    }

    public CompletableFuture<Result> requests(Online s) {
        List<Request> in = incoming(s.id()).toList();
        List<Request> out = outgoing(s.id()).toList();
        if (in.isEmpty() && out.isEmpty()) return reply(s, Messages.noRequests(), Result.SUCCESS);
        return reply(s, Messages.requests(in, out, clock.instant()), Result.SUCCESS);
    }

    // --- tab completion ---

    public List<String> friendNames(UUID id) {
        Profile p = profiles.get(id);
        return p == null ? List.of() : p.friends.values().stream().map(Friend::name).toList();
    }

    /** Friends a private message would reach right now (tab completion of {@code /msg}). */
    public List<String> onlineFriendNames(UUID id) {
        Profile p = profiles.get(id);
        return p == null ? List.of() : p.friends.values().stream().filter(f -> visible(f.id()) != null).map(Friend::name).toList();
    }

    public List<String> requesterNames(UUID id) {
        return incoming(id).map(r -> r.from().name()).toList();
    }

    /** A local player's cached friends (the API's reads; tests compare this with the database). */
    List<Friend> cachedFriends(UUID id) {
        Profile p = profiles.get(id);
        return p == null ? List.of() : List.copyOf(p.friends.values());
    }

    /** Everyone visibly online on any proxy (players appearing offline are left out). */
    public List<String> onlineNames() {
        return presence.values().stream().filter(p -> p.status() != Status.OFFLINE).map(Presence::name).toList();
    }

    // --- API reads (memory, except friendsOf) ---

    boolean loaded(UUID id) {
        return profiles.containsKey(id);
    }

    /** From memory: false unless {@code a} or {@code b} is loaded here. */
    boolean friendsWith(UUID a, UUID b) {
        Profile p = profiles.get(a);
        if (p != null) return p.friends.containsKey(b);
        Profile q = profiles.get(b);
        return q != null && q.friends.containsKey(a);
    }

    /** Where the player is on the network, or null if offline. */
    Presence presenceOf(UUID id) {
        return presence.get(id);
    }

    /** Any player's friends: the profile if loaded here, else the database (briefly cached, see {@link #offline}). */
    CompletableFuture<List<Friend>> friendsOf(UUID id) {
        Profile p = profiles.get(id);
        if (p != null) return completedFuture(List.copyOf(p.friends.values()));
        Instant now = clock.instant();
        Cached cached = offline.get(id);
        if (cached != null && now.isBefore(cached.expires())) return cached.friends();
        if (offline.size() >= OFFLINE_MAX) evictOffline(now);
        // Cache first, then queue the read: a change that drops the entry meanwhile has queued its write before
        // dropping it, so any entry still cached was read after that write.
        CompletableFuture<List<Friend>> read = new CompletableFuture<>();
        Cached entry = new Cached(read, now.plus(OFFLINE_TTL));
        offline.put(id, entry);
        db(() -> List.copyOf(storage.load(id).friends())).whenComplete((friends, e) -> {
            if (e == null) {
                read.complete(friends);
            } else {
                offline.remove(id, entry); // don't cache failures
                read.completeExceptionally(e);
            }
        });
        return read;
    }

    private void evictOffline(Instant now) {
        offline.values().removeIf(c -> !now.isBefore(c.expires()));
        for (var it = offline.keySet().iterator(); offline.size() >= OFFLINE_MAX && it.hasNext(); ) {
            it.next();
            it.remove();
        }
    }

    // --- helpers ---

    private interface FriendAction {
        CompletableFuture<Result> apply(Profile me, Friend friend);
    }

    private CompletableFuture<Result> withFriend(Online s, Target target, FriendAction action) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded(), Result.NOT_LOADED);
        synchronized (lock) { // look up and change in one step
            Friend f = find(me, target);
            return f != null ? action.apply(me, f) : reply(s, Messages.notFriend(target.label()), Result.NOT_FRIENDS);
        }
    }

    private static Friend find(Profile me, Target target) {
        if (target.id() != null) return me.friends.get(target.id());
        return me.friends.values().stream().filter(f -> f.name().equalsIgnoreCase(target.name())).findFirst().orElse(null);
    }

    // ponytail: linear scans over all pending requests; index by player if request volume ever matters.
    Stream<Request> incoming(UUID id) {
        return requests.values().stream().filter(r -> r.to().id().equals(id) && valid(r) != null);
    }

    Stream<Request> outgoing(UUID id) {
        return requests.values().stream().filter(r -> r.from().id().equals(id) && valid(r) != null);
    }

    private Request valid(Request r) {
        return r != null && clock.instant().isBefore(r.expires()) ? r : null;
    }

    private PlayerRow row(Online p) {
        return new PlayerRow(p.id(), p.name(), p.prefix(), clock.instant());
    }

    /** Online anywhere on the network first (no query), then the database. */
    private CompletableFuture<Optional<PlayerRow>> resolve(Target target) {
        if (target.id() != null) {
            Presence p = presence.get(target.id());
            return p != null
                    ? completedFuture(Optional.of(new PlayerRow(target.id(), p.name(), p.prefix(), clock.instant())))
                    : db(() -> storage.player(target.id()));
        }
        String name = target.name();
        if (!NAME.matcher(name).matches()) return completedFuture(Optional.empty());
        for (var e : presence.entrySet()) { // ponytail: O(online players), fine for a typed command
            Presence p = e.getValue();
            if (p.name().equalsIgnoreCase(name)) {
                return completedFuture(Optional.of(new PlayerRow(e.getKey(), p.name(), p.prefix(), clock.instant())));
            }
        }
        return db(() -> storage.player(name));
    }

    private CompletableFuture<Integer> friendCount(UUID id) {
        Profile p = profiles.get(id);
        return p != null ? completedFuture(p.friends.size()) : db(() -> storage.countFriends(id));
    }

    private void message(UUID id, Component message) {
        Profile p = profiles.get(id);
        if (p != null) p.audience.sendMessage(message);
    }

    private static CompletableFuture<Result> reply(Online s, Component message, Result result) {
        s.audience().sendMessage(message);
        return completedFuture(result);
    }

    private <T> CompletableFuture<T> db(SqlCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (SQLException e) {
                log.error("Friends database error", e);
                throw new CompletionException(e);
            }
        }, db);
    }

    private void write(SqlRun run) {
        db(() -> {
            run.run();
            return null;
        });
    }
}
