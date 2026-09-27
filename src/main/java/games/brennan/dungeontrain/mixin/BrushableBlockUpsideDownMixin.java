package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.worldgen.UpsideDownGravity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Upside-down gravity for {@link BrushableBlock} (suspicious sand / gravel — {@code BrushableBlock} is not a {@code FallingBlock} but runs the same fall check in its own tick; brushing ({@code checkReset}) is untouched).
 *
 * <ul>
 *   <li><b>Frozen until the flip:</b> while the chunk still waits for its deferred mirror, the fall check
 *       reports "supported" and the tick is retried later — so worldgen's persisted ticks can't drop
 *       blocks through the un-flipped terrain.</li>
 *   <li><b>Falls up:</b> in the band / entry lead-in the support cell is the one <em>above</em>, so the
 *       block only falls (up — see {@code FallingBlockEntityUpsideDownMixin}) when the cell above is free.</li>
 * </ul>
 * Rules live in {@link UpsideDownGravity}. Server-only by construction ({@code tick} takes a
 * {@link ServerLevel}).
 */
@Mixin(BrushableBlock.class)
public class BrushableBlockUpsideDownMixin {

    @WrapOperation(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;"))
    private BlockPos dungeontrain$gravitySupport(BlockPos pos, Operation<BlockPos> original,
            @Local(argsOnly = true) ServerLevel level) {
        if (UpsideDownGravity.isReversedBlock(level, pos)) return UpsideDownGravity.supportPos(pos, true);
        return original.call(pos);
    }

    @WrapOperation(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean dungeontrain$freezeUntilFlipped(BlockState support, Operation<Boolean> original,
            @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) BlockPos pos,
            @Local(argsOnly = true) BlockState self) {
        if (UpsideDownGravity.isFrozen(level, pos)) {
            level.scheduleTick(pos, self.getBlock(), UpsideDownGravity.FROZEN_RETRY_TICKS);
            return false;
        }
        return original.call(support);
    }
}
