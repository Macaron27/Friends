package com.friends.bungee;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.friends.core.ChatSessions;
import com.friends.core.FriendCommand;
import com.friends.core.FriendsRuntime;
import com.friends.core.LuckPermsPrefix;
import com.friends.core.Network;
import com.friends.core.Platform.Online;
import com.friends.core.RedisNetwork;
import com.friends.core.Settings;
import com.friends.core.Storage;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

/** BungeeCord glue: same core, config and multi-proxy (Redis) support as the Velocity plugin. */
public final class FriendsBungee extends Plugin implements Listener {
    private final Logger log = LoggerFactory.getLogger("Friends");
    private FriendsRuntime runtime;
    private ChatSessions<ProxiedPlayer> sessions;

    @Override
    public void onEnable() {
        Storage storage = null;
        try {
            Settings settings = Settings.read(source(loadConfig()));
            storage = settings.openStorage(getDataFolder().toPath());
            Network network = RedisNetwork.open(settings, log);
            Function<UUID, String> prefixes = getProxy().getPluginManager().getPlugin("LuckPerms") != null ? LuckPermsPrefix::of : _ -> null;
            sessions = new ChatSessions<>(new ChatSessions.Adapter<>() {
                @Override public UUID id(ProxiedPlayer p) { return p.getUniqueId(); }
                @Override public String name(ProxiedPlayer p) { return p.getName(); }
                @Override public String server(ProxiedPlayer p) { return p.getServer() == null ? null : p.getServer().getInfo().getName(); }
                @Override public String prefix(UUID id) { return prefixes.apply(id); }
                @Override public void send(ProxiedPlayer p, BaseComponent[] message) { p.sendMessage(message); }
            });
            runtime = new FriendsRuntime(settings, storage, network, sessions, new BungeeHooks(getProxy().getPluginManager()::callEvent), log);
            log.info("Friends enabled (proxy id: {})", network.proxyId());
        } catch (IOException | SQLException | IllegalArgumentException e) {
            log.error("Friends is disabled: {}", e.getMessage(), e);
            if (storage != null) storage.close();
            return;
        }
        var plugins = getProxy().getPluginManager();
        plugins.registerCommand(this, new Cmd("friend", null, "f", "friends"));
        plugins.registerCommand(this, new Cmd("fl", "list"));
        plugins.registerCommand(this, new Cmd("status", "status"));
        plugins.registerListener(this, this);
        for (ProxiedPlayer p : getProxy().getPlayers()) connect(p, () -> {}); // enabled while players are online
    }

    @Override
    public void onDisable() {
        if (runtime == null) return;
        for (ProxiedPlayer p : getProxy().getPlayers()) leave(p);
        runtime.close();
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        if (runtime == null) return;
        event.registerIntent(this); // hold the login until the player's friends are loaded, like Velocity does
        connect(event.getPlayer(), () -> event.completeIntent(this));
    }

    private void connect(ProxiedPlayer player, Runnable then) {
        runtime.friends.connect(sessions.join(player)).whenComplete((_, _) -> then.run());
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        if (runtime == null) return;
        Online online = sessions.online(event.getPlayer());
        runtime.friends.moved(online);
        if (event.getFrom() == null) runtime.friends.greet(online); // first server: the client can show chat now
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        if (runtime != null) leave(event.getPlayer());
    }

    private void leave(ProxiedPlayer player) {
        runtime.friends.disconnect(sessions.online(player));
        sessions.quit(player);
    }

    private Configuration loadConfig() throws IOException {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            Files.createDirectories(getDataFolder().toPath());
            try (InputStream in = getResourceAsStream("config.yml")) {
                Files.copy(Objects.requireNonNull(in, "config.yml missing from the plugin jar"), file.toPath());
            }
        }
        return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
    }

    private static Settings.Source source(Configuration config) {
        return new Settings.Source() {
            @Override public String string(String path, String def) { return config.getString(path, def); }
            @Override public int number(String path, int def) { return config.getInt(path, def); }
            @Override public boolean flag(String path, boolean def) { return config.getBoolean(path, def); }
        };
    }

    /** /friend, /fl ("list") and /status ("status"). Commands run off BungeeCord's network threads. */
    private final class Cmd extends Command implements TabExecutor {
        private final String sub;

        Cmd(String name, String sub, String... aliases) {
            super(name, null, aliases);
            this.sub = sub;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!(sender instanceof ProxiedPlayer p)) {
                sender.sendMessage(new ComponentBuilder("Only players can use this command.").color(ChatColor.RED).create());
                return;
            }
            Online online = sessions.online(p);
            getProxy().getScheduler().runAsync(FriendsBungee.this, () -> runtime.command.execute(online, FriendCommand.withSub(sub, args)));
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            return sender instanceof ProxiedPlayer p
                    ? runtime.command.suggest(sessions.online(p), FriendCommand.withSub(sub, FriendCommand.completing(args)))
                    : List.of();
        }
    }
}
