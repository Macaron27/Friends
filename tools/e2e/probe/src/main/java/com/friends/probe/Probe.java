package com.friends.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.friends.api.Friend;
import com.friends.api.FriendsAPI;
import com.friends.api.Status;

/** What every platform's probe does; it logs "probe: ..." lines that run.py waits for. */
final class Probe {
    private Probe() {}

    static String pair(String event, String player, String target) {
        return "probe: " + event + " " + player + "->" + target;
    }

    static String status(String player, Status from, Status to) {
        return "probe: FriendStatusChangeEvent " + player + " " + from + "->" + to + (to == Status.AWAY ? " cancelled" : "");
    }

    /** "/status away" is refused, to check that cancelling works. */
    static boolean cancels(Status to) {
        return to == Status.AWAY;
    }

    /** A friendship started: read it back from inside the listener, then act on it from {@code sync}. */
    static void befriended(FriendsAPI api, UUID player, String name, UUID target, Consumer<String> log, Executor sync) {
        boolean friends = api.areFriends(player, target);
        api.loadFriends(player).thenAccept(list -> log.accept("probe: areFriends=" + friends + " friends of " + name + "=" + names(list)));
        sync.execute(() -> api.setStatus(player, Status.BUSY).thenAccept(r -> log.accept("probe: setStatus " + name + " BUSY -> " + r)));
    }

    private static List<String> names(List<Friend> friends) {
        List<String> names = new ArrayList<>();
        for (Friend f : friends) names.add(f.getName());
        return names;
    }
}
