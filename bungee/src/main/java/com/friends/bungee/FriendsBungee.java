package com.friends.bungee;

import com.friends.common.service.FriendService;

import net.md_5.bungee.api.plugin.Plugin;

public class FriendsBungee extends Plugin {
    private FriendService friendService;
    private javax.sql.DataSource dataSource;

    @Override
    public void onEnable() {
        // Ensure a default config.yml exists in the plugin data folder, then parse it
        java.util.Map<String, String> map = new java.util.HashMap<>();
        java.io.File cfg = new java.io.File(getDataFolder(), "config.yml");
        try {
            if (!cfg.exists()) {
                getDataFolder().mkdirs();
                try (java.io.InputStream in = getResourceAsStream("config.yml")) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, cfg.toPath());
                    } else {
                        getLogger().warning("config.yml resource not found in plugin jar; using defaults");
                    }
                }
            }

            try (java.io.BufferedReader r = java.nio.file.Files.newBufferedReader(cfg.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                String currentSection = null;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.endsWith(":")) {
                        currentSection = line.substring(0, line.length() - 1).trim();
                    } else if (line.contains(":")) {
                        int idx = line.indexOf(":");
                        String key = line.substring(0, idx).trim();
                        String val = line.substring(idx + 1).trim();
                        val = val.replaceAll("^\"|\"$", "");
                        if (currentSection != null) key = currentSection + "." + key;
                        map.put(key, val);
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Could not load config.yml: " + e.getMessage());
        }

            String storage = map.getOrDefault("storage.type", "sqlite");
            if ("mysql".equalsIgnoreCase(storage)) {
                java.util.Properties props = new java.util.Properties();
                String host = map.getOrDefault("mysql.host", "localhost");
                int port = Integer.parseInt(map.getOrDefault("mysql.port", "3306"));
                String db = map.getOrDefault("mysql.database", "friends");
                String user = map.getOrDefault("mysql.user", "root");
                String pass = map.getOrDefault("mysql.password", "");
                boolean useSSL = Boolean.parseBoolean(map.getOrDefault("mysql.useSSL", "false"));
                String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=%b&serverTimezone=UTC", host, port, db, useSSL);
                props.setProperty("mysql.url", url);
                props.setProperty("mysql.user", user);
                props.setProperty("mysql.password", pass);
                props.setProperty("db.pool.size", map.getOrDefault("mysql.poolSize", "10"));
                try {
                    this.dataSource = com.friends.common.db.DbUtil.createDataSource(props);
                    getLogger().info("MySQL configured for Bungee (datasource created)");
                } catch (Exception ex) {
                    getLogger().severe("Failed to create MySQL datasource for Bungee, attempting sqlite fallback: " + ex.getMessage());
                    this.dataSource = null;
                }
            }

            // If we don't have a datasource yet, try sqlite fallback (or default to sqlite config)
            if (this.dataSource == null) {
                try {
                    java.util.Properties sprops = new java.util.Properties();
                    sprops.setProperty("sqlite.file", map.getOrDefault("sqlite.file", "friends.db"));
                    sprops.setProperty("db.pool.size", map.getOrDefault("sqlite.poolSize", "10"));
                    this.dataSource = com.friends.common.db.DbUtil.createDataSource(sprops);
                    getLogger().info("SQLite datasource created for Bungee");
                } catch (Exception ex2) {
                    getLogger().warning("Failed to create SQLite datasource for Bungee: " + ex2.getMessage());
                    this.dataSource = null;
                }
            }

        // Determine message prefix settings from config
        String defaultPrefix = map.getOrDefault("messages.default_prefix", "&aFriend >");
        String shortPrefix = map.getOrDefault("messages.short_prefix", "&aF >");
        boolean useShort = Boolean.parseBoolean(map.getOrDefault("messages.use_short_prefix", "false"));
        String chosenPrefix = useShort ? shortPrefix : defaultPrefix;

        if (this.dataSource != null) {
            try {
                this.friendService = new com.friends.common.service.SqlFriendService(this.dataSource, defaultPrefix, shortPrefix, useShort);
                getLogger().info("Using SqlFriendService backed by DataSource");
            } catch (Exception e) {
                getLogger().severe("Failed to initialize SqlFriendService, falling back to in-memory: " + e.getMessage());
                this.friendService = new com.friends.common.service.MemoryFriendService(chosenPrefix, shortPrefix, useShort);
            }
        } else {
            this.friendService = new com.friends.common.service.MemoryFriendService(chosenPrefix, shortPrefix, useShort);
        }

        if (this.friendService != null) {
            this.friendService.setShortPrefix(shortPrefix);
            this.friendService.setUseShortPrefix(useShort);
        }
        // Register commands
        getProxy().getPluginManager().registerCommand(this, new BungeeFriendsCommand(friendService));
        // Provide a dedicated /fl command that lists friends directly (treats /fl like '/f list')
        getProxy().getPluginManager().registerCommand(this, new BungeeFlCommand(friendService));

        // Register listeners
        getProxy().getPluginManager().registerListener(this, new BungeePlayerListener(this, friendService));

        getLogger().info("Friends Bungee plugin enabled (in-memory mode)");
    }

    @Override
    public void onDisable() {
        getLogger().info("Friends Bungee plugin disabled");
    }

    public FriendService getFriendService() {
        return friendService;
    }

    public javax.sql.DataSource getDataSource() { return dataSource; }
}
