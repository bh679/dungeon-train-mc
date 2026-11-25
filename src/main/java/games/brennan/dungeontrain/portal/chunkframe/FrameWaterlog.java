package games.brennan.dungeontrain.portal.chunkframe;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A frame block written under an underwater room's sea: one that can hold water does, so a stair,
 * slab or fence in the sea is wet rather than an air pocket — unless it touches Forced Air, the
 * frame's own dry space (a tunnel), whose edge it would otherwise flood.
 */
public final class FrameWaterlog {

    /** No sea: nothing is under water. */
    public static final int NO_SEA = Integer.MIN_VALUE;

    private FrameWaterlog() {}

    /**
     * {@code state} as it lands at world row {@code y}: waterlogged when it can be, {@code y} is at or
     * under {@code seaTopY}, and it touches no Forced Air. Anything else is returned unchanged.
     */
    public static BlockState apply(BlockState state, int y, int seaTopY, boolean touchesForcedAir) {
        if (state == null || seaTopY == NO_SEA || y > seaTopY || touchesForcedAir) return state;
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) return state;
        if (state.getValue(BlockStateProperties.WATERLOGGED)) return state;
        return state.setValue(BlockStateProperties.WATERLOGGED, true);
    }

    /** True when {@code state} could take water — the only cells worth a neighbour check. */
    public static boolean canHoldWater(BlockState state) {
        return state != null && state.hasProperty(BlockStateProperties.WATERLOGGED)
            && !state.getValue(BlockStateProperties.WATERLOGGED);
    }
}
