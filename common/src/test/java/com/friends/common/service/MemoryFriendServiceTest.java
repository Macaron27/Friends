package com.friends.common.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MemoryFriendServiceTest {
    @Test
    void basicRequestAcceptFlow() {
        MemoryFriendService svc = new MemoryFriendService();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertTrue(svc.sendRequest(a, b));
        List<UUID> reqs = svc.listRequests(b, 1, 10);
        assertEquals(1, reqs.size());
        assertEquals(a, reqs.get(0));

        assertTrue(svc.acceptRequest(b, a));
        List<UUID> friendsA = svc.listFriends(a, 1, 10);
        List<UUID> friendsB = svc.listFriends(b, 1, 10);
        assertTrue(friendsA.contains(b));
        assertTrue(friendsB.contains(a));
    }

    @Test
    void denyRequest() {
        MemoryFriendService svc = new MemoryFriendService();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertTrue(svc.sendRequest(a, b));
        assertTrue(svc.denyRequest(b, a));
        assertTrue(svc.listRequests(b, 1, 10).isEmpty());
    }
}
