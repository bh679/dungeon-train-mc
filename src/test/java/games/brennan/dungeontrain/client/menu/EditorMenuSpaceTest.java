package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.config.EditorMenuSpace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EditorMenuSpace}.
 *
 * <p>The config values themselves need a loaded {@code ModConfigSpec}, which needs a client, so
 * what is checked here is the enum the three settings are made of: the shipped default, and that
 * the toggle the menu rows use actually alternates rather than sticking.</p>
 */
final class EditorMenuSpaceTest {

    @Test
    @DisplayName("ships defaulting to screenspace")
    void defaultIsScreenspace() {
        assertEquals(EditorMenuSpace.SCREENSPACE, EditorMenuSpace.DEFAULT);
        assertTrue(EditorMenuSpace.DEFAULT.isScreenspace());
        assertFalse(EditorMenuSpace.DEFAULT.isWorldspace());
    }

    @Test
    @DisplayName("the two modes are mutually exclusive")
    void modesAreExclusive() {
        for (EditorMenuSpace space : EditorMenuSpace.values()) {
            assertNotEquals(space.isWorldspace(), space.isScreenspace(),
                space + " must be exactly one of the two");
        }
    }

    @Test
    @DisplayName("toggling alternates and returns after two presses")
    void toggleAlternates() {
        for (EditorMenuSpace space : EditorMenuSpace.values()) {
            assertNotEquals(space, space.toggled(), space + " should flip");
            assertEquals(space, space.toggled().toggled(), space + " should return");
        }
    }

    /**
     * The config stores these by name, so renaming a constant would silently reset every player
     * who had chosen the other mode back to the default.
     */
    @Test
    @DisplayName("the stored names are the ones the config was written against")
    void namesAreStable() {
        assertEquals("WORLDSPACE", EditorMenuSpace.WORLDSPACE.name());
        assertEquals("SCREENSPACE", EditorMenuSpace.SCREENSPACE.name());
        assertEquals(2, EditorMenuSpace.values().length);
    }
}
