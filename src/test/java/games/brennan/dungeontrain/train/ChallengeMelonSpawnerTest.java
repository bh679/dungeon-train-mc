package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeMelonSpawnerTest {

    private static final int GROUP = 3;

    @Test
    void everyFifthGroupForward() {
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(0, GROUP), "tail of the spawn group");
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(3, GROUP));
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(12, GROUP));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(15, GROUP));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(30, GROUP));
    }

    @Test
    void everyFifthGroupBackward() {
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(-3, GROUP));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(-15, GROUP));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(-30, GROUP));
    }

    @Test
    void onlyEdgeRoomsTouchAFlatbed() {
        assertEquals(15, ChallengeMelonSpawner.adjacentFlatbed(15, 15, GROUP), "first room → back pad");
        assertNull(ChallengeMelonSpawner.adjacentFlatbed(16, 15, GROUP), "middle room");
        assertEquals(18, ChallengeMelonSpawner.adjacentFlatbed(17, 15, GROUP), "last room → front pad");
        assertEquals(-15, ChallengeMelonSpawner.adjacentFlatbed(-15, -15, GROUP));
        assertEquals(-12, ChallengeMelonSpawner.adjacentFlatbed(-13, -15, GROUP));
    }

    @Test
    void approachingTheSameFlatbedFromEitherSideGivesOneKey() {
        Integer fromBehind = ChallengeMelonSpawner.adjacentFlatbed(14, 12, GROUP); // last room of group 12
        Integer fromAhead = ChallengeMelonSpawner.adjacentFlatbed(15, 15, GROUP);  // first room of group 15
        assertEquals(fromBehind, fromAhead);
    }
}
