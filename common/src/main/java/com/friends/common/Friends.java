package com.friends.common;

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
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.friends.common.Platform.Online;
import com.friends.common.Storage.PlayerRow;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/**
 * Friends core: rules, caches and messaging. Reads come from memory; SQL writes are queued on {@code db}, which must
 * run tasks one at a time in order. Anything other proxies must see is an {@link Event}: applied here via
 * {@link #apply}, then published on the {@link Network}, whose other proxies apply the same event.
 */
public final class Friends implements Network.Listener {
    public static final int PAGE_SIZE = 10;
    private static final Pattern NAME = Pattern.compile("[\\w.*]{1,16}");
    private static final Pattern NICKNAME = Pattern.compile("[\\p{L}\\p{N}_ ]{1,16}");

    /** Cached state of a player on this proxy. {@code audience} is also the session identity. */
    static final class Profile {
        final Audience audience;
        final Map<UUID, Friend> friends = new ConcurrentHashMap<>();
        volatile boolean notifications;

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

    public Friends(Storage storage, Platform platform, Network network, Executor db, Clock clock,
                   Duration requestExpiry, int maxFriends, Logger log) {
        this.storage = storage;
        this.platform = platform;
        this.network = network;
        this.proxy = network.proxyId();
        this.db = db;
        this.clock = clock;
        this.requestExpiry = requestExpiry;
        this.maxFriends = maxFriends;
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
                presence.put(id, p);
                Instant now = clock.instant();
                forFriendsOf(id, f -> f.seen(p.name(), p.prefix(), now), p.status() != Status.OFFLINE, true);
            }
            case Event.Updated(UUID id, Presence p) ->
                    presence.compute(id, (_, old) -> old == null || old.proxy().equals(p.proxy()) ? p : old);
            case Event.Left(UUID id, String from, Instant lastSeen) -> {
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
                link(a, b, since);
                link(b, a, since);
            }
            case Event.Unfriended(UUID player, List<UUID> others) -> {
                Profile me = profiles.get(player);
                if (me == null) markIfLoading(player);
                for (UUID other : others) {
                    if (me != null) me.friends.remove(other);
                    Profile theirs = profiles.get(other);
                    if (theirs != null) theirs.friends.remove(player);
                    else markIfLoading(other);
                }
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
            emit(new Event.Joined(p.id(), new Presence(p.name(), p.prefix(), proxy, p.server(), data.status())));
            return completedFuture(null);
        }
    }

    /** Call once the player can receive chat (first backend server): tells them about pending requests. */
    public void greet(Online p) {
        long pending = incoming(p.id()).count();
        if (pending > 0) p.audience().sendMessage(Messages.pending(pending));
    }

    /** Call after a server switch so friends see the new server. */
    public void moved(Online p) {
        synchronized (lock) {
            Presence old = presence.get(p.id());
            if (profiles.containsKey(p.id()) && old != null) emit(new Event.Updated(p.id(), old.withServer(p.server())));
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

    // --- commands ---

    public CompletableFuture<Void> help(Online s) {
        return reply(s, Messages.help());
    }

    public CompletableFuture<Void> add(Online s, String name) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        if (s.name().equalsIgnoreCase(name)) return reply(s, Messages.addSelf());
        return resolve(name).thenCompose(found -> {
            if (found.isEmpty()) return reply(s, Messages.notFound(name));
            PlayerRow target = found.get();
            return friendCount(target.id()).thenCompose(count -> {
                Request theirs;
                synchronized (lock) { // all checks and the send in one step
                    Component targetName = Messages.name(target);
                    if (me.friends.containsKey(target.id())) return reply(s, Messages.alreadyFriends(targetName));
                    theirs = valid(requests.get(new Request.Key(target.id(), s.id())));
                    if (theirs == null) {
                        if (valid(requests.get(new Request.Key(s.id(), target.id()))) != null) return reply(s, Messages.alreadySent(targetName));
                        if (me.friends.size() >= maxFriends) return reply(s, Messages.limitSelf(maxFriends));
                        if (count >= maxFriends) return reply(s, Messages.limitOther(targetName));
                        emit(new Event.RequestSent(new Request(row(s), target, clock.instant().plus(requestExpiry))));
                        return reply(s, Messages.sent(targetName, requestExpiry.toMinutes()));
                    }
                }
                return accept(s, me, theirs); // both asked: just make them friends (outside the lock: it may claim)
            });
        });
    }

    public CompletableFuture<Void> accept(Online s, String name) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        Request r = incoming(s.id()).filter(x -> x.from().name().equalsIgnoreCase(name)).findFirst().orElse(null);
        return r == null ? reply(s, Messages.noRequest(name)) : accept(s, me, r);
    }

    private CompletableFuture<Void> accept(Online s, Profile me, Request r) {
        PlayerRow other = r.from();
        if (me.friends.size() >= maxFriends) return reply(s, Messages.limitSelf(maxFriends));
        return friendCount(other.id()).thenCompose(count -> {
            if (count >= maxFriends) return reply(s, Messages.limitOther(Messages.name(other)));
            if (!network.claim(s.id(), other.id())) return reply(s, Messages.noRequest(other.name())); // lost a crossing accept
            synchronized (lock) {
                if (!requests.remove(r.key(), r)) return reply(s, Messages.noRequest(other.name())); // expired/raced
                if (me.friends.containsKey(other.id())) return completedFuture(null); // crossing accepts: already done
                Instant now = clock.instant();
                write(() -> storage.addFriendship(s.id(), other.id(), now));
                emit(new Event.Befriended(other, row(s), now)); // tells both sides, wherever they are
                return completedFuture(null);
            }
        });
    }

    public CompletableFuture<Void> deny(Online s, String name) {
        synchronized (lock) {
            Request r = incoming(s.id()).filter(x -> x.from().name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (r == null || !requests.remove(r.key(), r)) return reply(s, Messages.noRequest(name));
            emit(new Event.RequestDenied(r.from().id(), s.id()));
            return reply(s, Messages.declined(Messages.name(r.from())));
        }
    }

    public CompletableFuture<Void> remove(Online s, String name) {
        return withFriend(s, name, (_, f) -> {
            write(() -> storage.removeFriendships(s.id(), List.of(f.id())));
            emit(new Event.Unfriended(s.id(), List.of(f.id())));
            return reply(s, Messages.removed(Messages.name(f.prefix(), f.name())));
        });
    }

    public CompletableFuture<Void> removeAll(Online s, boolean confirmed) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        synchronized (lock) {
            List<UUID> victims = me.friends.values().stream().filter(f -> !f.best()).map(Friend::id).toList();
            if (victims.isEmpty()) return reply(s, Messages.nothingToRemove());
            if (!confirmed) return reply(s, Messages.removeAllConfirm(victims.size()));
            write(() -> storage.removeFriendships(s.id(), victims));
            emit(new Event.Unfriended(s.id(), victims));
            return reply(s, Messages.removedAll(victims.size()));
        }
    }

    public CompletableFuture<Void> best(Online s, String name) {
        return withFriend(s, name, (me, f) -> {
            boolean best = !f.best();
            me.friends.put(f.id(), f.withBest(best));
            write(() -> storage.setBest(s.id(), f.id(), best));
            return reply(s, Messages.best(Messages.name(f.prefix(), f.name()), best));
        });
    }

    /** {@code nickname} null clears it. */
    public CompletableFuture<Void> nickname(Online s, String name, String nickname) {
        if (nickname != null && !NICKNAME.matcher(nickname).matches()) return reply(s, Messages.invalidNickname());
        return withFriend(s, name, (me, f) -> {
            me.friends.put(f.id(), f.withNickname(nickname));
            write(() -> storage.setNickname(s.id(), f.id(), nickname));
            return reply(s, Messages.nickname(Messages.name(f.prefix(), f.name()), nickname));
        });
    }

    public CompletableFuture<Void> toggleNotifications(Online s) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        synchronized (lock) {
            boolean enabled = !me.notifications;
            me.notifications = enabled;
            write(() -> storage.setNotifications(s.id(), enabled));
            return reply(s, Messages.notifications(enabled));
        }
    }

    /** {@code status} null shows the current status with clickable options. */
    public CompletableFuture<Void> status(Online s, Status status) {
        synchronized (lock) {
            Presence current = presence.get(s.id());
            if (!profiles.containsKey(s.id()) || current == null) return reply(s, Messages.notLoaded());
            if (status == null) return reply(s, Messages.statusMenu(current.status()));
            write(() -> storage.setStatus(s.id(), status));
            emit(new Event.Updated(s.id(), current.withStatus(status)));
            return reply(s, Messages.statusSet(status));
        }
    }

    public CompletableFuture<Void> list(Online s, int page, boolean bestOnly) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        List<Messages.Entry> entries = me.friends.values().stream()
                .filter(f -> !bestOnly || f.best())
                .map(this::entry)
                .sorted(LIST_ORDER)
                .toList();
        if (entries.isEmpty()) return reply(s, Messages.noFriends(bestOnly));
        int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int p = Math.clamp(page, 1, pages);
        List<Messages.Entry> shown = entries.subList((p - 1) * PAGE_SIZE, Math.min(entries.size(), p * PAGE_SIZE));
        return reply(s, Messages.list(shown, p, pages, bestOnly, clock.instant()));
    }

    /** Best friends first, then online (by name), then offline (most recently seen first). */
    private static final Comparator<Messages.Entry> LIST_ORDER = Comparator
            .comparing((Messages.Entry e) -> !e.friend().best())
            .thenComparing(e -> e.status() == null)
            .thenComparing((a, b) -> a.status() == null
                    ? b.friend().lastSeen().compareTo(a.friend().lastSeen())
                    : a.friend().name().compareToIgnoreCase(b.friend().name()));

    private Messages.Entry entry(Friend f) {
        Presence p = presence.get(f.id());
        return p == null || p.status() == Status.OFFLINE
                ? new Messages.Entry(f, null, null)
                : new Messages.Entry(f, p.status(), p.server());
    }

    public CompletableFuture<Void> requests(Online s) {
        List<Request> in = incoming(s.id()).toList();
        List<Request> out = requests.values().stream().filter(r -> r.from().id().equals(s.id()) && valid(r) != null).toList();
        if (in.isEmpty() && out.isEmpty()) return reply(s, Messages.noRequests());
        return reply(s, Messages.requests(in, out, clock.instant()));
    }

    // --- tab completion ---

    public List<String> friendNames(UUID id) {
        Profile p = profiles.get(id);
        return p == null ? List.of() : p.friends.values().stream().map(Friend::name).toList();
    }

    public List<String> requesterNames(UUID id) {
        return incoming(id).map(r -> r.from().name()).toList();
    }

    /** A local player's cached friends (tests compare this with the database). */
    List<Friend> cachedFriends(UUID id) {
        Profile p = profiles.get(id);
        return p == null ? List.of() : List.copyOf(p.friends.values());
    }

    /** Everyone visibly online on any proxy (players appearing offline are left out). */
    public List<String> onlineNames() {
        return presence.values().stream().filter(p -> p.status() != Status.OFFLINE).map(Presence::name).toList();
    }

    // --- helpers ---

    private interface FriendAction {
        CompletableFuture<Void> apply(Profile me, Friend friend);
    }

    private CompletableFuture<Void> withFriend(Online s, String name, FriendAction action) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        synchronized (lock) { // look up and change in one step
            return me.friends.values().stream().filter(f -> f.name().equalsIgnoreCase(name)).findFirst()
                    .map(f -> action.apply(me, f))
                    .orElseGet(() -> reply(s, Messages.notFriend(name)));
        }
    }

    // ponytail: linear scan over all pending requests; index by receiver if request volume ever matters.
    private Stream<Request> incoming(UUID id) {
        return requests.values().stream().filter(r -> r.to().id().equals(id) && valid(r) != null);
    }

    private Request valid(Request r) {
        return r != null && clock.instant().isBefore(r.expires()) ? r : null;
    }

    private PlayerRow row(Online p) {
        return new PlayerRow(p.id(), p.name(), p.prefix(), clock.instant());
    }

    /** Online anywhere on the network first (no query), then the database. */
    private CompletableFuture<Optional<PlayerRow>> resolve(String name) {
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

    private static CompletableFuture<Void> reply(Online s, Component message) {
        s.audience().sendMessage(message);
        return completedFuture(null);
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
