package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * The block-variant menu's water-bucket right-click: a row's block carries water, or stops carrying
 * it. Stairs, slabs, fences, panes and the like have the {@code waterlogged} property; most blocks
 * do not, and those can never hold water.
 */
public final class VariantWaterlog {

    private VariantWaterlog() {}

    /** {@code state} with its water flipped, every other property kept; null when it can't hold water. */
    public static @Nullable BlockState toggle(@Nullable BlockState state) {
        if (state == null || !state.hasProperty(BlockStateProperties.WATERLOGGED)) return null;
        return state.setValue(BlockStateProperties.WATERLOGGED, !state.getValue(BlockStateProperties.WATERLOGGED));
    }

    /** True when {@code state} is holding water. */
    public static boolean isWaterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }
}
