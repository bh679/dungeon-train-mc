package games.brennan.dungeontrain.client.live;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LiveChatFlagHoverTest {

    private final List<String> calls = new ArrayList<>();
    private final LiveChatFlagHover hover = new LiveChatFlagHover();

    private Component watch(String name) {
        MutableComponent flag = LanguageFlags.flag("es", "hola");
        hover.watch(flag, () -> calls.add(name + " original"), () -> calls.add(name + " translated"));
        return LiveChatFlagHover.keyOf(flag.getStyle());
    }

    @Test
    @DisplayName("hovering shows the original once; leaving brings the translation back once")
    void hoverAndLeave() {
        Component a = watch("a");
        hover.hover(a);
        hover.hover(a);
        hover.hover(null);
        hover.hover(null);
        assertEquals(List.of("a original", "a translated"), calls);
        assertEquals(false, hover.hovering());
        hover.hover(a);
        assertEquals(true, hover.hovering());
    }

    @Test
    @DisplayName("moving straight from one flag to another puts the first back before showing the second")
    void moveBetween() {
        Component a = watch("a");
        Component b = watch("b");
        hover.hover(a);
        hover.hover(b);
        assertEquals(List.of("a original", "a translated", "b original"), calls);
    }

    @Test
    @DisplayName("each flag is its own, though two read the same; something never watched does nothing")
    void identity() {
        Component a = watch("a");
        Component b = watch("b");
        assertEquals(a, b, "the same message");
        assertNotSame(a, b);
        hover.hover(Component.literal("hola"));
        assertEquals(List.of(), calls);
    }

    @Test
    @DisplayName("keyOf: a flag's hover text; anything else, or no flag at all, is nothing")
    void keyOf() {
        MutableComponent flag = LanguageFlags.flag("es", "hola");
        assertNotNull(LiveChatFlagHover.keyOf(flag.getStyle()));
        assertSame(LiveChatFlagHover.keyOf(flag.getStyle()), LiveChatFlagHover.keyOf(flag.copy().getStyle()), "a copied line keeps its flag's identity");
        assertNull(LiveChatFlagHover.keyOf(Style.EMPTY));
        assertNull(LiveChatFlagHover.keyOf(null));
        hover.watch(null, () -> calls.add("x"), () -> calls.add("x"));
        assertEquals(0, hover.size());
    }

    @Test
    @DisplayName("only the newest flags are remembered, and an evicted one under the mouse is simply let go")
    void bound() {
        Component first = watch("first");
        hover.hover(first);
        for (int i = 0; i < LiveChatFlagHover.MAX; i++) watch("n" + i);
        assertEquals(LiveChatFlagHover.MAX, hover.size());
        calls.clear();
        hover.hover(null);
        assertEquals(List.of(), calls);
    }
}
