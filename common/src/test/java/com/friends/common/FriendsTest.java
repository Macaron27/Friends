package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.friends.common.TestSupport.Harness;
import com.friends.common.TestSupport.Inbox;
import com.friends.common.TestSupport.TestPlayer;

import net.kyori.adventure.text.format.TextDecoration;

class FriendsTest {
    @TempDir
    Path dir;
    Harness h;

    /** MysqlFriendsTest flips this to run the same scenarios against MySQL. */
    boolean mysql() {
        return false;
    }

    Harness harness(int maxFriends) throws Exception {
        return new Harness(TestSupport.open(dir, mysql()), maxFriends);
    }

    @BeforeEach
    void setUp() throws Exception {
        h = harness(5000);
    }

    @AfterEach
    void tearDown() {
        if (h != null) h.close();
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected <" + needle + "> in:\n" + haystack);
    }

    @Test
    void requestAndAcceptMakesBothFriends() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");

        h.run(alice, "add Bob");
        assertContains(alice.last(), "You sent a friend request to Bob! They have 5 minutes to accept it!");
        assertContains(bob.last(), "Friend request from Alice");
        assertEquals(List.of("/f accept Alice", "/f deny Alice"), TestSupport.clicks(bob.lastComponent()));

        h.run(bob, "accept alice"); // names are case-insensitive
        assertContains(bob.last(), "You are now friends with Alice");
        assertContains(alice.last(), "You are now friends with Bob");

        h.run(alice, "list");
        assertContains(alice.last(), "Friends (Page 1 of 1)");
        assertContains(alice.last(), "Bob is in lobby");
    }

    @Test
    void shorthandAndMutualRequestsAutoAccept() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "Bob"); // "/f Bob" == "/f add Bob"
        h.run(bob, "add Alice");
        assertContains(bob.last(), "You are now friends with Alice");
        assertContains(alice.last(), "You are now friends with Bob");
    }

    @Test
    void denyDropsTheRequest() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");
        h.run(bob, "deny Alice");
        assertContains(bob.last(), "Declined Alice's friend request!");
        h.run(bob, "accept Alice");
        assertContains(bob.last(), "You don't have a friend request from 'Alice'!");
    }

    @Test
    void rejectsSelfUnknownDuplicateAndExistingFriends() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");

        h.run(alice, "add alice");
        assertContains(alice.last(), "You can't add yourself as a friend!");
        h.run(alice, "add Nobody");
        assertContains(alice.last(), "Can't find a player by the name of 'Nobody'");
        h.run(alice, "add <red>x"); // MiniMessage in player input is shown literally
        assertContains(alice.last(), "Can't find a player by the name of '<red>x'");

        h.run(alice, "add Bob");
        h.run(alice, "add Bob");
        assertContains(alice.last(), "You've already sent a friend request to Bob!");

        h.run(bob, "accept Alice");
        h.run(alice, "add Bob");
        assertContains(alice.last(), "You're already friends with Bob!");
    }

    @Test
    void requestsExpireAfterFiveMinutes() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");

        h.clock.advance(Duration.ofMinutes(4));
        h.friends.expireRequests();
        h.run(bob, "requests");
        assertContains(bob.last(), "Friend Requests:");

        h.clock.advance(Duration.ofMinutes(1));
        h.friends.expireRequests();
        assertContains(alice.last(), "Your friend request to Bob has expired.");
        assertContains(bob.all(), "The friend request from Alice has expired.");
        h.run(bob, "accept Alice");
        assertContains(bob.last(), "You don't have a friend request from 'Alice'!");
    }

    @Test
    void expiryIsCheckedEvenBeforeTheSweepRuns() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");
        h.clock.advance(Duration.ofMinutes(6));
        h.run(bob, "accept Alice");
        assertContains(bob.last(), "You don't have a friend request from 'Alice'!");
    }

    @Test
    void offlinePlayersCanBeAddedAndSeeTheRequestOnJoin() {
        h.quit(h.join("Bob")); // Bob has played before
        var alice = h.join("Alice");

        h.run(alice, "add bob");
        assertContains(alice.last(), "You sent a friend request to Bob!");

        var bob = h.join("Bob");
        assertContains(bob.all(), "You have 1 pending friend request.");
        h.run(bob, "accept Alice");
        h.run(alice, "list");
        assertContains(alice.last(), "Bob is in lobby");
    }

    @Test
    void acceptingWhileTheSenderIsOfflinePersists() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");
        h.quit(alice);

        h.run(bob, "accept Alice");
        assertContains(bob.last(), "You are now friends with Alice");
        h.run(bob, "list");
        assertContains(bob.last(), "Alice is currently offline");

        alice = h.join("Alice");
        h.run(alice, "list");
        assertContains(alice.last(), "Bob is in lobby");
    }

    @Test
    void friendshipsSurviveAFreshCoreOnTheSameDatabase() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);
        h.run(alice, "best Bob");

        var restarted = new Harness(h.storage, 5000);
        alice = restarted.join("Alice");
        restarted.run(alice, "list");
        assertContains(alice.last(), "Bob is currently offline");
        assertEquals(List.of("Bob"), restarted.friends.friendNames(alice.id()));
    }

    @Test
    void removeIsMutualAndPersisted() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);

        h.run(alice, "remove bob");
        assertContains(alice.last(), "You removed Bob from your friends list!");
        assertEquals(List.of(), h.friends.friendNames(bob.id()));

        h.quit(bob);
        bob = h.join("Bob");
        assertEquals(List.of(), h.friends.friendNames(bob.id()));
        h.run(alice, "remove Bob");
        assertContains(alice.last(), "'Bob' isn't on your friends list!");
    }

    @Test
    void joinAndLeaveNotificationsRespectToggleAndAppearOffline() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);

        h.quit(bob);
        assertEquals("Friend > Bob left.", alice.last());
        bob = h.join("Bob");
        assertEquals("Friend > Bob joined.", alice.last());

        h.run(alice, "notifications");
        assertContains(alice.last(), "Disabled friend join/leave notifications.");
        alice.clear();
        h.quit(bob);
        bob = h.join("Bob");
        assertTrue(alice.inbox().messages.isEmpty());
        h.run(alice, "notifications");

        h.run(bob, "status offline");
        assertContains(bob.last(), "Your status is now Appear Offline.");
        h.run(alice, "list");
        assertContains(alice.last(), "Bob is currently offline");
        alice.clear();
        h.quit(bob);
        assertTrue(alice.inbox().messages.isEmpty());
    }

    @Test
    void statusIsShownToFriendsAndPersisted() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);

        h.run(alice, "status away");
        h.run(bob, "list");
        assertContains(bob.last(), "Alice is away");

        h.quit(alice);
        alice = h.join("Alice");
        h.run(alice, "status");
        assertContains(alice.last(), "Your status is Away.");
        h.run(alice, "status sleeping");
        assertContains(alice.last(), "Usage: /status [online|away|busy|offline]");
    }

    @Test
    void listSortsBestThenOnlineThenOfflineAndPaginates() {
        var alice = h.join("Alice");
        for (int i = 0; i < 25; i++) {
            var f = h.join("F" + (i < 10 ? "0" : "") + i);
            h.befriend(alice, f);
            if (i % 2 == 1) {
                h.clock.advance(Duration.ofMinutes(1));
                h.quit(f); // odd friends go offline; F23 is the most recently seen
            }
        }
        h.run(alice, "best F24");
        h.run(alice, "best F01");

        h.run(alice, "list");
        String page1 = alice.last();
        assertContains(page1, "Friends (Page 1 of 3)");
        String[] lines = page1.split("\n");
        assertEquals("F24 is in lobby", lines[2]);           // best friends first (online ones first)...
        assertEquals("F01 is currently offline", lines[3]); // ...even offline ones
        assertEquals("F00 is in lobby", lines[4]);           // then online by name

        h.run(alice, "list 99"); // clamps to the last page
        String page3 = alice.last();
        assertContains(page3, "Friends (Page 3 of 3)");
        assertEquals(5 + 3, page3.split("\n").length);           // 5 entries + header + 2 lines
        assertContains(page3, "F03 is currently offline");       // least recently seen last
        assertEquals(List.of("/f list 2"), TestSupport.clicks(alice.lastComponent()));

        h.run(alice, "list best");
        assertContains(alice.last(), "Best Friends (Page 1 of 1)");
        assertFalse(alice.last().contains("F00"));
    }

    @Test
    void bestFriendsAreBold() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);
        h.run(alice, "best Bob");
        assertContains(alice.last(), "Bob is now one of your best friends!");
        h.run(alice, "list");
        var entry = alice.lastComponent().children().get(4); // LINE, \n, header, \n, entry
        assertTrue(entry.children().getFirst().hasDecoration(TextDecoration.BOLD));
        h.run(alice, "best Bob");
        assertContains(alice.last(), "Bob is no longer one of your best friends.");
    }

    @Test
    void nicknamesArePrivateValidatedAndPersisted() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);

        h.run(alice, "nickname Bob Bobby B");
        assertContains(alice.last(), "You'll now see Bob as Bobby B.");
        h.run(alice, "list");
        assertContains(alice.last(), "Bobby B is in lobby");
        h.run(bob, "list");
        assertContains(bob.last(), "Alice is in lobby");
        h.quit(bob);
        assertEquals("Friend > Bobby B left.", alice.last());

        h.run(alice, "nickname Bob <red>x");
        assertContains(alice.last(), "Nicknames must be 1-16 letters");
        h.run(alice, "nickname Bob &cRed");
        assertContains(alice.last(), "Nicknames must be 1-16 letters");

        h.quit(alice);
        alice = h.join("Alice");
        h.run(alice, "list");
        assertContains(alice.last(), "Bobby B is currently offline");
        h.run(alice, "nick Bob");
        assertContains(alice.last(), "Cleared your nickname for Bob.");
    }

    @Test
    void removeAllKeepsBestFriendsAndNeedsConfirmation() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        var carol = h.join("Carol");
        var dave = h.join("Dave");
        h.befriend(alice, bob);
        h.befriend(alice, carol);
        h.befriend(alice, dave);
        h.run(alice, "best Dave");

        h.run(alice, "removeall");
        assertContains(alice.last(), "This will remove 2 friends (best friends are kept).");
        assertEquals(List.of("/f removeall confirm"), TestSupport.clicks(alice.lastComponent()));
        assertEquals(3, h.friends.friendNames(alice.id()).size());

        h.run(alice, "removeall confirm");
        assertContains(alice.last(), "Removed 2 friends");
        assertEquals(List.of("Dave"), h.friends.friendNames(alice.id()));
        assertEquals(List.of(), h.friends.friendNames(bob.id()));

        h.quit(carol);
        carol = h.join("Carol");
        assertEquals(List.of(), h.friends.friendNames(carol.id()));
    }

    @Test
    void friendLimitIsEnforcedOnBothSides() throws Exception {
        if (h != null) h.close();
        h = harness(1);
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        var carol = h.join("Carol");
        h.befriend(alice, bob);

        h.run(alice, "add Carol");
        assertContains(alice.last(), "You can't have more than 1 friends!");
        h.run(carol, "add Alice");
        assertContains(carol.last(), "Alice has reached the maximum number of friends!");
    }

    @Test
    void rankPrefixesAreRenderedFromLegacyCodes() {
        var alice = h.join("Alice", "&b[MVP&c+&b] ");
        var bob = h.join("Bob");
        h.run(alice, "add Bob");
        assertContains(bob.last(), "Friend request from [MVP+] Alice");
    }

    @Test
    void anOldSessionDisconnectingDoesNotDropTheNewOne() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);

        // kick-existing-players: the new session loads before the old one's disconnect fires
        var inbox = new Inbox();
        var newSession = new TestPlayer(new Platform.Online(alice.id(), "Alice", null, "lobby", inbox), inbox);
        h.platform.online.put(alice.id(), newSession.online()); // the proxy now maps Alice to the new session
        h.friends.connect(newSession.online()).join();
        h.friends.disconnect(alice.online());

        h.run(newSession, "list");
        assertContains(newSession.last(), "Bob is in lobby");
    }

    @Test
    void aPlayerWhoLeavesWhileLoadingIsNotLeftOnline() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.befriend(alice, bob);
        h.quit(bob);

        // Bob's connect is still loading when he disconnects: the proxy no longer knows him when it finishes.
        var inbox = new Inbox();
        var ghost = new Platform.Online(bob.id(), "Bob", null, null, inbox);
        h.friends.disconnect(ghost);
        h.friends.connect(ghost).join();

        h.run(alice, "list");
        assertContains(alice.last(), "Bob is currently offline");
    }

    @Test
    void helpAndUsage() {
        var alice = h.join("Alice");
        h.run(alice, "");
        assertContains(alice.last(), "Friend Commands:");
        h.run(alice, "add");
        assertContains(alice.last(), "Usage: /f add <player>");
        h.run(alice, "list two");
        assertContains(alice.last(), "Usage: /f list [best] [page]");
        h.run(alice, "list");
        assertContains(alice.last(), "You don't have any friends yet!");
        h.run(alice, "requests");
        assertContains(alice.last(), "You don't have any pending friend requests.");
    }

    @Test
    void tabCompletion() {
        var alice = h.join("Alice");
        var bob = h.join("Bob");
        h.join("Carol");

        assertTrue(h.suggest(alice, "ad").contains("add"));
        assertEquals(List.of("best", "Bob"), h.suggest(alice, "b"));
        assertEquals(List.of("Bob", "Carol"), h.suggest(alice, "add", ""));

        h.run(bob, "add Alice");
        assertEquals(List.of("Bob"), h.suggest(alice, "accept", ""));
        h.run(alice, "accept Bob");
        assertEquals(List.of("Carol"), h.suggest(alice, "add", ""));
        assertEquals(List.of("Bob"), h.suggest(alice, "remove", "b"));
        assertEquals(List.of("best"), h.suggest(alice, "list", ""));
    }

    @Test
    void databaseFailuresReplyWithAnErrorInsteadOfSilence() {
        var alice = h.join("Alice");
        h.storage.close();
        h.run(alice, "add Ghost"); // offline lookup needs the database
        assertContains(alice.last(), "Something went wrong, please try again later.");
    }
}
