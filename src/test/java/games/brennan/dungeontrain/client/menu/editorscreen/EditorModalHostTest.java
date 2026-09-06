package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A typing state must never be one nothing draws.
 *
 * <p>The bug this pins: the browser's <b>+</b> on Tracks and Dimensions, and the toolbar's
 * <b>Rename</b>, dispatch a {@link CommandMenuEntry.TypeArg} straight from a pane with no modal
 * open. That set the host typing, and the prompt was only ever drawn inside a modal — so the button
 * looked dead, the keyboard was captured invisibly, and Enter created a template the author never
 * saw themselves name.</p>
 */
final class EditorModalHostTest {

    private static EditorModalHost host() {
        return new EditorModalHost(() -> { }, () -> { });
    }

    @Test
    @DisplayName("typing with no modal open is a prompt this host draws itself")
    void typingOutsideAModalIsDrawn() {
        EditorModalHost host = host();
        assertFalse(host.isTyping());
        assertFalse(host.hasStandalonePrompt());

        host.beginTyping("name", "dungeontrain editor portals new portal_room", "", "");
        assertTrue(host.isTyping(), "the keyboard is captured…");
        assertTrue(host.hasStandalonePrompt(), "…so something has to be on screen saying so");
        assertFalse(host.isOpen(), "and no modal was opened to hold it");
    }

    @Test
    @DisplayName("what is typed is what the prompt shows, and Esc gives the keyboard back")
    void bufferAndCancel() {
        EditorModalHost host = host();
        host.beginTyping("name", "dungeontrain editor portals new portal_room", "", "");
        host.charTyped('c');
        host.charTyped('R');          // lowercased, like every other name field here
        host.charTyped('!');          // refused: names are letters, digits and underscores
        host.charTyped('7');
        assertEquals("cr7", host.typedBuffer());
        host.backspace();
        assertEquals("cr", host.typedBuffer());

        assertTrue(host.pop(), "Esc cancels the typing before it closes anything");
        assertFalse(host.isTyping());
        assertFalse(host.hasStandalonePrompt());
        assertEquals("", host.typedBuffer());
    }

    @Test
    @DisplayName("an initial value is offered to be edited, capped to what the field holds")
    void initialBuffer() {
        EditorModalHost host = host();
        host.beginTyping("new_name", "dungeontrain editor rename cabin", "", "cabin");
        assertEquals("cabin", host.typedBuffer());

        host.beginTyping("name", "prefix", "", "x".repeat(EditorModalHost.MAX_TYPED + 5));
        assertEquals(EditorModalHost.MAX_TYPED, host.typedBuffer().length());
    }

    @Test
    @DisplayName("the prompt names what it is asking for, in words rather than in the arg token")
    void argNameReadsAsWords() {
        assertEquals("Name", EditorModalHost.prettyArg("name"));
        assertEquals("New name", EditorModalHost.prettyArg("new_name"));
        assertEquals("", EditorModalHost.prettyArg(null));
        assertEquals("", EditorModalHost.prettyArg("  "));
    }
}
