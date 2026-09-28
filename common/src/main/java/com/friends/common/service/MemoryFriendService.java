package com.friends.common.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Simple in-memory FriendService for testing command flows. Not persisted.
 */
public class MemoryFriendService implements FriendService {
    private final ConcurrentMap<UUID, Set<UUID>> friendships = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Map<UUID, Instant>> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PlayerSettings> settings = new ConcurrentHashMap<>();

    public static class PlayerSettings {
        volatile boolean notifications = true;
        volatile int requestExpiryMinutes = 15;
        volatile String prefix;
    }

    private final String defaultPrefix;
    private volatile String shortPrefix = "&aF >";
    private volatile boolean useShort = false;

    public MemoryFriendService() { this("&aFriend >", "&aF >", false); }
    public MemoryFriendService(String defaultPrefix) { this(defaultPrefix, "&aF >", false); }
    public MemoryFriendService(String defaultPrefix, String shortPrefix, boolean useShort) {
        this.defaultPrefix = defaultPrefix == null ? "&aFriend >" : defaultPrefix;
        this.shortPrefix = shortPrefix == null ? "&aF >" : shortPrefix;
        this.useShort = useShort;
    }

    private PlayerSettings getSettings(UUID player) {
        PlayerSettings s = settings.computeIfAbsent(player, k -> {
            PlayerSettings ps = new PlayerSettings();
            ps.prefix = useShort ? shortPrefix : defaultPrefix;
            return ps;
        });
        if (s.prefix == null) s.prefix = useShort ? shortPrefix : defaultPrefix;
        return s;
    }

    private final ConcurrentMap<UUID, PlayerInfo> players = new ConcurrentHashMap<>();

    public static class PlayerInfo {
        volatile String username;
        volatile String lastServer;
        volatile Instant lastSeen;
    }

    private PlayerInfo getPlayerInfo(UUID player) {
        return players.computeIfAbsent(player, k -> new PlayerInfo());
    }

    @Override
    public boolean sendRequest(UUID sender, UUID receiver) {
        if (sender.equals(receiver)) return false;
        if (isFriend(sender, receiver)) return false;
        requests.computeIfAbsent(receiver, k -> new ConcurrentHashMap<>()).put(sender, Instant.now());
        return true;
    }

    @Override
    public boolean acceptRequest(UUID receiver, UUID sender) {
        Map<UUID, Instant> r = requests.getOrDefault(receiver, Collections.emptyMap());
        if (!r.containsKey(sender)) return false;
        // remove request
        r.remove(sender);
        // add friendship both ways
        friendships.computeIfAbsent(receiver, k -> ConcurrentHashMap.newKeySet()).add(sender);
        friendships.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(receiver);
        return true;
    }

    @Override
    public boolean denyRequest(UUID receiver, UUID sender) {
        Map<UUID, Instant> r = requests.getOrDefault(receiver, Collections.emptyMap());
        return r.remove(sender) != null;
    }

    @Override
    public boolean removeFriend(UUID player, UUID friend) {
        Set<UUID> a = friendships.getOrDefault(player, Collections.emptySet());
        Set<UUID> b = friendships.getOrDefault(friend, Collections.emptySet());
        boolean removed = a.remove(friend);
        b.remove(player);
        return removed;
    }

    @Override
    public List<UUID> listFriends(UUID player, int page, int pageSize) {
        Set<UUID> set = friendships.getOrDefault(player, Collections.emptySet());
        return set.stream().skip((long)(page-1)*pageSize).limit(pageSize).collect(Collectors.toList());
    }

    @Override
    public List<UUID> listRequests(UUID player, int page, int pageSize) {
        Map<UUID, Instant> map = requests.getOrDefault(player, Collections.emptyMap());
        return map.keySet().stream().skip((long)(page-1)*pageSize).limit(pageSize).collect(Collectors.toList());
    }

    @Override
    public void setRequestExpiry(UUID player, int minutes) {
        getSettings(player).requestExpiryMinutes = minutes;
    }

    @Override
    public int getRequestExpiry(UUID player) {
        return getSettings(player).requestExpiryMinutes;
    }

    @Override
    public void toggleNotifications(UUID player, boolean enabled) {
        getSettings(player).notifications = enabled;
    }

    @Override
    public boolean getNotifications(UUID player) {
        return getSettings(player).notifications;
    }

    @Override
    public void setPrefix(UUID player, String prefix) {
        getSettings(player).prefix = prefix;
    }

    @Override
    public String getPrefix(UUID player) {
        return getSettings(player).prefix;
    }

    @Override
    public void setUseShortPrefix(boolean useShort) {
        this.useShort = useShort;
    }

    @Override
    public boolean getUseShortPrefix() {
        return this.useShort;
    }

    @Override
    public void setShortPrefix(String shortPrefix) {
        this.shortPrefix = shortPrefix;
    }

    @Override
    public String getShortPrefix() {
        return this.shortPrefix;
    }

    private boolean isFriend(UUID a, UUID b) {
        return friendships.getOrDefault(a, Collections.emptySet()).contains(b);
    }

    // Cleanup expired requests for a player using its settings
    public void cleanupExpiredRequests(UUID player) {
        Map<UUID, Instant> r = requests.get(player);
        if (r == null) return;
        int expiry = getRequestExpiry(player);
        Instant now = Instant.now();
        r.entrySet().removeIf(e -> e.getValue().plusSeconds(expiry * 60L).isBefore(now));
    }

    /**
     * Cleanup expired requests for all players.
     */
    public void cleanupAllExpiredRequests() {
        for (UUID player : requests.keySet()) {
            cleanupExpiredRequests(player);
        }
    }

    @Override
    public void updatePlayerInfo(UUID player, String username, String server) {
        PlayerInfo info = getPlayerInfo(player);
        info.username = username;
        info.lastServer = server;
        info.lastSeen = Instant.now();
    }

    @Override
    public String getLastServer(UUID player) {
        PlayerInfo info = players.get(player);
        return info == null ? null : info.lastServer;
    }

    @Override
    public String getLastUsername(UUID player) {
        PlayerInfo info = players.get(player);
        return info == null ? null : info.username;
    }

    @Override
    public java.time.Instant getLastSeen(UUID player) {
        PlayerInfo info = players.get(player);
        return info == null ? null : info.lastSeen;
    }

    @Override
    public java.util.UUID findUuidForName(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase();
        for (java.util.Map.Entry<UUID, PlayerInfo> e : players.entrySet()) {
            if (e.getValue().username != null && e.getValue().username.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }
}
