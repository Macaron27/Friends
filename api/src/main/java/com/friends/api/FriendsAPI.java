package com.friends.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Friends' API for other plugins: read friend lists, presence and requests, and act on behalf of players. The same
 * API exists on Paper, Velocity and BungeeCord; each platform also fires its own events (packages
 * {@code com.friends.api.bukkit}, {@code .velocity} and {@code .bungee}).
 *
 * <h2>Getting it</h2>
 * Call {@link #get()} once Friends is enabled: declare a dependency on {@code Friends} ({@code depend} or
 * {@code softdepend} in plugin.yml / bungee.yml, {@code @Dependency(id = "friends")} on Velocity). On Paper it is also
 * a Bukkit service: {@code getServer().getServicesManager().load(FriendsAPI.class)}. Don't cache it across a reload
 * of Friends.
 *
 * <h2>Threads</h2>
 * Every method is thread-safe. Methods returning a value read memory only: they never block or touch the database, so
 * they are fine on the server thread. Methods returning a {@link CompletableFuture} return at once and do their work
 * (and any database access) on Friends' threads, where the future completes (unless it is already complete, e.g.
 * {@link #loadFriends} of a loaded player): switch back to the server thread (e.g. Bukkit's scheduler) before using
 * the world or the Bukkit API from a callback. Never {@code join()} such a future on the server thread.
 *
 * <h2>What is cached</h2>
 * Players online on this server (or proxy) are <em>loaded</em>: their friends and settings live in memory, so every
 * read about them is free. Status, current server and pending requests are known network-wide. Friends of other
 * players come from the database through {@link #loadFriends}.
 *
 * <h2>Invalid input</h2>
 * Nothing here throws: a null or invalid argument gives an empty result, {@code false} or
 * {@link Result#INVALID_ARGUMENT}. Futures of actions never complete exceptionally (database errors give
 * {@link Result#ERROR}).
 *
 * <h2>Actions</h2>
 * Actions behave like the matching command, events and limits included, with one difference: the acting player gets
 * no chat reply (use the {@link Result}). Other players are still notified ("Friend request from...", "You are now
 * friends with..."). The acting player must be loaded, else {@link Result#NOT_LOADED}.
 */
public interface FriendsAPI {

    /**
     * The running API.
     *
     * @return the API
     * @throws IllegalStateException if Friends is not enabled (your plugin doesn't depend on it, or loads first)
     */
    static FriendsAPI get() {
        FriendsAPI api = FriendsProvider.instance;
        if (api == null) {
            throw new IllegalStateException("Friends is not enabled: declare a dependency on Friends, or use FriendsAPI.find()");
        }
        return api;
    }

    /**
     * The running API, if Friends is enabled. For soft dependencies.
     *
     * @return the API, or empty if Friends is not enabled
     */
    static Optional<FriendsAPI> find() {
        return Optional.ofNullable(FriendsProvider.instance);
    }

    // --- Reads: memory only, never block ---

    /**
     * Whether the player's friends are in memory here, i.e. they are online on this server (proxy) and loaded.
     *
     * @param player a player's UUID
     * @return true if loaded; false if offline, online elsewhere, still loading, or null
     */
    boolean isLoaded(UUID player);

    /**
     * A loaded player's friends, in no particular order. For other players use {@link #loadFriends}.
     *
     * @param player a player's UUID
     * @return an unmodifiable snapshot; empty if the player has no friends or is not {@linkplain #isLoaded loaded}
     */
    List<Friend> getFriends(UUID player);

    /**
     * Whether two players are friends, from memory: at least one of them must be {@linkplain #isLoaded loaded} (for
     * example, both are online on this server). Otherwise use {@link #loadFriends}.
     *
     * @param a a player's UUID
     * @param b another player's UUID
     * @return true if they are friends; false if not, if neither is loaded, or if an argument is null
     */
    boolean areFriends(UUID a, UUID b);

    /**
     * The status of a player online anywhere on the network.
     *
     * @param player a player's UUID
     * @return their status ({@link Status#OFFLINE} while they appear offline), or empty if they are not online
     */
    Optional<Status> getStatus(UUID player);

    /**
     * The backend server a player is on (proxies only).
     *
     * @param player a player's UUID
     * @return the server name; empty on Paper, while connecting, if offline, or if they appear offline
     */
    Optional<String> getServer(UUID player);

    /**
     * What a player online anywhere on the network is doing, from the first {@code presence.rules} pattern matching
     * their backend server's name (proxies only). Synchronised across proxies, like {@link #getServer}.
     *
     * @param player a player's UUID
     * @return e.g. BedWars / Solo; empty on Paper, while connecting, if no rule matches their server, if offline, or
     *         if they appear offline
     * @see LastSeen#line
     */
    Optional<PlayerActivity> getActivity(UUID player);

    /**
     * Pending requests the player received (network-wide).
     *
     * @param player a player's UUID
     * @return an unmodifiable snapshot, possibly empty
     */
    List<FriendRequest> getIncomingRequests(UUID player);

    /**
     * Pending requests the player sent (network-wide).
     *
     * @param player a player's UUID
     * @return an unmodifiable snapshot, possibly empty
     */
    List<FriendRequest> getOutgoingRequests(UUID player);

    // --- Async: return at once, complete on Friends' threads ---

    /**
     * Any player's friends: from memory if they are loaded, else from the database. Offline players' lists are
     * cached for up to 30 seconds (and dropped as soon as a friendship of theirs changes), so repeated calls are
     * cheap.
     *
     * @param player a player's UUID
     * @return their friends (unmodifiable, no particular order; empty if none, never joined, or null); completes
     *         exceptionally only if the database fails, which Friends logs
     */
    CompletableFuture<List<Friend>> loadFriends(UUID player);

    /**
     * {@code /f add}: sends a friend request, or makes them friends if the target had already asked the sender.
     * Fires the request (or add) event, which can cancel it.
     *
     * @param sender a loaded player
     * @param target any player who has joined before
     * @return {@link Result#SUCCESS} (sent), {@link Result#BECAME_FRIENDS}, or why not: {@link Result#NOT_LOADED},
     *         {@link Result#SELF}, {@link Result#PLAYER_NOT_FOUND}, {@link Result#ALREADY_FRIENDS},
     *         {@link Result#ALREADY_REQUESTED}, {@link Result#LIMIT_REACHED}, {@link Result#TARGET_LIMIT_REACHED},
     *         {@link Result#IGNORED} (either one ignores the other), {@link Result#CANCELLED},
     *         {@link Result#INVALID_ARGUMENT}, {@link Result#ERROR}
     */
    CompletableFuture<Result> sendRequest(UUID sender, UUID target);

    /**
     * {@code /f accept}: accepts a pending request. Fires the add event, which can cancel it (the request then stays
     * pending).
     *
     * @param player a loaded player who received a request
     * @param sender who sent it
     * @return {@link Result#SUCCESS}, or {@link Result#NOT_LOADED}, {@link Result#NO_REQUEST},
     *         {@link Result#LIMIT_REACHED}, {@link Result#TARGET_LIMIT_REACHED}, {@link Result#IGNORED} ({@code player}
     *         ignores {@code sender}), {@link Result#CANCELLED}, {@link Result#INVALID_ARGUMENT}, {@link Result#ERROR}
     */
    CompletableFuture<Result> acceptRequest(UUID player, UUID sender);

    /**
     * {@code /f deny}: declines a pending request.
     *
     * @param player a loaded player who received a request
     * @param sender who sent it
     * @return {@link Result#SUCCESS}, or {@link Result#NOT_LOADED}, {@link Result#NO_REQUEST},
     *         {@link Result#INVALID_ARGUMENT}
     */
    CompletableFuture<Result> denyRequest(UUID player, UUID sender);

    /**
     * {@code /f remove}: ends a friendship (for both sides). Fires the remove event, which can cancel it.
     *
     * @param player a loaded player
     * @param friend one of their friends
     * @return {@link Result#SUCCESS}, or {@link Result#NOT_LOADED}, {@link Result#NOT_FRIENDS},
     *         {@link Result#CANCELLED}, {@link Result#INVALID_ARGUMENT}
     */
    CompletableFuture<Result> removeFriend(UUID player, UUID friend);

    /**
     * {@code /f best}: marks or unmarks a best friend (private to {@code player}).
     *
     * @param player a loaded player
     * @param friend one of their friends
     * @param best   true to mark, false to unmark
     * @return {@link Result#SUCCESS} (also if it already was so), or {@link Result#NOT_LOADED},
     *         {@link Result#NOT_FRIENDS}, {@link Result#INVALID_ARGUMENT}
     */
    CompletableFuture<Result> setBestFriend(UUID player, UUID friend, boolean best);

    /**
     * {@code /f nickname}: sets the nickname {@code player} sees for a friend.
     *
     * @param player   a loaded player
     * @param friend   one of their friends
     * @param nickname 1 to 16 letters, digits, spaces or underscores; null clears it
     * @return {@link Result#SUCCESS}, or {@link Result#NOT_LOADED}, {@link Result#NOT_FRIENDS},
     *         {@link Result#INVALID_ARGUMENT}
     */
    CompletableFuture<Result> setNickname(UUID player, UUID friend, String nickname);

    /**
     * {@code /status}: sets the status friends see. Fires the status change event, which can cancel it.
     *
     * @param player a loaded player
     * @param status the new status ({@link Status#OFFLINE} = appear offline)
     * @return {@link Result#SUCCESS}, or {@link Result#NOT_LOADED}, {@link Result#CANCELLED},
     *         {@link Result#INVALID_ARGUMENT}
     */
    CompletableFuture<Result> setStatus(UUID player, Status status);

    /**
     * {@code /msg}: sends a friend a private message, with every rule of the command: the receiver must be a friend
     * who is visibly online anywhere on the network, the sender's {@code private-messages.rate-limit} applies, and
     * the message event fires first (a plugin may cancel or rewrite it). Works with {@code private-messages.enabled:
     * false} too, which only leaves the commands to another plugin.
     *
     * <p>The sender sees no "To ..." echo (they get no chat reply, like every action), but a friend whose status is
     * {@link Status#AWAY} still answers with the AFK auto-reply in their chat. Ignoring a player removes them as a
     * friend, so messaging someone who ignores you gives {@link Result#NOT_FRIENDS}: an ignore is never revealed.
     *
     * @param sender   a loaded player
     * @param receiver one of their friends
     * @param message  plain text, never parsed (colour codes and control characters are removed)
     * @return {@link Result#SUCCESS} (delivered), or {@link Result#NOT_LOADED}, {@link Result#NOT_FRIENDS},
     *         {@link Result#NOT_ONLINE}, {@link Result#RATE_LIMITED}, {@link Result#CANCELLED},
     *         {@link Result#INVALID_ARGUMENT} (null, or nothing printable), {@link Result#ERROR}
     */
    CompletableFuture<Result> sendMessage(UUID sender, UUID receiver, String message);
}
