package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;

class ChatSessionsTest {
    /** Stands in for a platform player object (one per connection). */
    static final class FakePlayer {
        final UUID id;
        final String name;
        String server;
        final List<BaseComponent[]> received = new ArrayList<>();

        FakePlayer(String name) {
            this.id = TestSupport.uuid(name);
            this.name = name;
        }
    }

    final ChatSessions<FakePlayer> sessions = new ChatSessions<>(new ChatSessions.Adapter<>() {
        @Override public UUID id(FakePlayer p) { return p.id; }
        @Override public String name(FakePlayer p) { return p.name; }
        @Override public String server(FakePlayer p) { return p.server; }
        @Override public String prefix(UUID id) { return "&a"; }
        @Override public void send(FakePlayer p, BaseComponent[] message) { p.received.add(message); }
    });

    @Test
    void aSessionKeepsOneAudienceAndReadsTheServerFresh() {
        FakePlayer alice = new FakePlayer("Alice");
        var joined = sessions.join(alice);
        alice.server = "lobby";
        var later = sessions.online(alice);
        assertSame(joined.audience(), later.audience(), "the audience is the session identity");
        assertEquals("lobby", later.server());
        assertEquals("&a", later.prefix());
        assertEquals(later.audience(), sessions.player(alice.id).orElseThrow().audience());
    }

    @Test
    void messagesArriveAsBungeeComponents() {
        FakePlayer alice = new FakePlayer("Alice");
        sessions.join(alice).audience().sendMessage(Component.text("hi"));
        assertEquals("hi", alice.received.getFirst()[0].toPlainText());
    }

    @Test
    void aStaleSessionCannotRemoveTheNewOne() {
        FakePlayer first = new FakePlayer("Alice");
        FakePlayer second = new FakePlayer("Alice"); // same account, new connection
        var old = sessions.join(first);
        var fresh = sessions.join(second);
        assertNotSame(old.audience(), fresh.audience());

        sessions.quit(first);
        assertSame(fresh.audience(), sessions.player(second.id).orElseThrow().audience());
        assertNotSame(old.audience(), sessions.online(first).audience(), "a replaced session no longer owns the audience");

        sessions.quit(second);
        assertTrue(sessions.player(second.id).isEmpty());
    }
}
