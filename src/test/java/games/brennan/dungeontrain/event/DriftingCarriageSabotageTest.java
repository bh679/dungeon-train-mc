package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What counts as sabotaging a drifting carriage. The predicate is narrow on purpose — the credit it
 * gates changes the Fight/Flight the player's next echo is born with, so widening it is a design
 * decision, not a tidy-up. These cases are here to make an accidental widening fail loudly.
 *
 * <p>Needs a headless Minecraft bootstrap so the block registry resolves.</p>
 */
class DriftingCarriageSabotageTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("placing TNT is sabotage")
    void tntIsSabotage() {
        assertTrue(DriftingCarriageSabotageEvents.isSabotage(Blocks.TNT.defaultBlockState()));
    }

    @Test
    @DisplayName("ordinary building blocks are not")
    void buildingBlocksAreNot() {
        assertFalse(DriftingCarriageSabotageEvents.isSabotage(Blocks.STONE.defaultBlockState()));
        assertFalse(DriftingCarriageSabotageEvents.isSabotage(Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(DriftingCarriageSabotageEvents.isSabotage(Blocks.CHEST.defaultBlockState()));
        assertFalse(DriftingCarriageSabotageEvents.isSabotage(Blocks.AIR.defaultBlockState()));
    }

    @Test
    @DisplayName("other things that go bang are out of scope, deliberately")
    void otherExplosivesAreOutOfScope() {
        // Not an oversight: end crystals and beds explode too, but each is its own judgement call
        // about intent, and none of them is the block a player reaches for to level a room.
        assertFalse(DriftingCarriageSabotageEvents.isSabotage(Blocks.RESPAWN_ANCHOR.defaultBlockState()));
        assertFalse(DriftingCarriageSabotageEvents.isSabotage(Blocks.RED_BED.defaultBlockState()));
    }
}
