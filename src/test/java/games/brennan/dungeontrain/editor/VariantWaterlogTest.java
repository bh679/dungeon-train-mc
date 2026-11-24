package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The variant menu's water-bucket right-click: water in and out, nothing else touched. */
class VariantWaterlogTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("a stair holds water and lets it go again, keeping its facing and half")
    void stairsFlipBothWays() {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
            .setValue(BlockStateProperties.HALF, Half.TOP);
        BlockState wet = VariantWaterlog.toggle(stair);
        assertTrue(VariantWaterlog.isWaterlogged(wet));
        assertEquals(Direction.WEST, wet.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(Half.TOP, wet.getValue(BlockStateProperties.HALF));
        BlockState dry = VariantWaterlog.toggle(wet);
        assertEquals(stair, dry);
        assertFalse(VariantWaterlog.isWaterlogged(dry));
    }

    @Test
    @DisplayName("slabs and fences take water too")
    void slabAndFence() {
        assertTrue(VariantWaterlog.isWaterlogged(VariantWaterlog.toggle(Blocks.STONE_SLAB.defaultBlockState())));
        assertTrue(VariantWaterlog.isWaterlogged(VariantWaterlog.toggle(Blocks.OAK_FENCE.defaultBlockState())));
    }

    @Test
    @DisplayName("a block that can't hold water is refused")
    void cannotHoldWater() {
        assertNull(VariantWaterlog.toggle(Blocks.STONE.defaultBlockState()));
        assertNull(VariantWaterlog.toggle(Blocks.AIR.defaultBlockState()));
        assertNull(VariantWaterlog.toggle(Blocks.WATER.defaultBlockState()));
        assertNull(VariantWaterlog.toggle(null));
    }
}
