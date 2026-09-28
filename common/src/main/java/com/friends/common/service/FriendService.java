package com.friends.common.service;

import java.util.List;
import java.util.UUID;

public interface FriendService {
    boolean sendRequest(UUID sender, UUID receiver);
    boolean acceptRequest(UUID receiver, UUID sender);
    boolean denyRequest(UUID receiver, UUID sender);
    boolean removeFriend(UUID player, UUID friend);
    List<UUID> listFriends(UUID player, int page, int pageSize);
    List<UUID> listRequests(UUID player, int page, int pageSize);
    void setRequestExpiry(UUID player, int minutes);
    int getRequestExpiry(UUID player);
    void toggleNotifications(UUID player, boolean enabled);
    boolean getNotifications(UUID player);

    /**
     * Per-player message prefix, e.g. "&aFriend >". Defaults to a reasonable value when not set.
     */
    void setPrefix(UUID player, String prefix);
    String getPrefix(UUID player);

    /**
     * Attempt to resolve a player's UUID from a username (last-known). Returns null if unknown.
     */
    java.util.UUID findUuidForName(String name);

    /**
     * Update player's last-known info (username, server) and last-seen timestamp.
     */
    void updatePlayerInfo(UUID player, String username, String server);

    /**
     * Get last-known server for a player or null if unknown.
     */
    String getLastServer(UUID player);

    /**
     * Get last-known username for a player or null if unknown.
     */
    String getLastUsername(UUID player);

    /**
     * Get the last-seen timestamp for a player, or null if unknown.
     */
    java.time.Instant getLastSeen(UUID player);

    // Global short prefix toggle and getter/setter
    void setUseShortPrefix(boolean useShort);
    boolean getUseShortPrefix();

    void setShortPrefix(String shortPrefix);
    String getShortPrefix();
}
