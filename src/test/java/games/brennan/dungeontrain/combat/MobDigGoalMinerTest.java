package games.brennan.dungeontrain.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link MobDigGoal#isMiner}: only Luminous Monsters' two Miners get the Miner dig rule. */
final class MobDigGoalMinerTest {

    @Test
    @DisplayName("only the two Miners count")
    void minerIds() {
        assertTrue(MobDigGoal.isMiner("luminous_monsters:miner_zombie"));
        assertTrue(MobDigGoal.isMiner("luminous_monsters:miner_skeleton"));
        assertFalse(MobDigGoal.isMiner("luminous_monsters:swamp_zombie"));
        assertFalse(MobDigGoal.isMiner("minecraft:zombie"));
    }
}
