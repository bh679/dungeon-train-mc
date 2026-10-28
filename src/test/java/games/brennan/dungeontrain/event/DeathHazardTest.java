package games.brennan.dungeontrain.event;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathHazardTest {

    @Test
    void campfireIsLitOnlyWhenLit() {
        assertTrue(net.minecraft.world.level.block.CampfireBlock.isLitCampfire(Blocks.CAMPFIRE.defaultBlockState()));
        assertFalse(Blocks.CAMPFIRE.defaultBlockState().isAir());
    }
}
