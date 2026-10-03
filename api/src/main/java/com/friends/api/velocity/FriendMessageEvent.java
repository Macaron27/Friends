package com.friends.api.velocity;

import java.util.Objects;
import java.util.UUID;

import com.velocitypowered.api.event.ResultedEvent;

/**
 * {@code player} is about to send {@code target}, one of their friends, a private message ({@code /msg} or
 * {@code /r}). Fired once network-wide, on the sender's proxy, before anything is delivered.
 *
 * <p>Deny it to take over: Friends then delivers nothing, echoes nothing to the sender and doesn't update either
 * player's {@code /r} target, so a chat plugin can handle the messaging layer itself (or a moderation plugin can
 * block it, explaining why). Or change the text with {@link #setMessage} (e.g. a chat filter).
 */
public final class FriendMessageEvent extends FriendEvent implements ResultedEvent<ResultedEvent.GenericResult> {
    private String message;
    private GenericResult result = GenericResult.allowed();

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
     * Whether the message may go ahead.
     *
     * @return {@code allowed()} unless a plugin denied it
     */
    @Override
    public GenericResult getResult() {
        return result;
    }

    /**
     * {@code GenericResult.denied()} cancels the message: Friends then does nothing more with it.
     *
     * @param result {@code allowed()} or {@code denied()}
     */
    @Override
    public void setResult(GenericResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }
}
