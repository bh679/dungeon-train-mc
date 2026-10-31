package games.brennan.dungeontrain.event;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathHazardTest {

    @Test
    void onlyALitCampfireCounts() {
        assertTrue(DeathHazard.litCampfire(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true)));
        assertFalse(DeathHazard.litCampfire(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false)));
        assertFalse(DeathHazard.litCampfire(Blocks.STONE.defaultBlockState()));
    }
}
