package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {
    @TempDir
    Path dir;
    Storage storage;

    final UUID a = TestSupport.uuid("Alice");
    final UUID b = TestSupport.uuid("Bob");
    final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    boolean mysql() {
        return false;
    }

    @BeforeEach
    void setUp() throws Exception {
        storage = TestSupport.open(dir, mysql());
    }

    @AfterEach
    void tearDown() {
        if (storage != null) storage.close();
    }

    @Test
    void findsPlayersCaseInsensitivelyPreferringTheNewestHolderOfAName() throws Exception {
        storage.savePlayer(a, "Steve", "&a", t0);
        storage.savePlayer(b, "steve", null, t0.plusSeconds(60)); // name reused after a rename
        var found = storage.player("STEVE").orElseThrow();
        assertEquals(b, found.id());
        assertEquals("steve", found.name());
        assertNull(found.prefix());
        assertTrue(storage.player("nobody").isEmpty());
    }

    @Test
    void savingAPlayerKeepsTheirSettings() throws Exception {
        storage.savePlayer(a, "Alice", null, t0);
        storage.setNotifications(a, false);
        storage.setStatus(a, Status.BUSY);
        storage.savePlayer(a, "Alice2", "&b", t0.plusSeconds(5)); // the old INSERT OR REPLACE wiped these

        var loaded = storage.load(a);
        assertFalse(loaded.notifications());
        assertEquals(Status.BUSY, loaded.status());
        assertEquals("Alice2", storage.player("alice2").orElseThrow().name());
    }

    @Test
    void unknownPlayersLoadWithDefaults() throws Exception {
        var loaded = storage.load(a);
        assertTrue(loaded.notifications());
        assertEquals(Status.ONLINE, loaded.status());
        assertEquals(List.of(), loaded.friends());
    }

    @Test
    void friendshipsAreStoredPerDirectionWithPrivateFlags() throws Exception {
        storage.savePlayer(a, "Alice", "&c", t0);
        storage.savePlayer(b, "Bob", null, t0.plusSeconds(30));
        storage.addFriendship(a, b, t0);
        storage.addFriendship(a, b, t0); // idempotent
        storage.setBest(a, b, true);
        storage.setNickname(a, b, "Bobby");

        Friend bobForAlice = storage.load(a).friends().getFirst();
        assertEquals(b, bobForAlice.id());
        assertEquals("Bob", bobForAlice.name());
        assertEquals(t0, bobForAlice.since());
        assertEquals(t0.plusSeconds(30), bobForAlice.lastSeen());
        assertTrue(bobForAlice.best());
        assertEquals("Bobby", bobForAlice.nickname());

        Friend aliceForBob = storage.load(b).friends().getFirst();
        assertEquals("&c", aliceForBob.prefix());
        assertFalse(aliceForBob.best());
        assertNull(aliceForBob.nickname());
        assertEquals(1, storage.countFriends(a));

        storage.setNickname(a, b, null);
        assertNull(storage.load(a).friends().getFirst().nickname());
    }

    @Test
    void removingDeletesBothDirections() throws Exception {
        UUID c = TestSupport.uuid("Carol");
        storage.addFriendship(a, b, t0);
        storage.addFriendship(a, c, t0);
        storage.removeFriendships(a, List.of(b, c));
        assertEquals(0, storage.countFriends(a));
        assertEquals(0, storage.countFriends(b));
        assertEquals(0, storage.countFriends(c));
    }

    @Test
    void touchOnlyBumpsLastSeen() throws Exception {
        storage.savePlayer(a, "Alice", "&c", t0);
        storage.touch(a, t0.plusSeconds(99));
        var row = storage.player("alice").orElseThrow();
        assertEquals(t0.plusSeconds(99), row.lastSeen());
        assertEquals("&c", row.prefix());
    }
}
