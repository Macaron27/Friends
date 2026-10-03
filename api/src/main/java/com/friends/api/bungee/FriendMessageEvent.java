package com.friends.api.bungee;

import java.util.Objects;
import java.util.UUID;

import net.md_5.bungee.api.plugin.Cancellable;

/**
 * {@code player} is about to send {@code target}, one of their friends, a private message ({@code /msg} or
 * {@code /r}). Fired once network-wide, on the sender's proxy, before anything is delivered.
 *
 * <p>Cancel it to take over: Friends then delivers nothing, echoes nothing to the sender and doesn't update either
 * player's {@code /r} target, so a chat plugin can handle the messaging layer itself (or a moderation plugin can
 * block it, explaining why). Or change the text with {@link #setMessage} (e.g. a chat filter).
 */
public final class FriendMessageEvent extends FriendEvent implements Cancellable {
    private String message;
    private boolean cancelled;

    /**
     * Created by Friends.
     *
     * @param playerId   the sender
     * @param playerName their name
     * @param targetId   the recipient
     * @param targetName their name
     * @param message    what the sender typed
     */
    public FriendMessageEvent(UUID playerId, String playerName, UUID targetId, String targetName, String message) {
        super(playerId, playerName, targetId, targetName);
        this.message = message;
    }

    /**
     * The text to deliver (plain text: never parsed for colours or formatting).
     *
     * @return never null
     */
    public String getMessage() {
        return message;
    }

    /**
     * Replaces the text to deliver.
     *
     * @param message the new text (plain, never parsed); not null
     */
    public void setMessage(String message) {
        this.message = Objects.requireNonNull(message, "message");
    }

    /**
     * Whether a plugin cancelled the message.
     *
     * @return true if cancelled
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancels (or un-cancels) the message: Friends then does nothing more with it.
     *
     * @param cancel true to cancel
     */
    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }
}
