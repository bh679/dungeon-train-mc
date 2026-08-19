package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.worldgen.NetherBandLava;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lava in the Nether band's core advances at Nether speed. {@code LavaFluid#getSpreadDelay} opens
 * with {@code this.getTickDelay(level)} — 30 ticks in the overworld, 10 in an {@code ultraWarm}
 * dimension — and {@link net.minecraft.world.level.material.FlowingFluid#tick} uses that value to
 * schedule the flow's next step. The band is Nether terrain in the <b>overworld</b> dimension, so
 * vanilla hands it the slow 30.
 *
 * <p>{@code getTickDelay} itself only receives a {@code LevelReader}, so it can't be gated on
 * world-X; {@code getSpreadDelay} does have the {@link BlockPos}, so the delay is corrected here at
 * the call site instead. Sibling of {@link LiquidBlockNetherBandLavaMixin} (first tick of a newly
 * placed flowing-lava block) and {@link FlowingFluidNetherBandLavaMixin} (reach + slope search);
 * all three route the decision through {@link NetherBandLava}.</p>
 */
@Mixin(LavaFluid.class)
public class LavaFluidNetherBandMixin {

    @ModifyExpressionValue(
        method = "getSpreadDelay(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/FluidState;)I",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/LavaFluid;getTickDelay(Lnet/minecraft/world/level/LevelReader;)I"
        )
    )
    private int dungeontrain$netherBandSpreadDelay(
        int original,
        @Local(argsOnly = true) Level level,
        @Local(argsOnly = true, ordinal = 0) BlockPos pos
    ) {
        return NetherBandLava.tickDelay(original, level, pos);
    }
}
