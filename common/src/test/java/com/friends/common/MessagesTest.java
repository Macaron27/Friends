package com.friends.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

class MessagesTest {
    static String legacy(Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c);
    }

    @Test
    void fastJoinAndLeaveMessagesLookExactlyLikeTheirTemplates() {
        for (Component name : List.of(Messages.name(null, "Bob"), Messages.name("&b[VIP] ", "Bob"), Messages.name("[NoColour] ", "Bob"),
                Component.text("Bobby"))) {
            for (String verb : List.of("joined", "left")) {
                Component template = MiniMessage.miniMessage().deserialize("<green>Friend > <player> <yellow>" + verb + ".",
                        Placeholder.component("player", name));
                Component fast = verb.equals("joined") ? Messages.joined(name) : Messages.left(name);
                assertEquals(legacy(template), legacy(fast), verb + " with " + legacy(name));
            }
        }
    }

    @Test
    void fixedMessagesAreBuiltOnce() {
        assertSame(Messages.help(), Messages.help());
        assertSame(Messages.noFriends(true), Messages.noFriends(true));
    }
}
