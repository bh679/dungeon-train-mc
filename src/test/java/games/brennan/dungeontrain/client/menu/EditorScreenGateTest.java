package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the X key lands: the whole truth table. */
final class EditorScreenGateTest {

    @Test
    @DisplayName("screen space opens the editor screen in a plot, in the editor world, or both")
    void screenspace() {
        assertTrue(EditorScreenGate.opensInventoryScreen(true, true, true));
        assertTrue(EditorScreenGate.opensInventoryScreen(true, false, true));
        assertTrue(EditorScreenGate.opensInventoryScreen(false, true, true));
        assertFalse(EditorScreenGate.opensInventoryScreen(false, false, true), "a play world keeps the panel");
    }

    @Test
    @DisplayName("a Train Builder world opens it too, with the same screen-space rule")
    void builder() {
        assertTrue(EditorScreenGate.opensInventoryScreen(false, false, true, true));
        assertFalse(EditorScreenGate.opensInventoryScreen(false, false, true, false), "world space keeps the panel");
        assertFalse(EditorScreenGate.opensInventoryScreen(false, false, false, true), "a play world keeps the panel");
        // The three-cue form reads as "not in the builder".
        assertFalse(EditorScreenGate.opensInventoryScreen(false, false, true));
    }

    @Test
    @DisplayName("world space never opens it")
    void worldspace() {
        assertFalse(EditorScreenGate.opensInventoryScreen(true, true, false));
        assertFalse(EditorScreenGate.opensInventoryScreen(true, false, false));
        assertFalse(EditorScreenGate.opensInventoryScreen(false, true, false));
        assertFalse(EditorScreenGate.opensInventoryScreen(false, false, false));
    }
}
