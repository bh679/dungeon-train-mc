package games.brennan.dungeontrain.event;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathHazardTest {

    @Test
    void onlyALitCampfireCounts() {
        assertTrue(CampfireBlock.isLitCampfire(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true)));
        assertFalse(CampfireBlock.isLitCampfire(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false)));
    }
}
