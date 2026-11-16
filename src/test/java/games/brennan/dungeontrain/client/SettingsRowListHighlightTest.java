package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The fade on a row a chat link pointed at: held, then falling to nothing. */
final class SettingsRowListHighlightTest {

    @Test
    @DisplayName("Solid while held, half way through the fade, gone at the end and before the start")
    void strengthOverTime() {
        long fade = SettingsRowList.HIGHLIGHT_MS - SettingsRowList.HIGHLIGHT_HOLD_MS;
        assertEquals(0f, SettingsRowList.highlightStrength(-1));
        assertEquals(1f, SettingsRowList.highlightStrength(0));
        assertEquals(1f, SettingsRowList.highlightStrength(SettingsRowList.HIGHLIGHT_HOLD_MS));
        assertEquals(0.5f, SettingsRowList.highlightStrength(SettingsRowList.HIGHLIGHT_HOLD_MS + fade / 2), 0.001f);
        assertEquals(0f, SettingsRowList.highlightStrength(SettingsRowList.HIGHLIGHT_MS));
    }
}
