package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @DisplayName("the bedrock row itself is not below the floor")
    void bedrockRowIsInTheWorld() {
        assertFalse(WorldFloor.isBelowFloor(32, 32));
        assertTrue(WorldFloor.isBelowFloor(31, 32));
    }

    @Test
    @DisplayName("a trial chamber's absolute Y lands in a DT world's basement")
    void vanillaAbsoluteAnchorsAreBelowTheFloor() {
        // trial_chambers is anchored at absolute -40..-20; the shallowest DT preset puts bedrock at 16.
        assertTrue(WorldFloor.isBelowFloor(-20, 16));
        assertTrue(WorldFloor.entirelyBelowFloor(-20, 16));
    }

    @Test
    @DisplayName("a structure that straddles the floor keeps its start")
    void straddlingStructureSurvives() {
        assertFalse(WorldFloor.entirelyBelowFloor(40, 32));
        assertFalse(WorldFloor.entirelyBelowFloor(32, 32));
        assertTrue(WorldFloor.entirelyBelowFloor(31, 32));
    }

    @Test
    @DisplayName("with no basement nothing is ever below the floor that vanilla would allow")
    void noBasementRejectsNothingNew() {
        int floor = WorldFloor.bedrockY(-64, -64);
        assertFalse(WorldFloor.isBelowFloor(-64, floor));
        assertFalse(WorldFloor.entirelyBelowFloor(-64, floor));
    }
}
