package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The theme enum the config stores by name, and the constant that decides what ships. */
final class EditorScreenThemeTest {

    @Test
    @DisplayName("the screen ships light, as it was designed")
    void shippedDefault() {
        assertEquals(EditorScreenTheme.LIGHT, ClientDisplayConfig.DEFAULT_EDITOR_SCREEN_THEME);
    }

    @Test
    @DisplayName("toggling alternates and returns after two presses")
    void toggle() {
        for (EditorScreenTheme t : EditorScreenTheme.values()) {
            assertNotEquals(t, t.toggled());
            assertEquals(t, t.toggled().toggled());
        }
    }

    @Test
    @DisplayName("the stored names are the ones the config was written against")
    void namesAreStable() {
        assertEquals("LIGHT", EditorScreenTheme.LIGHT.name());
        assertEquals("DARK", EditorScreenTheme.DARK.name());
        assertEquals(2, EditorScreenTheme.values().length);
    }

    @Test
    @DisplayName("every colour is opaque enough to read: alpha above zero on both themes")
    void coloursDefined() {
        for (EditorScreenTheme t : EditorScreenTheme.values()) {
            for (int c : new int[] {t.panel(), t.bevelLight(), t.bevelDark(), t.outline(), t.subPanel(),
                t.tabIdle(), t.tabHover(), t.tabActive(), t.tabText(), t.tabTextActive(), t.panelText()}) {
                assertNotEquals(0, c >>> 24, t + " has a fully transparent colour");
            }
        }
    }
}
