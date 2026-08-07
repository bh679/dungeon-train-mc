package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math unit tests for {@link WorldFloor} — telling the world's floor apart from the level's.
 */
final class WorldFloorTest {

    @Test
    @DisplayName("a DT world's floor is where terrain starts, not where blocks may go")
    void basementWorld() {
        assertEquals(32, WorldFloor.bedrockY(-48, 32));
    }

    @Test
    @DisplayName("a world with no basement answers the same either way")
    void noBasement() {
        assertEquals(-64, WorldFloor.bedrockY(-64, -64));
    }

    @Test
    @DisplayName("a generator shallower than the level never reports below the build floor")
    void generatorAboveBuildFloor() {
        // Same clamp vanilla's WorldGenerationContext applies, so surface rules and DT's bedrock
        // can never disagree about where the bottom is.
        assertEquals(0, WorldFloor.bedrockY(-64, 0));
    }
}
