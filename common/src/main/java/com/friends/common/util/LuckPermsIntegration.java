package com.friends.common.util;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LuckPermsIntegration {
    private LuckPermsIntegration() {}

    public static String getPrefix(UUID player) {
        try {
            Class<?> prov = Class.forName("net.luckperms.api.LuckPermsProvider");
            Method get = prov.getMethod("get");
            Object api = get.invoke(null);
            if (api == null) return null;
            Method getUserManager = api.getClass().getMethod("getUserManager");
            Object um = getUserManager.invoke(api);
            if (um == null) return null;
            Method getUser = null;
            try {
                getUser = um.getClass().getMethod("getUser", UUID.class);
            } catch (NoSuchMethodException nsme) {
                try {
                    getUser = um.getClass().getMethod("loadUser", UUID.class);
                } catch (NoSuchMethodException nsme2) {
                    return null;
                }
            }
            Object user = getUser.invoke(um, player);
            if (user == null) return null;
            if (user instanceof CompletableFuture) {
                user = ((CompletableFuture<?>)user).join();
            }
            if (user == null) return null;
            Method getCached = user.getClass().getMethod("getCachedData");
            Object cached = getCached.invoke(user);
            Method getMeta = cached.getClass().getMethod("getMetaData");
            Object meta = getMeta.invoke(cached);
            Method getPrefix = meta.getClass().getMethod("getPrefix");
            Object p = getPrefix.invoke(meta);
            if (p != null) return p.toString();
        } catch (Throwable t) {
            // ignore - luckperms not available or API mismatch
        }
        return null;
    }

    public static String formatDisplay(UUID player, String name) {
        String prefix = getPrefix(player);
        if (prefix == null || prefix.isEmpty()) return name;
        return prefix + name;
    }
}