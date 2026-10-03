package com.friends.paper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.friends.api.FriendsAPI;
import com.friends.core.ChatSessions;
import com.friends.core.ConfigUpgrade;
import com.friends.core.FriendCommand;
import com.friends.core.FriendsRuntime;
import com.friends.core.LuckPermsPrefix;
import com.friends.core.Network;
import com.friends.core.Platform.Online;
import com.friends.core.Settings;
import com.friends.core.Storage;

import net.md_5.bungee.api.chat.BaseComponent;

/**
 * The Paper plugin proper (standalone servers, 1.8.8 to 26.x in one jar), loaded by {@code FriendsBootstrap}.
 * Built against the 1.8.8 API; chat goes out as BungeeCord components (the one chat API every version has) and all
 * friend logic stays off the server thread.
 */
public final class PaperFriends implements Listener {
    private final JavaPlugin plugin;
    private final Logger log = LoggerFactory.getLogger("Friends");
    // Commands wait on SQL now and then: run them on virtual threads, never on the server thread.
    private final ExecutorService commands = Executors.newVirtualThreadPerTaskExecutor();
    private FriendsRuntime runtime;
    private ChatSessions<Player> sessions;

    public PaperFriends(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        plugin.saveDefaultConfig();
        upgradeConfig();
        Storage storage = null;
        Settings settings;
        try {
            settings = Settings.read(source(plugin.getConfig()));
            if (settings.redis().enabled()) log.warn("Friends: redis (multi-proxy) is for Velocity/BungeeCord; ignored on Paper");
            storage = settings.openStorage(plugin.getDataFolder().toPath());
            Function<UUID, String> prefixes = plugin.getServer().getPluginManager().getPlugin("LuckPerms") != null ? LuckPermsPrefix::of : _ -> null;
            sessions = new ChatSessions<>(new ChatSessions.Adapter<>() {
                @Override public UUID id(Player p) { return p.getUniqueId(); }
                @Override public String name(Player p) { return p.getName(); }
                @Override public String server(Player p) { return null; } // one server: friends see "is online"
                @Override public String prefix(UUID id) { return prefixes.apply(id); }
                @Override public void send(Player p, BaseComponent[] message) { p.spigot().sendMessage(message); }
            });
            runtime = new FriendsRuntime(settings, storage, Network.LOCAL, sessions, new BukkitHooks(plugin.getServer().getPluginManager()), log);
        } catch (SQLException | IllegalArgumentException e) {
            log.error("Friends is disabled: {}", e.getMessage(), e);
            if (storage != null) storage.close();
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return;
        }
        for (var entry : Map.of("friend", "", "fl", "list", "status", "status").entrySet()) {
            Cmd command = new Cmd(entry.getValue().isEmpty() ? null : entry.getValue());
            plugin.getCommand(entry.getKey()).setExecutor(command);
            plugin.getCommand(entry.getKey()).setTabCompleter(command);
        }
        if (settings.privateMessages()) registerMessageCommands();
        // Explicit executors: no reflective @EventHandler scanning (or generated executors) of our classes.
        var events = plugin.getServer().getPluginManager();
        events.registerEvent(PlayerJoinEvent.class, this, EventPriority.MONITOR,
                (_, e) -> { if (e instanceof PlayerJoinEvent join) join(join.getPlayer()); }, plugin);
        events.registerEvent(PlayerQuitEvent.class, this, EventPriority.MONITOR,
                (_, e) -> { if (e instanceof PlayerQuitEvent quit) leave(quit.getPlayer()); }, plugin);
        // Also FriendsAPI.get(). Bukkit drops the service by itself when the plugin disables.
        plugin.getServer().getServicesManager().register(FriendsAPI.class, runtime.api, plugin, ServicePriority.Normal);
        for (Player p : plugin.getServer().getOnlinePlayers()) join(p); // enabled while players are online (/reload)
    }

    public void disable() {
        commands.shutdown();
        if (runtime == null) return;
        for (Player p : plugin.getServer().getOnlinePlayers()) leave(p); // shutdown or /reload: they may still be online
        runtime.close();
    }

    /** Appends the sections new versions added to the server's config.yml (their own settings and comments stay). */
    private void upgradeConfig() {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return;
            List<String> added = ConfigUpgrade.addMissingSections(plugin.getDataFolder().toPath().resolve("config.yml"),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!added.isEmpty()) {
                log.info("Friends: added the new settings {} to config.yml", added);
                plugin.reloadConfig();
            }
        } catch (IOException e) {
            log.warn("Friends: could not add new settings to config.yml ({}); using their defaults", e.getMessage());
        }
    }

    /**
     * /msg and /r go into the command map at enable time, not plugin.yml: so {@code private-messages.enabled: false}
     * leaves them alone entirely, and a plugin that declares them (e.g. Essentials) keeps them; ours then stay
     * reachable as /friends:msg and /friends:r. They still take over vanilla's /msg, /tell and /w.
     */
    private void registerMessageCommands() {
        CommandMap map;
        try { // public on CraftServer since 1.8 (and on Server in newer APIs), but not in the 1.8.8 API we build against
            map = (CommandMap) plugin.getServer().getClass().getMethod("getCommandMap").invoke(plugin.getServer());
        } catch (ReflectiveOperationException | ClassCastException e) {
            log.warn("Friends: no command map on this server ({}); /msg and /r are unavailable", e.toString());
            return;
        }
        register(map, new MessageCommand("msg", "msg", "Send a friend a private message", "/msg <friend> <message>", List.of("tell", "w", "whisper")));
        register(map, new MessageCommand("r", "reply", "Reply to your last private message", "/r <message>", List.of("reply")));
    }

    private void register(CommandMap map, MessageCommand command) {
        if (!map.register("friends", command)) {
            log.warn("Friends: another plugin owns /{}; Friends' is /friends:{} (or set private-messages.enabled: false)",
                    command.getName(), command.getName());
        }
    }

    private void join(Player player) {
        Online online = sessions.join(player);
        runtime.friends.connect(online).thenRun(() -> runtime.friends.greet(online));
    }

    private void leave(Player player) {
        runtime.friends.disconnect(sessions.online(player));
        sessions.quit(player);
    }

    private static Settings.Source source(FileConfiguration config) {
        return new Settings.Source() {
            @Override public String string(String path, String def) { return config.getString(path, def); }
            @Override public int number(String path, int def) { return config.getInt(path, def); }
            @Override public boolean flag(String path, boolean def) { return config.getBoolean(path, def); }
            @Override public List<?> list(String path) { List<?> l = config.getList(path); return l == null ? List.of() : l; }
        };
    }

    /** /msg ("msg") and /r ("reply"), registered at runtime (see {@link #registerMessageCommands}). */
    private final class MessageCommand extends Command {
        private final Cmd delegate;

        MessageCommand(String name, String sub, String description, String usage, List<String> aliases) {
            super(name, description, usage, aliases);
            setPermission("friends.use");
            this.delegate = new Cmd(sub);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return !testPermission(sender) || delegate.onCommand(sender, this, label, args); // true: no usage line on top
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return delegate.onTabComplete(sender, this, alias, args);
        }
    }

    /** /friend, /fl ("list") and /status ("status"); /msg and /r through {@link MessageCommand}. */
    private final class Cmd implements TabExecutor {
        private final String sub;

        Cmd(String sub) {
            this.sub = sub;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            Online online = sessions.online(p);
            commands.execute(() -> runtime.command.execute(online, FriendCommand.withSub(sub, args)));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return sender instanceof Player p
                    ? runtime.command.suggest(sessions.online(p), FriendCommand.withSub(sub, FriendCommand.completing(args)))
                    : List.of();
        }
    }
}
