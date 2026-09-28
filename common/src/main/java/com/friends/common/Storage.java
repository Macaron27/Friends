package com.friends.common;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Blocking JDBC storage for SQLite or MySQL/MariaDB. Friendships are stored once per direction so each side
 * keeps its own best/nickname flags and a player's list is a single primary-key range scan.
 */
public final class Storage implements AutoCloseable {

    public record PlayerRow(UUID id, String name, String prefix, Instant lastSeen) {}

    public record Loaded(boolean notifications, Status status, List<Friend> friends) {}

    private final HikariDataSource ds;
    private final boolean mysql;

    private Storage(HikariConfig config, boolean mysql) throws SQLException {
        // ponytail: pool of 1-2, all calls come from one ordered DB thread in Friends.
        this.ds = new HikariDataSource(config);
        this.mysql = mysql;
        createTables();
    }

    public static Storage sqlite(Path file) throws SQLException {
        HikariConfig c = new HikariConfig();
        c.setPoolName("friends-sqlite");
        c.setDriverClassName("org.sqlite.JDBC");
        c.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        c.setMaximumPoolSize(1);
        c.setConnectionInitSql("PRAGMA journal_mode=WAL");
        return new Storage(c, false);
    }

    public static Storage mysql(String host, int port, String database, String user, String password, boolean ssl)
            throws SQLException {
        HikariConfig c = new HikariConfig();
        c.setPoolName("friends-mysql");
        c.setDriverClassName("com.mysql.cj.jdbc.Driver");
        c.setJdbcUrl("jdbc:mysql://%s:%d/%s?sslMode=%s&rewriteBatchedStatements=true"
                .formatted(host, port, database, ssl ? "REQUIRED" : "DISABLED"));
        c.setUsername(user);
        c.setPassword(password);
        c.setMaximumPoolSize(2);
        return new Storage(c, true);
    }

    private void createTables() throws SQLException {
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            // MySQL has no CREATE INDEX IF NOT EXISTS, SQLite has no inline INDEX.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS friends_players (
                      uuid CHAR(36) NOT NULL PRIMARY KEY,
                      name VARCHAR(16) NOT NULL,
                      name_lower VARCHAR(16) NOT NULL,
                      prefix TEXT,
                      last_seen BIGINT NOT NULL,
                      notifications BOOLEAN NOT NULL DEFAULT TRUE,
                      status VARCHAR(16) NOT NULL DEFAULT 'ONLINE'%s
                    )""".formatted(mysql ? ",\n  INDEX idx_friends_players_name (name_lower)" : ""));
            if (!mysql) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_friends_players_name ON friends_players (name_lower)");
            }
            st.execute("""
                    CREATE TABLE IF NOT EXISTS friends_friendships (
                      player CHAR(36) NOT NULL,
                      friend CHAR(36) NOT NULL,
                      since BIGINT NOT NULL,
                      best BOOLEAN NOT NULL DEFAULT FALSE,
                      nickname VARCHAR(16),
                      PRIMARY KEY (player, friend)
                    )""");
        }
    }

    /** Upserts name/prefix and bumps last_seen; settings columns are left alone. */
    public void savePlayer(UUID id, String name, String prefix, Instant seen) throws SQLException {
        String sql = mysql
                ? """
                  INSERT INTO friends_players (uuid, name, name_lower, prefix, last_seen) VALUES (?, ?, ?, ?, ?)
                  ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower),
                    prefix = VALUES(prefix), last_seen = VALUES(last_seen)"""
                : """
                  INSERT INTO friends_players (uuid, name, name_lower, prefix, last_seen) VALUES (?, ?, ?, ?, ?)
                  ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower,
                    prefix = excluded.prefix, last_seen = excluded.last_seen""";
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, id.toString());
            p.setString(2, name);
            p.setString(3, name.toLowerCase(Locale.ROOT));
            p.setString(4, prefix);
            p.setLong(5, seen.toEpochMilli());
            p.executeUpdate();
        }
    }

    public void touch(UUID id, Instant seen) throws SQLException {
        update("UPDATE friends_players SET last_seen = ? WHERE uuid = ?", seen.toEpochMilli(), id.toString());
    }

    /** Most recent holder of a name (names can be reused after a rename). */
    public Optional<PlayerRow> player(String name) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT uuid, name, prefix, last_seen FROM friends_players WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
            p.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = p.executeQuery()) {
                return rs.next()
                        ? Optional.of(new PlayerRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                                Instant.ofEpochMilli(rs.getLong(4))))
                        : Optional.empty();
            }
        }
    }

    /** Settings plus the full friend list with each friend's cached name/prefix/last-seen, in two queries. */
    public Loaded load(UUID id) throws SQLException {
        try (Connection c = ds.getConnection()) {
            boolean notifications = true;
            Status status = Status.ONLINE;
            try (PreparedStatement p = c.prepareStatement("SELECT notifications, status FROM friends_players WHERE uuid = ?")) {
                p.setString(1, id.toString());
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) {
                        notifications = rs.getBoolean(1);
                        status = Status.valueOf(rs.getString(2));
                    }
                }
            }
            List<Friend> friends = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("""
                    SELECT f.friend, f.since, f.best, f.nickname, p.name, p.prefix, p.last_seen
                    FROM friends_friendships f LEFT JOIN friends_players p ON p.uuid = f.friend
                    WHERE f.player = ?""")) {
                p.setString(1, id.toString());
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        String friend = rs.getString(1);
                        String name = rs.getString(5);
                        friends.add(new Friend(UUID.fromString(friend), name != null ? name : friend,
                                rs.getString(6), Instant.ofEpochMilli(rs.getLong(2)), rs.getBoolean(3),
                                rs.getString(4), Instant.ofEpochMilli(rs.getLong(7))));
                    }
                }
            }
            return new Loaded(notifications, status, friends);
        }
    }

    public int countFriends(UUID id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM friends_friendships WHERE player = ?")) {
            p.setString(1, id.toString());
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void addFriendship(UUID a, UUID b, Instant since) throws SQLException {
        String sql = (mysql ? "INSERT IGNORE" : "INSERT OR IGNORE")
                + " INTO friends_friendships (player, friend, since) VALUES (?, ?, ?)";
        inTransaction(c -> {
            try (PreparedStatement p = c.prepareStatement(sql)) {
                for (UUID[] pair : new UUID[][] {{a, b}, {b, a}}) {
                    p.setString(1, pair[0].toString());
                    p.setString(2, pair[1].toString());
                    p.setLong(3, since.toEpochMilli());
                    p.addBatch();
                }
                p.executeBatch();
            }
        });
    }

    /** Removes both directions of each friendship in one batch. */
    public void removeFriendships(UUID player, Collection<UUID> friends) throws SQLException {
        inTransaction(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "DELETE FROM friends_friendships WHERE (player = ? AND friend = ?) OR (player = ? AND friend = ?)")) {
                for (UUID friend : friends) {
                    p.setString(1, player.toString());
                    p.setString(2, friend.toString());
                    p.setString(3, friend.toString());
                    p.setString(4, player.toString());
                    p.addBatch();
                }
                p.executeBatch();
            }
        });
    }

    public void setBest(UUID player, UUID friend, boolean best) throws SQLException {
        update("UPDATE friends_friendships SET best = ? WHERE player = ? AND friend = ?", best, player.toString(), friend.toString());
    }

    public void setNickname(UUID player, UUID friend, String nickname) throws SQLException {
        update("UPDATE friends_friendships SET nickname = ? WHERE player = ? AND friend = ?", nickname, player.toString(), friend.toString());
    }

    public void setNotifications(UUID id, boolean enabled) throws SQLException {
        update("UPDATE friends_players SET notifications = ? WHERE uuid = ?", enabled, id.toString());
    }

    public void setStatus(UUID id, Status status) throws SQLException {
        update("UPDATE friends_players SET status = ? WHERE uuid = ?", status.name(), id.toString());
    }

    private void update(String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) p.setNull(i + 1, Types.VARCHAR);
                else p.setObject(i + 1, args[i]);
            }
            p.executeUpdate();
        }
    }

    private interface SqlWork {
        void run(Connection c) throws SQLException;
    }

    private void inTransaction(SqlWork work) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Override
    public void close() {
        ds.close();
    }
}
