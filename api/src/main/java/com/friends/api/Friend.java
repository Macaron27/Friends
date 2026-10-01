package com.friends.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One friend, as seen by the player who owns the list. Immutable snapshot: it doesn't change when the friendship
 * does, ask {@link FriendsAPI} again for fresh data. Best friend and nickname are private to the owner.
 */
public final class Friend {
    private final UUID uniqueId;
    private final String name;
    private final Instant since;
    private final boolean bestFriend;
    private final String nickname;
    private final Instant lastSeen;

    /**
     * Created by Friends; plugins have no reason to build one.
     *
     * @param uniqueId   the friend's UUID
     * @param name       the friend's last known name
     * @param since      when they became friends
     * @param bestFriend whether the owner marked them as a best friend
     * @param nickname   the owner's nickname for them, or null
     * @param lastSeen   when the friend last joined or left
     */
    public Friend(UUID uniqueId, String name, Instant since, boolean bestFriend, String nickname, Instant lastSeen) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.since = since;
        this.bestFriend = bestFriend;
        this.nickname = nickname;
        this.lastSeen = lastSeen;
    }

    /**
     * The friend's UUID.
     *
     * @return never null
     */
    public UUID getUniqueId() {
        return uniqueId;
    }

    /**
     * The friend's name the last time Friends saw them.
     *
     * @return never null
     */
    public String getName() {
        return name;
    }

    /**
     * When they became friends.
     *
     * @return never null
     */
    public Instant getSince() {
        return since;
    }

    /**
     * Whether the owner marked this friend as a best friend.
     *
     * @return true for a best friend
     */
    public boolean isBestFriend() {
        return bestFriend;
    }

    /**
     * The nickname the owner gave this friend (only the owner sees it).
     *
     * @return the nickname, or null if none
     */
    public String getNickname() {
        return nickname;
    }

    /**
     * When the friend last joined or left (for an online friend: when they joined).
     *
     * @return never null
     */
    public Instant getLastSeen() {
        return lastSeen;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Friend)) return false;
        Friend f = (Friend) o;
        return bestFriend == f.bestFriend && uniqueId.equals(f.uniqueId) && name.equals(f.name) && since.equals(f.since)
                && Objects.equals(nickname, f.nickname) && lastSeen.equals(f.lastSeen);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueId, name, since, bestFriend, nickname, lastSeen);
    }

    @Override
    public String toString() {
        return "Friend{" + name + " " + uniqueId + ", since=" + since + ", best=" + bestFriend + ", nickname=" + nickname
                + ", lastSeen=" + lastSeen + "}";
    }
}
