package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The command words for {@link EditorSettingMode}, which the Settings rows send. */
final class EditorSettingModeTest {

    @Test
    void idsRoundTrip() {
        for (EditorSettingMode mode : EditorSettingMode.values()) {
            assertEquals(mode, EditorSettingMode.parse(mode.id()));
        }
        assertEquals("editor", EditorSettingMode.EDITOR.id());
        assertEquals("test", EditorSettingMode.TEST.id());
        assertNull(EditorSettingMode.parse("both"));
    }
}
