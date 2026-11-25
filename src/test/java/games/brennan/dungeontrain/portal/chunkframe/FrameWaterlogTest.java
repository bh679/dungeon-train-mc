package games.brennan.dungeontrain.portal.chunkframe;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FrameWaterlog}: frame blocks under the sea take water, except beside Forced Air. */
class FrameWaterlogTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static boolean wet(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED);
    }

    @Test
    @DisplayName("a stair at or under the sea surface is waterlogged")
    void underSea_waterlogs() {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState();
        assertTrue(wet(FrameWaterlog.apply(stair, 10, 10, false)));
        assertTrue(wet(FrameWaterlog.apply(stair, 3, 10, false)));
    }

    @Test
    @DisplayName("above the surface, beside Forced Air, or in a room with no sea it stays dry")
    void otherwise_staysDry() {
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        assertFalse(wet(FrameWaterlog.apply(fence, 11, 10, false)));
        assertFalse(wet(FrameWaterlog.apply(fence, 5, 10, true)));
        assertFalse(wet(FrameWaterlog.apply(fence, 5, FrameWaterlog.NO_SEA, false)));
    }

    @Test
    @DisplayName("a block that cannot hold water is left as it is")
    void solid_unchanged() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, FrameWaterlog.apply(stone, 0, 10, false));
        assertFalse(FrameWaterlog.canHoldWater(stone));
        assertTrue(FrameWaterlog.canHoldWater(Blocks.GLASS_PANE.defaultBlockState()));
    }
}
