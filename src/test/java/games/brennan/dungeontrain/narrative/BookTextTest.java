package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link BookText#toPage} keybind-token resolution:
 * <ul>
 *   <li>Plain pages return a literal with no keybind component (unchanged behaviour).</li>
 *   <li>{@code {key.advancements}} expands into a real {@link KeybindContents}.</li>
 *   <li>Tokens at the edges of a page and multiple tokens all resolve.</li>
 *   <li>Braces that aren't a {@code key.*} token are left untouched.</li>
 * </ul>
 */
final class BookTextTest {

    /** Recursively collect every keybind id present in a component tree. */
    private static List<String> keybindIds(Component c) {
        List<String> out = new ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(Component c, List<String> out) {
        if (c.getContents() instanceof KeybindContents kb) {
            out.add(kb.getName());
        }
        for (Component sibling : c.getSiblings()) {
            collect(sibling, out);
        }
    }

    @Test
    @DisplayName("Plain text → literal component, no keybind")
    void plainText() {
        Component c = BookText.toPage("There are rules to this train.");
        assertEquals("There are rules to this train.", c.getString());
        assertTrue(keybindIds(c).isEmpty(), "plain text must not produce a keybind");
    }

    @Test
    @DisplayName("{key.advancements} → real KeybindContents, surrounding text preserved")
    void singleKeybind() {
        Component c = BookText.toPage("Press {key.advancements} to look.");
        assertEquals(List.of("key.advancements"), keybindIds(c));
        String s = c.getString();
        assertTrue(s.startsWith("Press "), s);
        assertTrue(s.endsWith(" to look."), s);
    }

    @Test
    @DisplayName("Token at the start and at the end of a page both resolve")
    void tokenAtEdges() {
        assertEquals(List.of("key.advancements"),
            keybindIds(BookText.toPage("{key.advancements} reveals it")));
        assertEquals(List.of("key.inventory"),
            keybindIds(BookText.toPage("then press {key.inventory}")));
    }

    @Test
    @DisplayName("Multiple tokens each resolve, in order")
    void multipleTokens() {
        Component c = BookText.toPage("{key.advancements} then {key.inventory}");
        assertEquals(List.of("key.advancements", "key.inventory"), keybindIds(c));
    }

    @Test
    @DisplayName("Non-keybind braces are left untouched")
    void nonKeybindBraces() {
        Component c = BookText.toPage("see {note} and {123} below");
        assertTrue(keybindIds(c).isEmpty());
        assertEquals("see {note} and {123} below", c.getString());
    }

    @Test
    @DisplayName("Empty and null pages are safe")
    void emptyOrNullPage() {
        assertEquals("", BookText.toPage("").getString());
        assertEquals("", BookText.toPage(null).getString());
    }
}
