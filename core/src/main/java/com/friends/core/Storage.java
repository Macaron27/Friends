package com.friends.core;

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

import com.friends.api.Status;
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

    /**
     * SQL here must run on SQLite 3.7.2 (the driver Paper 1.8.8 bundles, which Bukkit loads before ours): no UPSERT,
     * no TRUE/FALSE literals, no JDBC4 isValid().
     */
    public static Storage sqlite(Path file) throws SQLException {
        HikariConfig c = new HikariConfig();
        c.setPoolName("friends-sqlite");
        c.setDriverClassName("org.sqlite.JDBC");
        c.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        c.setMaximumPoolSize(1);
        c.setConnectionTestQuery("SELECT 1");
        // WAL + synchronous=NORMAL: one fsync per checkpoint instead of per commit, still crash-safe for the database.
        // (journal_mode returns a row, which old drivers reject as a connection property, hence the init SQL.)
        c.setConnectionInitSql("PRAGMA journal_mode=WAL");
        c.addDataSourceProperty("synchronous", "NORMAL");
        try {
            return new Storage(c, false);
        } catch (RuntimeException e) { // Hikari's "Error opening connection" hides the useful part
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            throw new SQLException("SQLite could not open " + file + " (" + root.getMessage()
                    + "). On old servers the bundled SQLite driver may not support this OS/CPU; use storage.type: mysql", e);
        }
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
        // HikariCP's recommended MySQL settings (statement/metadata caching), minus server-side prepared statements.
        c.addDataSourceProperty("cachePrepStmts", "true");
        c.addDataSourceProperty("prepStmtCacheSize", "250");
        c.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        c.addDataSourceProperty("useLocalSessionState", "true");
        c.addDataSourceProperty("cacheResultSetMetadata", "true");
        c.addDataSourceProperty("cacheServerConfiguration", "true");
        c.addDataSourceProperty("maintainTimeStats", "false");
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
                      notifications BOOLEAN NOT NULL DEFAULT 1,
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
                      best BOOLEAN NOT NULL DEFAULT 0,
                      nickname VARCHAR(16),
                      PRIMARY KEY (player, friend)
                    )""");
        }
    }

    /** Upserts name/prefix and bumps last_seen; settings columns are left alone. */
    public void savePlayer(UUID id, String name, String prefix, Instant seen) throws SQLException {
        if (mysql) {
            try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("""
                    INSERT INTO friends_players (uuid, name, name_lower, prefix, last_seen) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower),
                      prefix = VALUES(prefix), last_seen = VALUES(last_seen)""")) {
                bindPlayer(p, id, name, prefix, seen);
                p.executeUpdate();
            }
            return;
        }
        // SQLite < 3.24 has no UPSERT: update, and insert if nothing was there (one DB thread, so no race).
        inTransaction(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE friends_players SET name = ?, name_lower = ?, prefix = ?, last_seen = ? WHERE uuid = ?")) {
                p.setString(1, name);
                p.setString(2, name.toLowerCase(Locale.ROOT));
                p.setString(3, prefix);
                p.setLong(4, seen.toEpochMilli());
                p.setString(5, id.toString());
                if (p.executeUpdate() > 0) return;
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO friends_players (uuid, name, name_lower, prefix, last_seen) VALUES (?, ?, ?, ?, ?)")) {
                bindPlayer(p, id, name, prefix, seen);
                p.executeUpdate();
            }
        });
    }

    private static void bindPlayer(PreparedStatement p, UUID id, String name, String prefix, Instant seen) throws SQLException {
        p.setString(1, id.toString());
        p.setString(2, name);
        p.setString(3, name.toLowerCase(Locale.ROOT));
        p.setString(4, prefix);
        p.setLong(5, seen.toEpochMilli());
    }

    public void touch(UUID id, Instant seen) throws SQLException {
        update("UPDATE friends_players SET last_seen = ? WHERE uuid = ?", seen.toEpochMilli(), id.toString());
    }

    /** Most recent holder of a name (names can be reused after a rename). */
    public Optional<PlayerRow> player(String name) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT uuid, name, prefix, last_seen FROM friends_players WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
            p.setString(1, name.toLowerCase(Locale.ROOT));
            return playerRow(p);
        }
    }

    public Optional<PlayerRow> player(UUID id) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT uuid, name, prefix, last_seen FROM friends_players WHERE uuid = ?")) {
            p.setString(1, id.toString());
            return playerRow(p);
        }
    }

    private static Optional<PlayerRow> playerRow(PreparedStatement p) throws SQLException {
        try (ResultSet rs = p.executeQuery()) {
            return rs.next()
                    ? Optional.of(new PlayerRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                            Instant.ofEpochMilli(rs.getLong(4))))
                    : Optional.empty();
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
                switch (args[i]) { // typed setters: old sqlite-jdbc's setObject doesn't know Boolean
                    case null -> p.setNull(i + 1, Types.VARCHAR);
                    case Boolean b -> p.setBoolean(i + 1, b);
                    case Long l -> p.setLong(i + 1, l);
                    case String s -> p.setString(i + 1, s);
                    default -> p.setObject(i + 1, args[i]);
                }
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
