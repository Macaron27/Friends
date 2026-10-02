package com.friends.core;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;

/**
 * {@link Platform} for BungeeCord and Paper: tracks connected players (our own concurrent map, so async threads never
 * touch the server's player list) and gives each session one {@link Audience} that sends BungeeCord components.
 * That audience is the session identity the core relies on, so it must stay the same object for the whole session.
 */
public final class ChatSessions<P> implements Platform {

    /** How to read and message a platform player {@code P}. */
    public interface Adapter<P> {
        UUID id(P player);

        String name(P player);

        /** Current backend server, or null. */
        String server(P player);

        String prefix(UUID id);

        void send(P player, BaseComponent[] message);
    }

    private record Session<P>(P player, Audience audience) {}

    private final Adapter<P> adapter;
    private final Map<UUID, Session<P>> sessions = new ConcurrentHashMap<>();

    public ChatSessions(Adapter<P> adapter) {
        this.adapter = adapter;
    }

    /** Call when the player connects; returns their session. */
    public Online join(P player) {
        Audience audience = new Audience() {
            @Override
            public void sendMessage(Component message) {
                adapter.send(player, BungeeChat.convert(message));
            }
        };
        sessions.put(adapter.id(player), new Session<>(player, audience));
        return online(player);
    }

    /** The player's session as the core sees it (server and prefix are read fresh each time). */
    public Online online(P player) {
        UUID id = adapter.id(player);
        Session<P> s = sessions.get(id);
        Audience audience = s != null && s.player() == player ? s.audience() : Audience.empty(); // not joined: never matches
        return new Online(id, adapter.name(player), adapter.prefix(id), adapter.server(player), audience);
    }

    /** Call after {@code friends.disconnect(online(player))}. Only forgets this exact session. */
    public void quit(P player) {
        UUID id = adapter.id(player);
        sessions.computeIfPresent(id, (_, s) -> s.player() == player ? null : s);
    }

    @Override
    public Optional<Online> player(UUID id) {
        Session<P> s = sessions.get(id);
        return s == null ? Optional.empty() : Optional.of(online(s.player()));
    }
}
