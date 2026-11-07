package games.brennan.dungeontrain.client.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatBodySwapTest {

    private static String flat(Component c) {
        return c == null ? null : c.getString();
    }

    @Test
    @DisplayName("a literal line's text is replaced, keeping its style and the parts around it")
    void literal() {
        Component line = Component.literal("[Live] ").append(Component.literal("hello").withStyle(ChatFormatting.WHITE)).append(" [+1]");
        Component swapped = ChatBodySwap.withBody(line, "hello", Component.literal("hola"));
        assertEquals("[Live] hola [+1]", flat(swapped));
        assertEquals(ChatFormatting.WHITE.getColor(), swapped.getSiblings().get(0).getStyle().getColor().getValue());
    }

    @Test
    @DisplayName("a translatable's argument is replaced, the original line untouched")
    void argument() {
        Component line = Component.translatableWithFallback("chat.type.text", "<%s> %s", Component.literal("Dev"), Component.literal("hello"));
        Component swapped = ChatBodySwap.withBody(line, "hello", Component.literal("hola"));
        assertNotNull(swapped);
        assertEquals("<Dev> hola", flat(swapped));
        assertEquals("<Dev> hello", flat(line));
    }

    @Test
    @DisplayName("a player named the same as their words keeps their name")
    void nameReadsLikeWords() {
        Component line = Component.translatableWithFallback("chat.type.text", "<%s> %s", Component.literal("hello"), Component.literal("hello"));
        assertEquals("<hello> hola", flat(ChatBodySwap.withBody(line, "hello", Component.literal("hola"))));
    }

    @Test
    @DisplayName("no part reading the text gives null")
    void noMatch() {
        Component line = Component.translatableWithFallback("chat.type.text", "<%s> %s", Component.literal("Dev"), Component.literal("hello"));
        assertNull(ChatBodySwap.withBody(line, "goodbye", Component.literal("adios")));
    }
}
