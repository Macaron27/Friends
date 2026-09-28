package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SettingsTest {
    static Settings.Source source(Map<String, Object> values) {
        return new Settings.Source() {
            @Override public String string(String path, String def) { return (String) values.getOrDefault(path, def); }
            @Override public int number(String path, int def) { return (int) values.getOrDefault(path, def); }
            @Override public boolean flag(String path, boolean def) { return (boolean) values.getOrDefault(path, def); }
        };
    }

    @Test
    void defaultsMatchTheShippedConfig() {
        Settings s = Settings.read(source(Map.of()));
        assertEquals("sqlite", s.storage());
        assertEquals("friends.db", s.sqliteFile());
        assertFalse(s.usesMysql());
        assertFalse(s.redis().enabled());
        assertEquals("friends", s.redis().namespace());
        assertEquals(Duration.ofMinutes(5), s.requestExpiry());
        assertEquals(5000, s.maxFriends());
    }

    @Test
    void readsEverySection() {
        Settings s = Settings.read(source(Map.of("storage.type", "MySQL", "mysql.host", "db", "mysql.port", 3307,
                "redis.enabled", true, "redis.proxy-id", "p1", "request-expiry-minutes", 2, "max-friends", 100)));
        assertTrue(s.usesMysql());
        assertEquals("db", s.mysql().host());
        assertEquals(3307, s.mysql().port());
        assertTrue(s.redis().enabled());
        assertEquals("p1", s.redis().proxyId());
        assertEquals(Duration.ofMinutes(2), s.requestExpiry());
        assertEquals(100, s.maxFriends());
    }

    @Test
    void rejectsUnknownStorageAndClampsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> Settings.read(source(Map.of("storage.type", "postgres"))));
        Settings s = Settings.read(source(Map.of("request-expiry-minutes", 0, "max-friends", -5)));
        assertEquals(Duration.ofMinutes(1), s.requestExpiry());
        assertEquals(1, s.maxFriends());
    }

    @Test
    void redisNeedsMysql() {
        Settings s = Settings.read(source(Map.of("redis.enabled", true)));
        assertThrows(java.io.IOException.class, () -> RedisNetwork.open(s, org.slf4j.LoggerFactory.getLogger("t")));
        assertEquals(Network.LOCAL, uncheckedOpen(Settings.read(source(Map.of()))));
    }

    private static Network uncheckedOpen(Settings s) {
        try {
            return RedisNetwork.open(s, org.slf4j.LoggerFactory.getLogger("t"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
