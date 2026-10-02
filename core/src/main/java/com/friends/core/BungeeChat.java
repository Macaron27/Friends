package com.friends.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;

/**
 * Adventure → BungeeCord chat components, for BungeeCord and for every Paper version (1.8.8 has no Adventure; later
 * ones still accept these). Compiled against the 1.8 chat API: no {@code switch} on {@link ChatColor} (it stopped
 * being an enum) and hex colours only when the runtime has {@code ChatColor.of(String)}.
 */
public final class BungeeChat {
    private BungeeChat() {}

    private static final Map<NamedTextColor, ChatColor> COLORS = new IdentityHashMap<>(Map.ofEntries(
            Map.entry(NamedTextColor.BLACK, ChatColor.BLACK), Map.entry(NamedTextColor.DARK_BLUE, ChatColor.DARK_BLUE),
            Map.entry(NamedTextColor.DARK_GREEN, ChatColor.DARK_GREEN), Map.entry(NamedTextColor.DARK_AQUA, ChatColor.DARK_AQUA),
            Map.entry(NamedTextColor.DARK_RED, ChatColor.DARK_RED), Map.entry(NamedTextColor.DARK_PURPLE, ChatColor.DARK_PURPLE),
            Map.entry(NamedTextColor.GOLD, ChatColor.GOLD), Map.entry(NamedTextColor.GRAY, ChatColor.GRAY),
            Map.entry(NamedTextColor.DARK_GRAY, ChatColor.DARK_GRAY), Map.entry(NamedTextColor.BLUE, ChatColor.BLUE),
            Map.entry(NamedTextColor.GREEN, ChatColor.GREEN), Map.entry(NamedTextColor.AQUA, ChatColor.AQUA),
            Map.entry(NamedTextColor.RED, ChatColor.RED), Map.entry(NamedTextColor.LIGHT_PURPLE, ChatColor.LIGHT_PURPLE),
            Map.entry(NamedTextColor.YELLOW, ChatColor.YELLOW), Map.entry(NamedTextColor.WHITE, ChatColor.WHITE)));

    /** {@code ChatColor.of(String)} on BungeeCord / Spigot 1.16+, else null (fall back to the nearest named colour). */
    private static final MethodHandle HEX = hexFactory();

    private static MethodHandle hexFactory() {
        try {
            return MethodHandles.publicLookup().findStatic(ChatColor.class, "of", MethodType.methodType(ChatColor.class, String.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static BaseComponent[] convert(Component component) {
        return new BaseComponent[] {toBungee(component)};
    }

    static BaseComponent toBungee(Component c) {
        // Our messages are text only (MiniMessage/legacy output); anything else keeps its style and children.
        var out = new net.md_5.bungee.api.chat.TextComponent(c instanceof TextComponent t ? t.content() : "");
        Style style = c.style();
        if (style.color() != null) out.setColor(color(style.color()));
        decoration(style, TextDecoration.BOLD, out::setBold);
        decoration(style, TextDecoration.ITALIC, out::setItalic);
        decoration(style, TextDecoration.UNDERLINED, out::setUnderlined);
        decoration(style, TextDecoration.STRIKETHROUGH, out::setStrikethrough);
        decoration(style, TextDecoration.OBFUSCATED, out::setObfuscated);
        ClickEvent<?> click = style.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Text text) {
            var action = click.action() == ClickEvent.Action.RUN_COMMAND ? net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND
                    : click.action() == ClickEvent.Action.SUGGEST_COMMAND ? net.md_5.bungee.api.chat.ClickEvent.Action.SUGGEST_COMMAND
                    : click.action() == ClickEvent.Action.OPEN_URL ? net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL
                    : null;
            if (action != null) out.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(action, text.value()));
        }
        HoverEvent<?> hover = style.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT) {
            out.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[] {toBungee((Component) hover.value())}));
        }
        for (Component child : c.children()) out.addExtra(toBungee(child));
        return out;
    }

    private static void decoration(Style style, TextDecoration decoration, Consumer<Boolean> setter) {
        TextDecoration.State state = style.decoration(decoration);
        if (state != TextDecoration.State.NOT_SET) setter.accept(state == TextDecoration.State.TRUE);
    }

    static ChatColor color(TextColor color) {
        if (color instanceof NamedTextColor named) return COLORS.get(named);
        if (HEX != null) {
            try {
                return (ChatColor) HEX.invoke(color.asHexString());
            } catch (Throwable ignored) {
                // fall through to the nearest named colour
            }
        }
        return COLORS.get(NamedTextColor.nearestTo(color));
    }
}
