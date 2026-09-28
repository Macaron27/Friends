package com.friends.paper;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The only class Bukkit loads itself. Compiled for Java 8 so every CraftBukkit's class rewriter (1.13+, whose ASM
 * can't read Java 25 class files before ~1.21) accepts it; the real plugin ({@code PaperFriends}, Java 25) lives in an
 * embedded jar loaded through a child class loader the rewriter never sees. Same trick LuckPerms uses.
 */
public final class FriendsBootstrap extends JavaPlugin {
    private URLClassLoader loader;
    private Object impl;

    @Override
    public void onEnable() {
        try {
            File jar = File.createTempFile("friends-paper-", ".jar");
            jar.deleteOnExit();
            try (InputStream in = getResource("friends-paper-impl.jar")) {
                Files.copy(in, jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            loader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, getClassLoader());
            impl = loader.loadClass("com.friends.paper.PaperFriends").getConstructor(JavaPlugin.class).newInstance(this);
            impl.getClass().getMethod("enable").invoke(impl);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Friends could not start (it needs Java 25)", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (impl != null) impl.getClass().getMethod("disable").invoke(impl);
            if (loader != null) loader.close();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Friends did not stop cleanly", e);
        }
        impl = null;
        loader = null;
    }
}
