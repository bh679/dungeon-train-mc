package games.brennan.dungeontrain.train;

import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeMelonSpawnerTest {

    private static final int GROUP = 3;
    private static final int HARD = ChallengeMelonSpawner.HARD_GROUP_INTERVAL;
    private static final int NORMAL = ChallengeMelonSpawner.NORMAL_GROUP_INTERVAL;

    @Test
    void intervalByDifficulty() {
        assertEquals(5, ChallengeMelonSpawner.groupInterval(Difficulty.HARD));
        assertEquals(7, ChallengeMelonSpawner.groupInterval(Difficulty.NORMAL));
        assertEquals(0, ChallengeMelonSpawner.groupInterval(Difficulty.EASY));
        assertEquals(0, ChallengeMelonSpawner.groupInterval(Difficulty.PEACEFUL));
    }

    @Test
    void everySeventhGroupOnNormal() {
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(21, GROUP, NORMAL));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(-21, GROUP, NORMAL));
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(15, GROUP, NORMAL));
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(30, GROUP, NORMAL));
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(0, GROUP, NORMAL), "tail of the spawn group");
    }

    @Test
    void noMelonsWithoutAnInterval() {
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(15, GROUP, 0));
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(21, GROUP, 0));
    }

    @Test
    void everyFifthGroupForward() {
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(0, GROUP, HARD), "tail of the spawn group");
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(3, GROUP, HARD));
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(12, GROUP, HARD));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(15, GROUP, HARD));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(30, GROUP, HARD));
    }

    @Test
    void everyFifthGroupBackward() {
        assertFalse(ChallengeMelonSpawner.isMelonFlatbed(-3, GROUP, HARD));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(-15, GROUP, HARD));
        assertTrue(ChallengeMelonSpawner.isMelonFlatbed(-30, GROUP, HARD));
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
