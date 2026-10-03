package com.friends.core;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import com.friends.api.Friend;
import com.friends.api.FriendRequest;
import com.friends.api.FriendsAPI;
import com.friends.api.PlayerActivity;
import com.friends.api.Result;
import com.friends.api.Status;
import com.friends.core.Friends.Target;
import com.friends.core.Platform.Online;

import net.kyori.adventure.audience.Audience;

/** {@link FriendsAPI} over the core: reads come straight from its caches, actions are silent commands run on {@code async}. */
public final class FriendsApi implements FriendsAPI {
    private final Friends friends;
    private final Platform platform;
    private final Executor async;

    public FriendsApi(Friends friends, Platform platform, Executor async) {
        this.friends = friends;
        this.platform = platform;
        this.async = async;
    }

    @Override
    public boolean isLoaded(UUID player) {
        return player != null && friends.loaded(player);
    }

    @Override
    public List<Friend> getFriends(UUID player) {
        return player == null ? List.of() : views(friends.cachedFriends(player));
    }

    @Override
    public boolean areFriends(UUID a, UUID b) {
        return a != null && b != null && friends.friendsWith(a, b);
    }

    @Override
    public Optional<Status> getStatus(UUID player) {
        return presence(player).map(Presence::status);
    }

    @Override
    public Optional<String> getServer(UUID player) {
        return presence(player).filter(p -> p.status() != Status.OFFLINE).map(Presence::server);
    }

    @Override
    public Optional<PlayerActivity> getActivity(UUID player) {
        return presence(player).filter(p -> p.status() != Status.OFFLINE).map(Presence::activity);
    }

    @Override
    public List<FriendRequest> getIncomingRequests(UUID player) {
        return player == null ? List.of() : friends.incoming(player).map(FriendsApi::view).toList();
    }

    @Override
    public List<FriendRequest> getOutgoingRequests(UUID player) {
        return player == null ? List.of() : friends.outgoing(player).map(FriendsApi::view).toList();
    }

    @Override
    public CompletableFuture<List<Friend>> loadFriends(UUID player) {
        return player == null ? completedFuture(List.of()) : friends.friendsOf(player).thenApply(FriendsApi::views);
    }

    @Override
    public CompletableFuture<Result> sendRequest(UUID sender, UUID target) {
        return act(sender, target, s -> friends.add(s, Target.of(target)));
    }

    @Override
    public CompletableFuture<Result> acceptRequest(UUID player, UUID sender) {
        return act(player, sender, s -> friends.accept(s, Target.of(sender)));
    }

    @Override
    public CompletableFuture<Result> denyRequest(UUID player, UUID sender) {
        return act(player, sender, s -> friends.deny(s, Target.of(sender)));
    }

    @Override
    public CompletableFuture<Result> removeFriend(UUID player, UUID friend) {
        return act(player, friend, s -> friends.remove(s, Target.of(friend)));
    }

    @Override
    public CompletableFuture<Result> setBestFriend(UUID player, UUID friend, boolean best) {
        return act(player, friend, s -> friends.best(s, Target.of(friend), best));
    }

    @Override
    public CompletableFuture<Result> setNickname(UUID player, UUID friend, String nickname) {
        return act(player, friend, s -> friends.nickname(s, Target.of(friend), nickname));
    }

    @Override
    public CompletableFuture<Result> setStatus(UUID player, Status status) {
        return act(player, status, s -> friends.status(s, status));
    }

    @Override
    public CompletableFuture<Result> sendMessage(UUID sender, UUID receiver, String message) {
        if (message == null) return completedFuture(Result.INVALID_ARGUMENT);
        return act(sender, receiver, s -> friends.message(s, Target.of(receiver), message));
    }

    /**
     * Runs {@code action} as {@code player}'s command, without the chat reply. The whole action runs on {@code async}
     * (never on the caller's thread, which may be the server thread events must not be fired from).
     */
    private CompletableFuture<Result> act(UUID player, Object arg, Function<Online, CompletableFuture<Result>> action) {
        if (player == null || arg == null) return completedFuture(Result.INVALID_ARGUMENT);
        try {
            return CompletableFuture.supplyAsync(() -> platform.player(player)
                            .map(o -> action.apply(new Online(o.id(), o.name(), o.prefix(), o.server(), Audience.empty())))
                            .orElseGet(() -> completedFuture(Result.NOT_LOADED)), async)
                    .thenCompose(Function.identity())
                    .exceptionally(_ -> Result.ERROR); // the database error is already logged
        } catch (RejectedExecutionException e) { // Friends is shutting down
            return completedFuture(Result.ERROR);
        }
    }

    private Optional<Presence> presence(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(friends.presenceOf(player));
    }

    private static List<Friend> views(List<com.friends.core.Friend> list) {
        return list.stream()
                .map(f -> new Friend(f.id(), f.name(), f.since(), f.best(), f.nickname(), f.lastSeen()))
                .toList();
    }

    private static FriendRequest view(Request r) {
        return new FriendRequest(r.from().id(), r.from().name(), r.to().id(), r.to().name(), r.expires());
    }
}
