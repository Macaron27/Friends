package com.friends.common.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Simple SQL-backed FriendService using an injected DataSource. Creates minimal tables if missing.
 * Works with both SQLite and MySQL via generic SQL (no vendor-specific upserts used).
 */
public class SqlFriendService implements FriendService {
    private final DataSource ds;
    private volatile String defaultPrefix;
    private volatile String shortPrefix = "&aF >";
    private volatile boolean useShort = false;

    public SqlFriendService(DataSource ds) throws SQLException {
        this(ds, "&aFriend >", "&aF >", false);
    }

    public SqlFriendService(DataSource ds, String defaultPrefix) throws SQLException {
        this(ds, defaultPrefix, "&aF >", false);
    }

    public SqlFriendService(DataSource ds, String defaultPrefix, String shortPrefix, boolean useShort) throws SQLException {
        this.ds = ds;
        this.defaultPrefix = defaultPrefix == null ? "&aFriend >" : defaultPrefix;
        this.shortPrefix = shortPrefix == null ? "&aF >" : shortPrefix;
        this.useShort = useShort;
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement p = c.prepareStatement("CREATE TABLE IF NOT EXISTS players (uuid CHAR(36) PRIMARY KEY, username TEXT, last_server TEXT, last_seen INTEGER)")) {
                p.execute();
            }
            try (PreparedStatement p = c.prepareStatement("CREATE TABLE IF NOT EXISTS friends (player_uuid CHAR(36), friend_uuid CHAR(36), PRIMARY KEY (player_uuid, friend_uuid))")) {
                p.execute();
            }
            try (PreparedStatement p = c.prepareStatement("CREATE TABLE IF NOT EXISTS requests (receiver_uuid CHAR(36), sender_uuid CHAR(36), created_at INTEGER, PRIMARY KEY (receiver_uuid, sender_uuid))")) {
                p.execute();
            }
            try (PreparedStatement p = c.prepareStatement("CREATE TABLE IF NOT EXISTS settings (player_uuid CHAR(36) PRIMARY KEY, notifications INTEGER DEFAULT 1, request_expiry INTEGER DEFAULT 15, prefix TEXT DEFAULT '&aFriend >')")) {
                p.execute();
            }
        }
    }

    @Override
    public boolean sendRequest(UUID sender, UUID receiver) {
        if (sender.equals(receiver)) return false;
        try (Connection c = ds.getConnection()) {
            // check friendship
            try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM friends WHERE player_uuid=? AND friend_uuid=? LIMIT 1")) {
                p.setString(1, sender.toString());
                p.setString(2, receiver.toString());
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) return false;
                }
            }
            // check existing request
            try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM requests WHERE receiver_uuid=? AND sender_uuid=? LIMIT 1")) {
                p.setString(1, receiver.toString());
                p.setString(2, sender.toString());
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) return false;
                }
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO requests (receiver_uuid,sender_uuid,created_at) VALUES (?,?,?)")) {
                p.setString(1, receiver.toString());
                p.setString(2, sender.toString());
                p.setLong(3, Instant.now().getEpochSecond());
                p.execute();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean acceptRequest(UUID receiver, UUID sender) {
        try (Connection c = ds.getConnection()) {
            // check request exists
            try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM requests WHERE receiver_uuid=? AND sender_uuid=? LIMIT 1")) {
                p.setString(1, receiver.toString());
                p.setString(2, sender.toString());
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return false;
                }
            }
            try (PreparedStatement p = c.prepareStatement("DELETE FROM requests WHERE receiver_uuid=? AND sender_uuid=?")) {
                p.setString(1, receiver.toString());
                p.setString(2, sender.toString());
                p.execute();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO friends (player_uuid, friend_uuid) VALUES (?,?)")) {
                p.setString(1, receiver.toString());
                p.setString(2, sender.toString());
                p.execute();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO friends (player_uuid, friend_uuid) VALUES (?,?)")) {
                p.setString(1, sender.toString());
                p.setString(2, receiver.toString());
                p.execute();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean denyRequest(UUID receiver, UUID sender) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("DELETE FROM requests WHERE receiver_uuid=? AND sender_uuid=?")) {
            p.setString(1, receiver.toString());
            p.setString(2, sender.toString());
            int changed = p.executeUpdate();
            return changed > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean removeFriend(UUID player, UUID friend) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM friends WHERE player_uuid=? AND friend_uuid=?")) {
                p.setString(1, player.toString());
                p.setString(2, friend.toString());
                int removed = p.executeUpdate();
                try (PreparedStatement p2 = c.prepareStatement("DELETE FROM friends WHERE player_uuid=? AND friend_uuid=?")) {
                    p2.setString(1, friend.toString());
                    p2.setString(2, player.toString());
                    p2.executeUpdate();
                }
                return removed > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<UUID> listFriends(UUID player, int page, int pageSize) {
        List<UUID> out = new ArrayList<>();
        int offset = (page-1)*pageSize;
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT friend_uuid FROM friends WHERE player_uuid=? LIMIT ? OFFSET ?")) {
            p.setString(1, player.toString());
            p.setInt(2, pageSize);
            p.setInt(3, offset);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<UUID> listRequests(UUID player, int page, int pageSize) {
        List<UUID> out = new ArrayList<>();
        int offset = (page-1)*pageSize;
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT sender_uuid FROM requests WHERE receiver_uuid=? LIMIT ? OFFSET ?")) {
            p.setString(1, player.toString());
            p.setInt(2, pageSize);
            p.setInt(3, offset);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setRequestExpiry(UUID player, int minutes) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO settings (player_uuid, request_expiry) VALUES (?,?)")) {
                p.setString(1, player.toString());
                p.setInt(2, minutes);
                p.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getRequestExpiry(UUID player) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT request_expiry FROM settings WHERE player_uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
            return 15;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void toggleNotifications(UUID player, boolean enabled) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO settings (player_uuid, notifications) VALUES (?,?)")) {
                p.setString(1, player.toString());
                p.setInt(2, enabled ? 1 : 0);
                p.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean getNotifications(UUID player) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT notifications FROM settings WHERE player_uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getInt(1) != 0;
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setPrefix(UUID player, String prefix) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO settings (player_uuid, prefix) VALUES (?,?)")) {
            p.setString(1, player.toString());
            p.setString(2, prefix);
            p.execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getPrefix(UUID player) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT prefix FROM settings WHERE player_uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
            return useShort ? shortPrefix : defaultPrefix;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setUseShortPrefix(boolean useShort) {
        this.useShort = useShort;
    }

    @Override
    public boolean getUseShortPrefix() {
        return this.useShort;
    }

    @Override
    public void setShortPrefix(String shortPrefix) {
        this.shortPrefix = shortPrefix;
    }

    @Override
    public String getShortPrefix() {
        return this.shortPrefix;
    }

    @Override
    public UUID findUuidForName(String name) {
        if (name == null) return null;
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT uuid FROM players WHERE lower(username)=lower(?) LIMIT 1")) {
            p.setString(1, name);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString(1));
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updatePlayerInfo(UUID player, String username, String server) {
        long now = Instant.now().getEpochSecond();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement p = c.prepareStatement("UPDATE players SET username=?, last_server=?, last_seen=? WHERE uuid=?")) {
                p.setString(1, username);
                p.setString(2, server);
                p.setLong(3, now);
                p.setString(4, player.toString());
                int changed = p.executeUpdate();
                if (changed == 0) {
                    try (PreparedStatement ins = c.prepareStatement("INSERT INTO players (uuid, username, last_server, last_seen) VALUES (?,?,?,?)")) {
                        ins.setString(1, player.toString());
                        ins.setString(2, username);
                        ins.setString(3, server);
                        ins.setLong(4, now);
                        ins.execute();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getLastServer(UUID player) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT last_server FROM players WHERE uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getLastUsername(UUID player) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT username FROM players WHERE uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Instant getLastSeen(UUID player) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT last_seen FROM players WHERE uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    long epoch = rs.getLong(1);
                    if (rs.wasNull()) return null;
                    return Instant.ofEpochSecond(epoch);
                }
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
