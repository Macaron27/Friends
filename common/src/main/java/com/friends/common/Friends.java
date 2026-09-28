package com.friends.common;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.friends.common.Platform.Online;
import com.friends.common.Storage.PlayerRow;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/**
 * Friends core: rules, per-online-player cache and messaging. Reads come from the cache; writes update the cache
 * first and are queued on {@code db}, which must run tasks one at a time in order (writes, then later loads).
 * ponytail: single proxy only, state lives in this JVM. Multi-proxy needs a shared cache + pub/sub (e.g. Redis).
 */
public final class Friends {
    public static final int PAGE_SIZE = 10;
    private static final Pattern NAME = Pattern.compile("[\\w.*]{1,16}");
    private static final Pattern NICKNAME = Pattern.compile("[\\p{L}\\p{N}_ ]{1,16}");

    record Key(UUID from, UUID to) {}

    record Request(PlayerRow from, PlayerRow to, Instant expires) {
        Key key() {
            return new Key(from.id(), to.id());
        }
    }

    /** Cached state of an online player. {@code audience} is also the session identity. */
    static final class Profile {
        final Audience audience;
        final Map<UUID, Friend> friends = new ConcurrentHashMap<>();
        volatile boolean notifications;
        volatile Status status;

        Profile(Audience audience, Storage.Loaded data) {
            this.audience = audience;
            this.notifications = data.notifications();
            this.status = data.status();
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
    private final Executor db;
    private final Clock clock;
    private final Duration requestExpiry;
    private final int maxFriends;
    private final Logger log;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    // ponytail: requests live in memory (they expire after minutes anyway) and are lost on restart.
    private final Map<Key, Request> requests = new ConcurrentHashMap<>();

    public Friends(Storage storage, Platform platform, Executor db, Clock clock, Duration requestExpiry, int maxFriends, Logger log) {
        this.storage = storage;
        this.platform = platform;
        this.db = db;
        this.clock = clock;
        this.requestExpiry = requestExpiry;
        this.maxFriends = maxFriends;
        this.log = log;
    }

    // --- lifecycle ---

    /** Loads the player's data, then announces them to online friends. Never completes exceptionally. */
    public CompletableFuture<Void> connect(Online p) {
        Instant now = clock.instant();
        return db(() -> {
            storage.savePlayer(p.id(), p.name(), p.prefix(), now);
            return storage.load(p.id());
        }).thenAccept(data -> {
            // Left (or was replaced by a newer session) while loading: don't resurrect a stale profile.
            if (platform.player(p.id()).filter(o -> o.audience() == p.audience()).isEmpty()) return;
            Profile profile = new Profile(p.audience(), data);
            profiles.put(p.id(), profile);
            announce(p.id(), profile, f -> f.seen(p.name(), p.prefix(), now), true);
        }).exceptionally(_ -> null); // logged by db(); the player just has no profile until they rejoin
    }

    /** Call once the player can receive chat (first backend server): tells them about pending requests. */
    public void greet(Online p) {
        long pending = incoming(p.id()).count();
        if (pending > 0) p.audience().sendMessage(Messages.pending(pending));
    }

    public void disconnect(Online p) {
        Profile profile = profiles.get(p.id());
        // Only the session that loaded the profile may drop it (kick-existing-players can reorder events).
        if (profile == null || profile.audience != p.audience() || !profiles.remove(p.id(), profile)) return;
        Instant now = clock.instant();
        write(() -> storage.touch(p.id(), now));
        announce(p.id(), profile, f -> f.seen(f.name(), f.prefix(), now), false);
    }

    private void announce(UUID id, Profile profile, java.util.function.UnaryOperator<Friend> update, boolean joined) {
        for (UUID friendId : profile.friends.keySet()) {
            Profile other = profiles.get(friendId);
            if (other == null) continue; // offline friends have no profile
            Friend view = other.friends.computeIfPresent(id, (_, f) -> update.apply(f));
            if (view == null || !other.notifications || profile.status == Status.OFFLINE) continue;
            Component name = Messages.friendName(view);
            other.audience.sendMessage(joined ? Messages.joined(name) : Messages.left(name));
        }
    }

    /** Called periodically by the platform; also re-checked lazily, so timing isn't critical. */
    public void expireRequests() {
        Instant now = clock.instant();
        for (Request r : requests.values()) {
            if (now.isBefore(r.expires()) || !requests.remove(r.key(), r)) continue;
            message(r.from().id(), Messages.expiredOutgoing(Messages.name(r.to())));
            message(r.to().id(), Messages.expiredIncoming(Messages.name(r.from())));
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
            Component targetName = Messages.name(target);
            if (me.friends.containsKey(target.id())) return reply(s, Messages.alreadyFriends(targetName));
            Request theirs = valid(requests.get(new Key(target.id(), s.id())));
            if (theirs != null) return accept(s, me, theirs); // both asked: just make them friends
            if (valid(requests.get(new Key(s.id(), target.id()))) != null) return reply(s, Messages.alreadySent(targetName));
            if (me.friends.size() >= maxFriends) return reply(s, Messages.limitSelf(maxFriends));
            return friendCount(target.id()).thenCompose(count -> {
                if (count >= maxFriends) return reply(s, Messages.limitOther(targetName));
                PlayerRow from = row(s);
                requests.put(new Key(s.id(), target.id()), new Request(from, target, clock.instant().plus(requestExpiry)));
                message(target.id(), Messages.received(Messages.name(from), s.name()));
                return reply(s, Messages.sent(targetName, requestExpiry.toMinutes()));
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
            if (!requests.remove(r.key(), r)) return reply(s, Messages.noRequest(other.name())); // expired/raced
            Instant now = clock.instant();
            me.friends.put(other.id(), new Friend(other.id(), other.name(), other.prefix(), now, false, null, other.lastSeen()));
            Profile theirs = profiles.get(other.id());
            if (theirs != null) theirs.friends.put(s.id(), new Friend(s.id(), s.name(), s.prefix(), now, false, null, now));
            write(() -> storage.addFriendship(s.id(), other.id(), now));
            message(other.id(), Messages.nowFriends(Messages.name(row(s))));
            return reply(s, Messages.nowFriends(Messages.name(other)));
        });
    }

    public CompletableFuture<Void> deny(Online s, String name) {
        Request r = incoming(s.id()).filter(x -> x.from().name().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (r == null || !requests.remove(r.key(), r)) return reply(s, Messages.noRequest(name));
        return reply(s, Messages.declined(Messages.name(r.from())));
    }

    public CompletableFuture<Void> remove(Online s, String name) {
        return withFriend(s, name, (me, f) -> {
            unfriend(s.id(), me, f.id());
            write(() -> storage.removeFriendships(s.id(), List.of(f.id())));
            return reply(s, Messages.removed(Messages.name(f.prefix(), f.name())));
        });
    }

    public CompletableFuture<Void> removeAll(Online s, boolean confirmed) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        List<UUID> victims = me.friends.values().stream().filter(f -> !f.best()).map(Friend::id).toList();
        if (victims.isEmpty()) return reply(s, Messages.nothingToRemove());
        if (!confirmed) return reply(s, Messages.removeAllConfirm(victims.size()));
        victims.forEach(id -> unfriend(s.id(), me, id));
        write(() -> storage.removeFriendships(s.id(), victims));
        return reply(s, Messages.removedAll(victims.size()));
    }

    private void unfriend(UUID self, Profile me, UUID friend) {
        me.friends.remove(friend);
        Profile theirs = profiles.get(friend);
        if (theirs != null) theirs.friends.remove(self);
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
        boolean enabled = !me.notifications;
        me.notifications = enabled;
        write(() -> storage.setNotifications(s.id(), enabled));
        return reply(s, Messages.notifications(enabled));
    }

    /** {@code status} null shows the current status with clickable options. */
    public CompletableFuture<Void> status(Online s, Status status) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        if (status == null) return reply(s, Messages.statusMenu(me.status));
        me.status = status;
        write(() -> storage.setStatus(s.id(), status));
        return reply(s, Messages.statusSet(status));
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
        Profile p = profiles.get(f.id());
        if (p == null || p.status == Status.OFFLINE) return new Messages.Entry(f, null, null);
        return new Messages.Entry(f, p.status, platform.player(f.id()).map(Online::server).orElse(null));
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

    public boolean isFriend(UUID id, UUID other) {
        Profile p = profiles.get(id);
        return p != null && p.friends.containsKey(other);
    }

    // --- helpers ---

    private interface FriendAction {
        CompletableFuture<Void> apply(Profile me, Friend friend);
    }

    private CompletableFuture<Void> withFriend(Online s, String name, FriendAction action) {
        Profile me = profiles.get(s.id());
        if (me == null) return reply(s, Messages.notLoaded());
        return me.friends.values().stream().filter(f -> f.name().equalsIgnoreCase(name)).findFirst()
                .map(f -> action.apply(me, f))
                .orElseGet(() -> reply(s, Messages.notFriend(name)));
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

    private CompletableFuture<Optional<PlayerRow>> resolve(String name) {
        if (!NAME.matcher(name).matches()) return completedFuture(Optional.empty());
        Optional<Online> online = platform.player(name);
        if (online.isPresent()) return completedFuture(Optional.of(row(online.get())));
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
