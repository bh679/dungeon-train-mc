package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What may spawn naturally in the End band's columns. */
class EndBandSpawnRulesTest {

    @Test
    @DisplayName("endermen stand in every End-band column")
    void endermenEverywhere() {
        assertTrue(EndBandSpawnRules.admits(true, false, false));
        assertTrue(EndBandSpawnRules.admits(true, true, false));
    }

    @Test
    @DisplayName("a BetterEnd column admits its biome's own mobs and nothing else")
    void biomeListInBetterEndColumns() {
        assertTrue(EndBandSpawnRules.admits(false, true, true));
        assertFalse(EndBandSpawnRules.admits(false, true, false), "not on this biome's list");
    }

    @Test
    @DisplayName("a vanilla End column keeps today's endermen-only rule")
    void vanillaColumnsUnchanged() {
        assertFalse(EndBandSpawnRules.admits(false, false, true));
    }

    @Test
    @DisplayName("the sky stops counting only for light-ruled mobs in unlit BetterEnd columns")
    void skyRule() {
        assertTrue(EndBandSpawnRules.skyDoesNotCount(true, true, 0, false));
        assertFalse(EndBandSpawnRules.skyDoesNotCount(false, true, 0, false), "vanilla End column");
        assertFalse(EndBandSpawnRules.skyDoesNotCount(true, false, 0, false), "mob with its own rules");
        assertFalse(EndBandSpawnRules.skyDoesNotCount(true, true, 1, false), "lit by a block, as in the real End");
        assertFalse(EndBandSpawnRules.skyDoesNotCount(true, true, 0, true), "Peaceful");
    }
}
