package com.friends.common;

import java.util.UUID;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

/** Rank prefixes from LuckPerms (same API on every platform). Only touch this class when LuckPerms is loaded. */
public final class LuckPermsPrefix {
    private LuckPermsPrefix() {}

    public static String of(UUID id) {
        User user = LuckPermsProvider.get().getUserManager().getUser(id);
        return user == null ? null : user.getCachedData().getMetaData().getPrefix();
    }
}
