package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.worldgen.NetherBandLava;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives lava in the Nether band's core the Nether's <b>reach</b>: {@code getDropOff} 2 → 1 (a source
 * spreads 7 blocks instead of 3) and {@code getSlopeFindDistance} 2 → 4 (it looks twice as far ahead
 * for a hole to pour into). Vanilla {@code LavaFluid} keys both off
 * {@code dimensionType().ultraWarm()}, which is false in the band because it is Nether terrain in
 * the <b>overworld</b> dimension.
 *
 * <p>Neither method receives a position, so they're corrected at the three {@link FlowingFluid} call
 * sites that do have one. {@code FlowingFluid} is shared with water, so every hook first checks the
 * receiver is a {@link LavaFluid} — water keeps vanilla behaviour everywhere.</p>
 *
 * <p>Uses MixinExtras {@code @ModifyExpressionValue} rather than {@code @Redirect}, so it composes
 * with other mods hooking the same calls. Independent of the band's other fluid mixins
 * ({@link FlowingFluidChuncksMixin} / {@link FlowingFluidDisintegrationMixin}), which veto spreads
 * into void space via {@code canSpreadTo} — the longer reach here still can't cascade into a void
 * band. Speed of the flow is handled by {@link LavaFluidNetherBandMixin} /
 * {@link LiquidBlockNetherBandLavaMixin}.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidNetherBandLavaMixin {

    /** Sideways spread amount — {@code fluidState.getAmount() - this.getDropOff(level)}. */
    @ModifyExpressionValue(
        method = "spreadToSides(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;getDropOff(Lnet/minecraft/world/level/LevelReader;)I"
        )
    )
    private int dungeontrain$netherBandSpreadDropOff(
        int original,
        @Local(argsOnly = true) Level level,
        @Local(argsOnly = true, ordinal = 0) BlockPos pos
    ) {
        if (!((Object) this instanceof LavaFluid)) return original; // water flows as vanilla
        return NetherBandLava.dropOff(original, level, pos);
    }

    /** Level of the fluid a neighbouring column inherits — {@code i - this.getDropOff(level)}. */
    @ModifyExpressionValue(
        method = "getNewLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/material/FluidState;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;getDropOff(Lnet/minecraft/world/level/LevelReader;)I"
        )
    )
    private int dungeontrain$netherBandNewLiquidDropOff(
        int original,
        @Local(argsOnly = true) Level level,
        @Local(argsOnly = true, ordinal = 0) BlockPos pos
    ) {
        if (!((Object) this instanceof LavaFluid)) return original;
        return NetherBandLava.dropOff(original, level, pos);
    }

    /** How far the downhill search recurses looking for a hole. */
    @ModifyExpressionValue(
        method = "getSlopeDistance(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;ILnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lit/unimi/dsi/fastutil/shorts/Short2ObjectMap;Lit/unimi/dsi/fastutil/shorts/Short2BooleanMap;)I",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;getSlopeFindDistance(Lnet/minecraft/world/level/LevelReader;)I"
        )
    )
    private int dungeontrain$netherBandSlopeFindDistance(
        int original,
        @Local(argsOnly = true) LevelReader level,
        @Local(argsOnly = true, ordinal = 0) BlockPos spreadPos
    ) {
        if (!((Object) this instanceof LavaFluid)) return original;
        return NetherBandLava.slopeFindDistance(original, level, spreadPos);
    }
}
