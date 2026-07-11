package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Freezes <b>water</b> flow inside the upside-down band, so the mirrored ocean/lake water that
 * {@code WorldUpsideDownEvents} reflects into the ceiling hangs there as static source blocks instead
 * of pouring off it in waterfalls.
 *
 * <p><b>Why.</b> The terrain mirror now keeps water as level-0 source blocks (see
 * {@code WorldUpsideDownEvents}). A water source with air below/beside it would normally flow on its
 * next fluid tick — down into the train's open gap and out across the ceiling. Vetoing every water
 * spread whose destination lands in the band leaves those sources exactly where they were mirrored.</p>
 *
 * <p>Direct sibling of {@link FlowingFluidExternalWaterMixin}: same {@code HEAD}-cancellable hook on
 * {@link FlowingFluid#canSpreadTo}, both cancel-only so injection order is irrelevant and they compose
 * (train-boundary veto + band veto). Server-side only (fluid spreading is driven server-side);
 * overworld + band gated like the grass freeze, and scoped to water only — lava and modded fluids flow
 * as vanilla (and are still dropped to air by the mirror at generation). Backed by the memoised
 * {@code WorldGenCycle.fromConfig()} so the per-spread band test is cheap.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidUpsideDownMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$freezeWaterInUpsideDownBand(
        BlockGetter level,
        BlockPos fromPos,
        BlockState fromBlockState,
        Direction direction,
        BlockPos toPos,
        BlockState toBlockState,
        FluidState toFluidState,
        Fluid fluid,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(level instanceof ServerLevel server)) return;
        if (!server.dimension().equals(Level.OVERWORLD)) return;
        if (fluid != Fluids.WATER && fluid != Fluids.FLOWING_WATER) return; // water only
        if (UpsideDownBand.isInBand(server, toPos.getX())) {
            cir.setReturnValue(false); // no water flow into in-band cells — mirrored water stays static
        }
    }
}
