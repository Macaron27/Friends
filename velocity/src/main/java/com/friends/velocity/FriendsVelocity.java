package com.friends.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import com.friends.common.FriendCommand;
import com.friends.common.Friends;
import com.friends.common.Platform;
import com.friends.common.Storage;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

@Plugin(id = "friends", name = "Friends", version = "0.2.0",
        description = "Hypixel-style friends system",
        dependencies = @Dependency(id = "luckperms", optional = true))
public final class FriendsVelocity {
    private final ProxyServer proxy;
    private final Logger log;
    private final Path dataDir;
    private ExecutorService db;
    private Storage storage;
    private Friends friends;
    private VelocityPlatform platform;

    @Inject
    public FriendsVelocity(ProxyServer proxy, Logger log, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.log = log;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        ConfigurationNode config;
        try {
            config = loadConfig();
            storage = openStorage(config);
        } catch (IOException | SQLException e) {
            log.error("Friends is disabled: could not load config or open storage", e);
            return;
        }
        // One thread keeps writes ordered and off the proxy's event/command threads.
        db = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("friends-db").daemon().factory());
        Function<UUID, String> prefixes = proxy.getPluginManager().isLoaded("luckperms") ? LuckPermsPrefix::of : _ -> null;
        platform = new VelocityPlatform(proxy, prefixes);
        friends = new Friends(storage, platform, db, Clock.systemUTC(),
                Duration.ofMinutes(config.node("request-expiry-minutes").getLong(5)),
                config.node("max-friends").getInt(5000), log);

        FriendCommand command = new FriendCommand(friends, platform);
        CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("friend").aliases("f", "friends").plugin(this).build(), new Cmd(command, platform, null));
        commands.register(commands.metaBuilder("fl").plugin(this).build(), new Cmd(command, platform, "list"));
        commands.register(commands.metaBuilder("status").plugin(this).build(), new Cmd(command, platform, "status"));
        proxy.getScheduler().buildTask(this, friends::expireRequests).repeat(Duration.ofSeconds(1)).schedule();
    }

    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        return friends == null ? null : EventTask.resumeWhenComplete(friends.connect(platform.online(event.getPlayer())));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (friends != null && event.getPreviousServer() == null) friends.greet(platform.online(event.getPlayer()));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Safe for every login status: it's a no-op unless this exact session loaded a profile.
        if (friends != null) friends.disconnect(platform.online(event.getPlayer()));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) throws InterruptedException {
        if (db == null) return;
        db.shutdown();
        if (!db.awaitTermination(10, TimeUnit.SECONDS)) log.warn("Friends: gave up waiting for pending database writes");
        storage.close();
    }

    private ConfigurationNode loadConfig() throws IOException {
        Path file = dataDir.resolve("config.yml");
        if (Files.notExists(file)) {
            Files.createDirectories(dataDir);
            try (InputStream in = FriendsVelocity.class.getResourceAsStream("/config.yml")) {
                Files.copy(Objects.requireNonNull(in, "config.yml missing from the plugin jar"), file);
            }
        }
        return YamlConfigurationLoader.builder().path(file).build().load();
    }

    private Storage openStorage(ConfigurationNode config) throws SQLException {
        if ("mysql".equalsIgnoreCase(config.node("storage", "type").getString("sqlite"))) {
            ConfigurationNode m = config.node("mysql");
            return Storage.mysql(m.node("host").getString("localhost"), m.node("port").getInt(3306),
                    m.node("database").getString("friends"), m.node("user").getString("root"),
                    m.node("password").getString(""), m.node("use-ssl").getBoolean(false));
        }
        return Storage.sqlite(dataDir.resolve(config.node("sqlite", "file").getString("friends.db")));
    }

    record VelocityPlatform(ProxyServer proxy, Function<UUID, String> prefixes) implements Platform {
        Online online(Player p) {
            return new Online(p.getUniqueId(), p.getUsername(), prefixes.apply(p.getUniqueId()),
                    p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null), p);
        }

        @Override
        public Optional<Online> player(UUID id) {
            return proxy.getPlayer(id).map(this::online);
        }

        @Override
        public Optional<Online> player(String name) {
            return proxy.getPlayer(name).map(this::online);
        }

        @Override
        public Collection<String> onlineNames() {
            return proxy.getAllPlayers().stream().map(Player::getUsername).toList();
        }
    }

    /** Routes /friend, /fl ("list") and /status ("status") into the shared command parser. */
    record Cmd(FriendCommand command, VelocityPlatform platform, String sub) implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (invocation.source() instanceof Player p) {
                command.execute(platform.online(p), args(invocation.arguments()));
            } else {
                invocation.source().sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments().length == 0 ? new String[] {""} : invocation.arguments();
            return invocation.source() instanceof Player p ? command.suggest(platform.online(p), args(args)) : List.of();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            // Allowed unless explicitly denied: Velocity has no permission defaults without a permissions plugin.
            return invocation.source().getPermissionValue("friends.use") != Tristate.FALSE;
        }

        private String[] args(String[] args) {
            if (sub == null) return args;
            String[] out = new String[args.length + 1];
            out[0] = sub;
            System.arraycopy(args, 0, out, 1, args.length);
            return out;
        }
    }

    /** Only touched when LuckPerms is loaded, so its classes are never needed otherwise. */
    static final class LuckPermsPrefix {
        static String of(UUID id) {
            User user = LuckPermsProvider.get().getUserManager().getUser(id);
            return user == null ? null : user.getCachedData().getMetaData().getPrefix();
        }
    }
}
