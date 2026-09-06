package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Typing a value straight into the cell that shows it. */
final class InlineEditTest {

    private static final InventoryEditorLayout.Rect CELL = new InventoryEditorLayout.Rect(10, 20, 30, 10);

    private static InlineEdit editing(String shown) {
        InlineEdit edit = new InlineEdit();
        edit.begin("dungeontrain editor weight pen", shown, CELL);
        return edit;
    }

    private static void type(InlineEdit edit, String text) {
        for (char c : text.toCharArray()) edit.charTyped(c);
    }

    @Test
    @DisplayName("typing replaces the old value rather than running on from it")
    void typingReplaces() {
        InlineEdit edit = editing("20");
        assertTrue(edit.active());
        assertEquals("", edit.buffer(), "the field starts empty so the first keystroke replaces");
        type(edit, "5");
        assertEquals("dungeontrain editor weight pen 5", edit.submit());
        assertFalse(edit.active());
    }

    @Test
    @DisplayName("digits and a leading minus are accepted; anything else is dropped")
    void acceptsNumbersOnly() {
        InlineEdit edit = editing("all");
        type(edit, "-1");
        assertEquals("-1", edit.buffer());
        type(edit, "a-b");
        assertEquals("-1", edit.buffer(), "letters, and a minus after the first slot, are dropped");
    }

    @Test
    @DisplayName("submitting nothing runs no command, and escape abandons the edit")
    void emptySubmitAndCancel() {
        InlineEdit edit = editing("20");
        assertNull(edit.submit(), "an untouched field must not send a command");
        assertFalse(edit.active());

        InlineEdit lone = editing("20");
        type(lone, "-");
        assertNull(lone.submit(), "a lone minus is not a number");

        InlineEdit cancelled = editing("20");
        type(cancelled, "7");
        cancelled.cancel();
        assertFalse(cancelled.active());
    }

    @Test
    @DisplayName("backspace walks back, and the field will not grow past its cap")
    void backspaceAndCap() {
        InlineEdit edit = editing("20");
        type(edit, "123");
        edit.backspace();
        assertEquals("12", edit.buffer());
        type(edit, "34567890");
        assertEquals(InlineEdit.MAX_LENGTH, edit.buffer().length());
        edit.cancel();
        edit.backspace();
        assertEquals("", edit.buffer(), "backspacing an inactive field is harmless");
    }
}
