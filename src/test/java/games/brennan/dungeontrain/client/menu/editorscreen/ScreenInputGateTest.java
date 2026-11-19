package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.menu.editorscreen.ScreenInputGate.Layer;
import static games.brennan.dungeontrain.client.menu.editorscreen.ScreenInputGate.State;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The X menu's input order, pinned: which of the modal, the search panel, the inline number
 * field, the filter box, a stage dropdown and the panes takes a click, a wheel notch, a key or a
 * typed character. The screen itself cannot be built in a test, so this is where the order lives.
 */
final class ScreenInputGateTest {

    private static State of(boolean modal, boolean typing, boolean search, boolean inline, boolean filter, boolean dropdown) {
        return new State(modal, typing, search, inline, filter, dropdown);
    }

    @Test
    @DisplayName("a click: modal over search over an open stage dropdown over the panes")
    void clickOrder() {
        assertEquals(Layer.MODAL, ScreenInputGate.click(of(true, false, true, true, true, true)));
        assertEquals(Layer.SEARCH, ScreenInputGate.click(of(false, false, true, true, true, true)));
        assertEquals(Layer.STAGE_DROPDOWN, ScreenInputGate.click(of(false, false, false, true, true, true)));
        assertEquals(Layer.PANES, ScreenInputGate.click(of(false, false, false, true, true, false)));
        assertEquals(Layer.PANES, ScreenInputGate.click(State.NONE));
    }

    @Test
    @DisplayName("a click past the modal and the search panel abandons a half-typed number")
    void clickCancelsInlineEdit() {
        assertTrue(ScreenInputGate.clickCancelsInlineEdit(of(false, false, false, true, false, false)));
        assertTrue(ScreenInputGate.clickCancelsInlineEdit(of(false, false, false, true, false, true)),
            "even when a dropdown takes the click");
        assertFalse(ScreenInputGate.clickCancelsInlineEdit(of(true, false, false, true, false, false)),
            "a modal's click leaves the field as it was");
        assertFalse(ScreenInputGate.clickCancelsInlineEdit(of(false, false, true, true, false, false)),
            "so does the search panel's");
        assertFalse(ScreenInputGate.clickCancelsInlineEdit(State.NONE), "nothing to cancel");
    }

    @Test
    @DisplayName("a wheel notch: modal over search over the panes; the dropdown is one of the panes")
    void scrollOrder() {
        assertEquals(Layer.MODAL, ScreenInputGate.scroll(of(true, false, true, false, false, true)));
        assertEquals(Layer.SEARCH, ScreenInputGate.scroll(of(false, false, true, false, false, true)));
        assertEquals(Layer.PANES, ScreenInputGate.scroll(of(false, false, false, true, true, true)));
    }

    @Test
    @DisplayName("a key: inline field, then search, then a typing modal, then the filter box, then a dropdown")
    void keyOrder() {
        assertEquals(Layer.INLINE_EDIT, ScreenInputGate.key(of(true, true, true, true, true, true)));
        assertEquals(Layer.SEARCH, ScreenInputGate.key(of(true, true, true, false, true, true)));
        assertEquals(Layer.MODAL, ScreenInputGate.key(of(true, true, false, false, true, true)));
        assertEquals(Layer.FILTER_BOX, ScreenInputGate.key(of(true, false, false, false, true, true)));
        assertEquals(Layer.STAGE_DROPDOWN, ScreenInputGate.key(of(true, false, false, false, false, true)));
        assertEquals(Layer.PANES, ScreenInputGate.key(of(true, false, false, false, false, false)),
            "an open modal that is not typing leaves keys to the screen, which pops it on Escape");
    }

    @Test
    @DisplayName("a typed character goes to the search panel before the inline field — the reverse of a key")
    void charOrder() {
        assertEquals(Layer.SEARCH, ScreenInputGate.charTyped(of(false, true, true, true, false, false)));
        assertEquals(Layer.INLINE_EDIT, ScreenInputGate.charTyped(of(false, true, false, true, false, false)));
        assertEquals(Layer.MODAL, ScreenInputGate.charTyped(of(false, true, false, false, false, false)));
        assertEquals(Layer.PANES, ScreenInputGate.charTyped(State.NONE));
    }
}
