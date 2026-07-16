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
 * Freezes <b>water</b> and blocks <b>lava</b> flow inside the upside-down band. Mirrored ocean/lake
 * water that {@code WorldUpsideDownEvents} reflects into the ceiling hangs there as static source
 * blocks instead of pouring off in waterfalls; lava (which the mirror never keeps) is kept from
 * creeping in from outside the band.
 *
 * <p><b>Why.</b> The terrain mirror keeps water as level-0 source blocks and drops all other liquids —
 * including lava — to air (see {@code WorldUpsideDownEvents}). A water source with air below/beside it
 * would normally flow on its next fluid tick — down into the train's open gap and out across the
 * ceiling. Vetoing every water spread whose destination lands in the band leaves those sources exactly
 * where they were mirrored. Lava needs the opposite guarantee: the mirror leaves no lava in the band,
 * but a vanilla lava source just outside the band (e.g. the returning overworld in the exit crossfade,
 * or ordinary terrain at the band edges) could otherwise flow in. Vetoing lava spread into band cells
 * blocks that flow-in so no lava block ever occupies a band cell.</p>
 *
 * <p>Direct sibling of {@link FlowingFluidExternalWaterMixin}: same {@code HEAD}-cancellable hook on
 * {@link FlowingFluid#canSpreadTo}, both cancel-only so injection order is irrelevant and they compose
 * (train-boundary veto + band veto). Server-side only (fluid spreading is driven server-side);
 * overworld + band gated like the grass freeze, and scoped to water + lava — modded fluids flow as
 * vanilla (and are still dropped to air by the mirror at generation). Backed by the memoised
 * {@code WorldGenCycle.fromConfig()} so the per-spread band test is cheap.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidUpsideDownMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$freezeFluidInUpsideDownBand(
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
        if (fluid != Fluids.WATER && fluid != Fluids.FLOWING_WATER
                && fluid != Fluids.LAVA && fluid != Fluids.FLOWING_LAVA) return; // water + lava
        // Veto across the band, its entry lead-in AND its exit crossfade — water the mirror reveals in
        // the lead-in, and water on the dispersing / returning islands in the exit zone, must all stay
        // static rather than pour off into the void; lava must be kept from flowing in from outside.
        if (UpsideDownBand.isInBandEntryLeadOrExit(server, toPos.getX())) {
            cir.setReturnValue(false); // no fluid flow into these cells — mirrored water stays static, lava stays out
        }
    }
}
