package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link RotationApplier#apply(BlockState, VariantState, BlockPos, long, int, int)} — the
 * whole-entry apply the dimension-room and chunk-frame stamps use. A trapdoor captured bottom-half
 * and set to T in the Block Variants menu must stamp top-half (it used to stamp as captured).
 */
final class RotationApplierEntryTest {

    private static final long WORLD_SEED = 0xC0FFEE_1234L;

    @Test
    @DisplayName("Entry with TOP half stamps a bottom-captured trapdoor top-half at every position")
    void topHalfEntry_forcesTop() {
        BlockState captured = Blocks.BIRCH_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        VariantState entry = VariantState.of(captured).withHalf(VariantHalf.top());
        for (int i = 0; i < 64; i++) {
            BlockState out = RotationApplier.apply(captured, entry,
                new BlockPos(i, i % 5, i * 3), WORLD_SEED, i, 0);
            assertEquals(Half.TOP, out.getValue(BlockStateProperties.HALF));
        }
    }

    @Test
    @DisplayName("Entry with BOTTOM half stamps a top-captured trapdoor bottom-half at every position")
    void bottomHalfEntry_forcesBottom() {
        BlockState captured = Blocks.BIRCH_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP);
        VariantState entry = VariantState.of(captured).withHalf(VariantHalf.bottom());
        for (int i = 0; i < 64; i++) {
            BlockState out = RotationApplier.apply(captured, entry,
                new BlockPos(i, i % 5, i * 3), WORLD_SEED, i, 0);
            assertEquals(Half.BOTTOM, out.getValue(BlockStateProperties.HALF));
        }
    }
}
