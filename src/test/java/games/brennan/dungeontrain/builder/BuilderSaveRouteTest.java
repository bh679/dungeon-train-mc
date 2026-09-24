package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Save does before it writes — every Builder Save button reads this one answer. */
final class BuilderSaveRouteTest {

    @Test
    @DisplayName("A named build of the player's own saves straight away")
    void ownBuildSaves() {
        assertEquals(BuilderSaveRoute.SAVE, BuilderSaveRoute.of(false, false));
    }

    @Test
    @DisplayName("A shipped template asks for a new name, with a local edit as the other way out")
    void shippedTemplateOffersAName() {
        assertEquals(BuilderSaveRoute.NAME_OR_LOCAL, BuilderSaveRoute.of(false, true));
    }

    @Test
    @DisplayName("A draft always needs a name, whatever else is said about it")
    void draftNeedsAName() {
        assertEquals(BuilderSaveRoute.NAME_REQUIRED, BuilderSaveRoute.of(true, false));
        // A draft has no name, so it cannot be going by a shipped one — but if the flag ever said
        // so, offering to keep it "as a local edit" would be offering to write it nowhere.
        assertEquals(BuilderSaveRoute.NAME_REQUIRED, BuilderSaveRoute.of(true, true));
    }

    @Test
    @DisplayName("A shipped template is protected, except in a dev checkout that authors it")
    void devModeIsTheExemption() {
        assertTrue(BuilderBuiltins.isProtected(true, false));
        assertFalse(BuilderBuiltins.isProtected(true, true));
        assertFalse(BuilderBuiltins.isProtected(false, false));
        assertFalse(BuilderBuiltins.isProtected(false, true));
    }
}
