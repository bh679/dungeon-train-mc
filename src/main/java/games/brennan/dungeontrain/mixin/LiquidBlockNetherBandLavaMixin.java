package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.worldgen.NetherBandLava;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The other half of "lava in the Nether band's core flows at Nether speed" (see
 * {@link LavaFluidNetherBandMixin}). When a flowing-lava block is created — placed by a bucket, or
 * written by a spread — {@link LiquidBlock} schedules its <em>first</em> fluid tick with
 * {@code this.fluid.getTickDelay(level)}. Left alone, every newly spread block would still wait the
 * overworld's 30 ticks before advancing, so the flow would crawl regardless of the spread delay.
 *
 * <p>All three vanilla scheduling sites are corrected ({@code onPlace}, {@code updateShape},
 * {@code neighborChanged}), each with the position that call is scheduling for. Scoped to lava via
 * the shadowed {@code fluid} field, so water keeps its vanilla 5-tick delay.</p>
 */
@Mixin(LiquidBlock.class)
public class LiquidBlockNetherBandLavaMixin {

    @Shadow @Final public FlowingFluid fluid;

    @ModifyExpressionValue(
        method = "onPlace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;getTickDelay(Lnet/minecraft/world/level/LevelReader;)I"
        )
    )
    private int dungeontrain$netherBandPlaceDelay(
        int original,
        @Local(argsOnly = true) Level level,
        @Local(argsOnly = true, ordinal = 0) BlockPos pos
    ) {
        if (!this.fluid.is(FluidTags.LAVA)) return original; // water flows as vanilla
        return NetherBandLava.tickDelay(original, level, pos);
    }

    @ModifyExpressionValue(
        method = "updateShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;getTickDelay(Lnet/minecraft/world/level/LevelReader;)I"
        )
    )
    private int dungeontrain$netherBandUpdateShapeDelay(
        int original,
        @Local(argsOnly = true) LevelAccessor level,
        @Local(argsOnly = true, ordinal = 0) BlockPos currentPos
    ) {
        if (!this.fluid.is(FluidTags.LAVA)) return original;
        return NetherBandLava.tickDelay(original, level, currentPos);
    }

    @ModifyExpressionValue(
        method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;getTickDelay(Lnet/minecraft/world/level/LevelReader;)I"
        )
    )
    private int dungeontrain$netherBandNeighborDelay(
        int original,
        @Local(argsOnly = true) Level level,
        @Local(argsOnly = true, ordinal = 0) BlockPos pos
    ) {
        if (!this.fluid.is(FluidTags.LAVA)) return original;
        return NetherBandLava.tickDelay(original, level, pos);
    }
}
