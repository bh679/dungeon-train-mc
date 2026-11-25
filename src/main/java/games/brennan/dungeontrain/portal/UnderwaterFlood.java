package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

/**
 * Sets the sea level of an underwater chunk dimension room's cut, whatever height the real sea
 * stood at in the sampled chunk.
 *
 * <p>At or below the top water row every air cell is water, so a shallow seabed lifted into the
 * box comes out full. Above it the sea is drained: water and the plants that only stand in water
 * become air, and waterlogged blocks are dried out. Applied to the room-local cut, so the doorways
 * and the rest of the room never see the sample's own sea level.</p>
 */
public final class UnderwaterFlood {

    private UnderwaterFlood() {}

    /**
     * Flood {@code states} (a {@code width} x {@code height} x {@code width} cut, indexed
     * {@code (y * width + z) * width + x}) up to and including {@code topWaterRow}, and drain it
     * above. Returns a new array; the input is left as it was.
     */
    public static BlockState[] apply(BlockState[] states, int width, int height, int topWaterRow) {
        BlockState[] out = states.clone();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int layer = width * width;
        for (int y = 0; y < height; y++) {
            boolean wet = y <= topWaterRow;
            for (int i = y * layer, end = i + layer; i < end; i++) {
                BlockState state = out[i];
                if (state == null) continue;
                out[i] = wet ? flooded(state, water) : drained(state, air);
            }
        }
        return out;
    }

    private static BlockState flooded(BlockState state, BlockState water) {
        return state.isAir() ? water : state;
    }

    private static BlockState drained(BlockState state, BlockState air) {
        if (needsWater(state)) return air;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)
            && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return state;
    }

    /** Water itself, and the blocks that exist only in it and would pop or hang in air. */
    static boolean needsWater(BlockState state) {
        if (state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER)) {
            if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) return true;
        }
        return state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT) || state.is(Blocks.SEAGRASS)
            || state.is(Blocks.TALL_SEAGRASS) || state.is(Blocks.BUBBLE_COLUMN);
    }
}
