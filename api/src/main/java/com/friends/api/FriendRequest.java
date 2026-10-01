package com.friends.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A pending friend request. Immutable snapshot. */
public final class FriendRequest {
    private final UUID sender;
    private final String senderName;
    private final UUID target;
    private final String targetName;
    private final Instant expiresAt;

    /**
     * Created by Friends; plugins have no reason to build one.
     *
     * @param sender     UUID of the player who sent it
     * @param senderName their name
     * @param target     UUID of the player who received it
     * @param targetName their name
     * @param expiresAt  when it expires
     */
    public FriendRequest(UUID sender, String senderName, UUID target, String targetName, Instant expiresAt) {
        this.sender = sender;
        this.senderName = senderName;
        this.target = target;
        this.targetName = targetName;
        this.expiresAt = expiresAt;
    }

    /**
     * Who sent the request.
     *
     * @return the sender's UUID, never null
     */
    public UUID getSender() {
        return sender;
    }

    /**
     * The sender's name.
     *
     * @return never null
     */
    public String getSenderName() {
        return senderName;
    }

    /**
     * Who received the request.
     *
     * @return the target's UUID, never null
     */
    public UUID getTarget() {
        return target;
    }

    /**
     * The target's name.
     *
     * @return never null
     */
    public String getTargetName() {
        return targetName;
    }

    /**
     * When the request expires (it can't be accepted after that).
     *
     * @return never null
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FriendRequest)) return false;
        FriendRequest r = (FriendRequest) o;
        return sender.equals(r.sender) && senderName.equals(r.senderName) && target.equals(r.target)
                && targetName.equals(r.targetName) && expiresAt.equals(r.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, senderName, target, targetName, expiresAt);
    }

    @Override
    public String toString() {
        return "FriendRequest{" + senderName + " -> " + targetName + ", expiresAt=" + expiresAt + "}";
    }
}
