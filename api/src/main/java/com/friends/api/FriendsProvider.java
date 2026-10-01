package com.friends.api;

/** Holds the running {@link FriendsAPI}. Only Friends itself calls this; other plugins use {@link FriendsAPI#get()}. */
public final class FriendsProvider {
    static volatile FriendsAPI instance;

    private FriendsProvider() {}

    /**
     * Makes {@code api} the one {@link FriendsAPI#get()} returns. Called by Friends when it enables.
     *
     * @param api the running API
     */
    public static synchronized void register(FriendsAPI api) {
        instance = api;
    }

    /**
     * Forgets {@code api} if it is the registered one, so a disabled Friends (and its class loader) isn't kept alive.
     * Called by Friends when it disables.
     *
     * @param api the API being shut down
     */
    public static synchronized void unregister(FriendsAPI api) {
        if (instance == api) instance = null;
    }
}
