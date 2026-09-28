package com.friends.paper;

import javax.sql.DataSource;

import org.bukkit.plugin.java.JavaPlugin;

import com.friends.common.db.DbUtil;
import com.friends.common.service.FriendService;
import com.friends.common.service.MemoryFriendService;

public class FriendsPlugin extends JavaPlugin {
    private FriendService friendService;
    private DataSource dataSource;

    @Override
    public void onEnable() {
        // Ensure default config.yml is present in plugin data folder
        saveDefaultConfig();

        String storageType = getConfig().getString("storage.type", "sqlite").toLowerCase();

        // Determine default prefix for messages
        String defaultPrefix = getConfig().getString("messages.default_prefix", "&aFriend >");
        String shortPrefix = getConfig().getString("messages.short_prefix", "&aF >");
        boolean useShort = getConfig().getBoolean("messages.use_short_prefix", false);
        String chosenPrefix = useShort ? shortPrefix : defaultPrefix;

        if ("mysql".equals(storageType)) {
            // Build properties expected by DbUtil
            java.util.Properties props = new java.util.Properties();
            String host = getConfig().getString("mysql.host", "localhost");
            int port = getConfig().getInt("mysql.port", 3306);
            String db = getConfig().getString("mysql.database", "friends");
            String user = getConfig().getString("mysql.user", "root");
            String pass = getConfig().getString("mysql.password", "");
            boolean useSSL = getConfig().getBoolean("mysql.useSSL", false);
            int poolSize = getConfig().getInt("mysql.poolSize", 10);

            String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=%b&serverTimezone=UTC", host, port, db, useSSL);
            props.setProperty("mysql.url", url);
            props.setProperty("mysql.user", user);
            props.setProperty("mysql.password", pass);
            props.setProperty("db.pool.size", String.valueOf(poolSize));

            try {
                this.dataSource = DbUtil.createDataSource(props);
                getLogger().info("MySQL configured — datasource created successfully");
                this.friendService = new com.friends.common.service.SqlFriendService(this.dataSource, defaultPrefix, shortPrefix, useShort);
            } catch (Exception ex) {
                getLogger().severe("Failed to create MySQL datasource — falling back to sqlite or in-memory: " + ex.getMessage());
                // Try sqlite fallback
                try {
                    java.util.Properties sprops = new java.util.Properties();
                    sprops.setProperty("sqlite.file", getConfig().getString("sqlite.file", "friends.db"));
                    sprops.setProperty("db.pool.size", String.valueOf(getConfig().getInt("sqlite.poolSize", 10)));
                    this.dataSource = DbUtil.createDataSource(sprops);
                    this.friendService = new com.friends.common.service.SqlFriendService(this.dataSource, defaultPrefix, shortPrefix, useShort);
                    getLogger().info("Fell back to SQLite datasource successfully");
                } catch (Exception ex2) {
                    getLogger().severe("Failed to create SQLite datasource as fallback: " + ex2.getMessage());
                    this.friendService = new MemoryFriendService(chosenPrefix);
                }
            }
        } else {
            // SQLite/default fallback
            try {
                java.util.Properties sprops = new java.util.Properties();
                sprops.setProperty("sqlite.file", getConfig().getString("sqlite.file", "friends.db"));
                sprops.setProperty("db.pool.size", String.valueOf(getConfig().getInt("sqlite.poolSize", 10)));
                this.dataSource = DbUtil.createDataSource(sprops);
                this.friendService = new com.friends.common.service.SqlFriendService(this.dataSource, defaultPrefix, shortPrefix, useShort);
                getLogger().info("SQLite configured — datasource created and SQL-backed FriendService enabled");
            } catch (Exception ex) {
                getLogger().severe("Failed to create SQLite datasource — falling back to in-memory storage: " + ex.getMessage());
                this.friendService = new MemoryFriendService(chosenPrefix);
            }
        }

        // Ensure message prefix settings propagate into the service (works for SQL and Memory implementations)
        if (this.friendService != null) {
            this.friendService.setShortPrefix(shortPrefix);
            this.friendService.setUseShortPrefix(useShort);
        }

        // Register command
        this.getCommand("f").setExecutor(new PaperFriendsCommand(friendService, this));
        this.getCommand("friends").setExecutor(new PaperFriendsCommand(friendService, this));

        // Schedule cleanup of expired in-memory friend requests every 1 minute
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (friendService instanceof com.friends.common.service.MemoryFriendService) {
                ((com.friends.common.service.MemoryFriendService) friendService).cleanupAllExpiredRequests();
            }
        }, 20L * 60L, 20L * 60L);

        // Register plugin message listener for cross-server notifications
        getServer().getMessenger().registerIncomingPluginChannel(this, "friends:notify", new FriendsPluginMessageListener(friendService));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "friends:notify");

        getLogger().info("Friends plugin enabled (storage: " + storageType + ")");
    }

    @Override
    public void onDisable() {
        // Close datasource if it supports close (e.g., HikariDataSource implements Closeable)
        if (dataSource instanceof AutoCloseable) {
            try {
                ((AutoCloseable) dataSource).close();
            } catch (Exception e) {
                getLogger().warning("Failed to close DataSource: " + e.getMessage());
            }
        }
        getLogger().info("Friends plugin disabled");
    }

    public FriendService getFriendService() {
        return friendService;
    }

    public DataSource getDataSource() { return dataSource; }
}
