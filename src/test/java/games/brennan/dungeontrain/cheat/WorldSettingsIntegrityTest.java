package games.brennan.dungeontrain.cheat;

import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The difficulty half of {@link WorldSettingsIntegrity}'s classification: below the
 * default is Free Play, at or above it is a legitimate run. The {@code keepInventory}
 * half and the marking sweep need a live server/player, so they are verified in-game.
 */
class WorldSettingsIntegrityTest {

    @Test
    @DisplayName("The balanced default is Normal")
    void defaultIsNormal() {
        assertEquals(Difficulty.NORMAL, WorldSettingsIntegrity.DEFAULT_DIFFICULTY);
    }

    @Test
    @DisplayName("Peaceful and Easy are below default — Free Play")
    void belowDefaultTrips() {
        assertTrue(WorldSettingsIntegrity.isFreePlayDifficulty(Difficulty.PEACEFUL));
        assertTrue(WorldSettingsIntegrity.isFreePlayDifficulty(Difficulty.EASY));
    }

    @Test
    @DisplayName("Normal and Hard are not — a harder run is still a real run")
    void defaultAndAboveStayClean() {
        assertFalse(WorldSettingsIntegrity.isFreePlayDifficulty(Difficulty.NORMAL));
        assertFalse(WorldSettingsIntegrity.isFreePlayDifficulty(Difficulty.HARD));
    }

    @Test
    @DisplayName("A null difficulty never trips (defensive — never false-positive)")
    void nullIsClean() {
        assertFalse(WorldSettingsIntegrity.isFreePlayDifficulty(null));
        assertFalse(WorldSettingsIntegrity.isFreePlayKeepInventory(null));
    }
}
