package com.friends.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import com.friends.common.FriendCommand;
import com.friends.common.FriendsRuntime;
import com.friends.common.LuckPermsPrefix;
import com.friends.common.Network;
import com.friends.common.Platform;
import com.friends.common.RedisNetwork;
import com.friends.common.Settings;
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

@Plugin(id = "friends", name = "Friends", version = "0.5.0",
        description = "Hypixel-style friends system",
        dependencies = @Dependency(id = "luckperms", optional = true))
public final class FriendsVelocity {
    private final ProxyServer proxy;
    private final Logger log;
    private final Path dataDir;
    // Commands may wait on SQL and on plugins' event listeners: run them on virtual threads, not Velocity's.
    private final ExecutorService commandThreads = Executors.newVirtualThreadPerTaskExecutor();
    private FriendsRuntime runtime;
    private VelocityPlatform platform;

    @Inject
    public FriendsVelocity(ProxyServer proxy, Logger log, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.log = log;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        Storage storage = null;
        try {
            Settings settings = Settings.read(source(loadConfig()));
            storage = settings.openStorage(dataDir);
            Network network = RedisNetwork.open(settings, log);
            Function<UUID, String> prefixes = proxy.getPluginManager().isLoaded("luckperms") ? LuckPermsPrefix::of : _ -> null;
            platform = new VelocityPlatform(proxy, prefixes);
            runtime = new FriendsRuntime(settings, storage, network, platform, new VelocityHooks(proxy.getEventManager()::fire), log);
            log.info("Friends enabled (proxy id: {})", network.proxyId());
        } catch (IOException | SQLException | IllegalArgumentException e) {
            log.error("Friends is disabled: {}", e.getMessage(), e);
            if (storage != null) storage.close();
            return;
        }
        CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("friend").aliases("f", "friends").plugin(this).build(), new Cmd(runtime.command, platform, null, commandThreads));
        commands.register(commands.metaBuilder("fl").plugin(this).build(), new Cmd(runtime.command, platform, "list", commandThreads));
        commands.register(commands.metaBuilder("status").plugin(this).build(), new Cmd(runtime.command, platform, "status", commandThreads));
        proxy.getScheduler().buildTask(this, runtime.friends::expireRequests).repeat(Duration.ofSeconds(1)).schedule();
    }

    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        return runtime == null ? null : EventTask.resumeWhenComplete(runtime.friends.connect(platform.online(event.getPlayer())));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (runtime == null) return;
        Platform.Online online = platform.online(event.getPlayer());
        runtime.friends.moved(online);
        if (event.getPreviousServer() == null) runtime.friends.greet(online);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Safe for every login status: it's a no-op unless this exact session loaded a profile.
        if (runtime != null) runtime.friends.disconnect(platform.online(event.getPlayer()));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        commandThreads.shutdown();
        if (runtime != null) runtime.close(); // players are already disconnected
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

    private static Settings.Source source(ConfigurationNode root) {
        return new Settings.Source() {
            private ConfigurationNode node(String path) {
                return root.node((Object[]) path.split("\\."));
            }

            @Override public String string(String path, String def) { return node(path).getString(def); }
            @Override public int number(String path, int def) { return node(path).getInt(def); }
            @Override public boolean flag(String path, boolean def) { return node(path).getBoolean(def); }
        };
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
    }

    /** Routes /friend, /fl ("list") and /status ("status") into the shared command parser. */
    record Cmd(FriendCommand command, VelocityPlatform platform, String sub, Executor commands) implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (invocation.source() instanceof Player p) {
                Platform.Online online = platform.online(p);
                commands.execute(() -> command.execute(online, FriendCommand.withSub(sub, invocation.arguments())));
            } else {
                invocation.source().sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return invocation.source() instanceof Player p
                    ? command.suggest(platform.online(p), FriendCommand.withSub(sub, FriendCommand.completing(invocation.arguments())))
                    : List.of();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            // Allowed unless explicitly denied: Velocity has no permission defaults without a permissions plugin.
            return invocation.source().getPermissionValue("friends.use") != Tristate.FALSE;
        }
    }
}
