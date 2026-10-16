package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link TestCopyMobs#shouldThaw}: the Test Mobs setting may only ever ADD thawing, inside a
 * test copy — a real train always thaws, and an editor plot stays frozen whatever the setting says.
 */
final class TestCopyMobsTest {

    @Test
    void outsideEditorSpaceAlwaysThaws() {
        assertTrue(TestCopyMobs.shouldThaw(false, false, false));
        assertTrue(TestCopyMobs.shouldThaw(false, true, false));
    }

    @Test
    void editorPlotStaysFrozenEvenWhenLive() {
        assertFalse(TestCopyMobs.shouldThaw(true, false, false));
        assertFalse(TestCopyMobs.shouldThaw(true, true, false));
    }

    @Test
    void testCopyThawsOnlyWhenLive() {
        assertFalse(TestCopyMobs.shouldThaw(true, false, true));
        assertTrue(TestCopyMobs.shouldThaw(true, true, true));
    }
}
