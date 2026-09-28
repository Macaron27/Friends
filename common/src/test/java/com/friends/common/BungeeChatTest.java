package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.chat.ComponentSerializer;

/** Runs on the newest bungeecord-chat ({@code test}) and on Spigot 1.8.8's ({@code testChat1_8}). */
class BungeeChatTest {

    static List<BaseComponent> flatten(BaseComponent c) {
        List<BaseComponent> out = new ArrayList<>();
        out.add(c);
        if (c.getExtra() != null) c.getExtra().forEach(child -> out.addAll(flatten(child)));
        return out;
    }

    static List<String> commands(BaseComponent c) {
        return flatten(c).stream().filter(x -> x.getClickEvent() != null && x.getClickEvent().getAction() == ClickEvent.Action.RUN_COMMAND)
                .map(x -> x.getClickEvent().getValue()).distinct().toList();
    }

    @Test
    void textIsIdenticalToWhatAdventureRenders() {
        for (Component message : List.of(Messages.help(), Messages.received(Messages.name("&b[MVP&c+&b] ", "Alice"), "Alice"),
                Messages.removeAllConfirm(3), Messages.statusMenu(Status.AWAY), Messages.joined(Component.text("Bob")))) {
            assertEquals(TestSupport.plain(message), BungeeChat.convert(message)[0].toPlainText());
        }
    }

    @Test
    void clickAndHoverEventsSurvive() {
        BaseComponent request = BungeeChat.convert(Messages.received(Messages.name(null, "Alice"), "Alice"))[0];
        assertEquals(List.of("/f accept Alice", "/f deny Alice"), commands(request));
        assertTrue(flatten(request).stream().anyMatch(x -> x.getHoverEvent() != null), "hover text kept");

        String json = ComponentSerializer.toString(request);
        assertTrue(json.contains("/f accept Alice") && json.contains("run_command"), json);
    }

    @Test
    void coloursDecorationsAndRankPrefixesMap() {
        BaseComponent line = BungeeChat.convert(Messages.LINE)[0];
        assertEquals(ChatColor.BLUE, line.getColor());
        assertTrue(line.isStrikethrough());

        Friend best = new Friend(UUID.randomUUID(), "Bob", null, Instant.EPOCH, true, null, Instant.EPOCH);
        BaseComponent entry = BungeeChat.convert(Messages.list(List.of(new Messages.Entry(best, Status.ONLINE, "lobby")), 1, 1, false, Instant.EPOCH))[0];
        assertTrue(flatten(entry).stream().anyMatch(x -> "Bob".equals(textOf(x)) && x.isBold()), "best friends are bold");

        String legacy = BungeeChat.convert(Messages.name("&b[MVP&c+&b] ", "Alice"))[0].toLegacyText();
        assertTrue(legacy.contains("§b[MVP") && legacy.contains("§c+") && legacy.contains("Alice"), legacy);
    }

    @Test
    void hexColoursUseTheRuntimeWhenItCanElseTheNearestNamedColour() {
        TextColor hex = TextColor.color(0x3366ff);
        ChatColor mapped = BungeeChat.color(hex);
        boolean runtimeHasHex;
        try {
            ChatColor.class.getMethod("of", String.class);
            runtimeHasHex = true;
        } catch (NoSuchMethodException e) {
            runtimeHasHex = false;
        }
        if (runtimeHasHex) assertEquals("#3366ff", mapped.getName().toLowerCase());
        else assertEquals(BungeeChat.color(NamedTextColor.nearestTo(hex)), mapped);
        assertEquals(ChatColor.GOLD, BungeeChat.color(NamedTextColor.GOLD));
    }

    private static String textOf(BaseComponent c) {
        return c instanceof net.md_5.bungee.api.chat.TextComponent t ? t.getText() : null;
    }
}
