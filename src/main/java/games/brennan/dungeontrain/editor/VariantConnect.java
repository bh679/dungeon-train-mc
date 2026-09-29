package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Which variant entries can carry the {@link VariantState#autoConnect()} flag: blocks whose state
 * stores connection arms toward their neighbours — fences, panes and iron bars
 * ({@link CrossCollisionBlock}) and walls ({@link WallBlock}), including the stage fence and stage
 * stone walls. Fence gates are excluded (their state is a facing, not arms).
 *
 * <p>Off (the default) places the captured state as-is. On re-derives the arms from the real
 * neighbours once the carriage has been placed — see {@link AutoConnectPass}.</p>
 */
public final class VariantConnect {

    private VariantConnect() {}

    /** True when {@code state} is a block whose connection arms the auto-connect flag can re-derive. */
    public static boolean canConnect(@Nullable BlockState state) {
        if (state == null) return false;
        return state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock;
    }
}
