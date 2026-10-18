package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link CreakingHeartPositions#allowsSpawning}: a running test copy may only ever ADD a
 * heart's spawning; the game rule still decides everywhere else, so editor plots stay quiet.
 */
final class CreakingHeartPositionsTest {

    @Test
    void gameRuleOnAlwaysSpawns() {
        assertTrue(CreakingHeartPositions.allowsSpawning(true, false));
        assertTrue(CreakingHeartPositions.allowsSpawning(true, true));
    }

    @Test
    void testCopySpawnsWithRuleOff() {
        assertTrue(CreakingHeartPositions.allowsSpawning(false, true));
    }

    @Test
    void editorPlotStaysQuiet() {
        assertFalse(CreakingHeartPositions.allowsSpawning(false, false));
    }
}
