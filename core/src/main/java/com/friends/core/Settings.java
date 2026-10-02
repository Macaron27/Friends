package com.friends.core;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;

/** The plugin's config.yml, identical on every platform; each reads its YAML through a {@link Source}. */
public record Settings(String storage, String sqliteFile, Mysql mysql, Redis redis, Duration requestExpiry, int maxFriends) {

    public record Mysql(String host, int port, String database, String user, String password, boolean ssl) {}

    public record Redis(boolean enabled, String host, int port, String password, int database, boolean ssl,
                        String proxyId, String namespace) {}

    /** Dotted-path lookups with defaults, backed by the platform's config API. */
    public interface Source {
        String string(String path, String def);

        int number(String path, int def);

        boolean flag(String path, boolean def);
    }

    public static Settings read(Source s) {
        String storage = s.string("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        if (!storage.equals("sqlite") && !storage.equals("mysql")) {
            throw new IllegalArgumentException("storage.type must be sqlite or mysql, not '" + storage + "'");
        }
        return new Settings(storage, s.string("sqlite.file", "friends.db"),
                new Mysql(s.string("mysql.host", "localhost"), s.number("mysql.port", 3306), s.string("mysql.database", "friends"),
                        s.string("mysql.user", "root"), s.string("mysql.password", ""), s.flag("mysql.use-ssl", false)),
                new Redis(s.flag("redis.enabled", false), s.string("redis.host", "localhost"), s.number("redis.port", 6379),
                        s.string("redis.password", ""), s.number("redis.database", 0), s.flag("redis.ssl", false),
                        s.string("redis.proxy-id", ""), s.string("redis.namespace", "friends")),
                Duration.ofMinutes(Math.max(1, s.number("request-expiry-minutes", 5))),
                Math.max(1, s.number("max-friends", 5000)));
    }

    public boolean usesMysql() {
        return storage.equals("mysql");
    }

    public Storage openStorage(Path dataDir) throws SQLException {
        return usesMysql()
                ? Storage.mysql(mysql.host(), mysql.port(), mysql.database(), mysql.user(), mysql.password(), mysql.ssl())
                : Storage.sqlite(dataDir.resolve(sqliteFile));
    }
}
