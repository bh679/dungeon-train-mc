package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
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
 * what is checked here is the enum the four settings are made of, plus the plain constants that
 * decide what each menu ships as.</p>
 */
final class EditorMenuSpaceTest {

    /**
     * The two groups ship pointing different ways, and which menu is in which group is a product
     * decision rather than an implementation detail: X and V act on the whole plot, while C and Z
     * edit one block cell and carry its position, so their in-world panel appears beside the very
     * block being edited. Flipping one of these by accident would quietly move a menu between
     * groups, so they are pinned here.
     */
    @Test
    @DisplayName("plot-wide menus ship screen-space, per-cell menus ship world-space")
    void shippedDefaultsMatchWhatEachMenuActsOn() {
        assertEquals(EditorMenuSpace.SCREENSPACE, ClientDisplayConfig.DEFAULT_COMMAND_MENU_SPACE);
        assertEquals(EditorMenuSpace.SCREENSPACE, ClientDisplayConfig.DEFAULT_TEMPLATE_BLOCKS_MENU_SPACE);
        assertEquals(EditorMenuSpace.WORLDSPACE, ClientDisplayConfig.DEFAULT_CONTAINER_CONTENTS_MENU_SPACE);
        assertEquals(EditorMenuSpace.WORLDSPACE, ClientDisplayConfig.DEFAULT_BLOCK_VARIANT_MENU_SPACE);
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
