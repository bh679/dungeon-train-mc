package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.VoidFluidGuard;
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
 * Stops water and lava from flowing into the disintegration/End band's <b>void space</b> — the same
 * runaway-tick fix as {@link FlowingFluidChuncksMixin}, applied to the other void band. Across the End
 * void holds + core, whole chunks are erased to void ({@code NoiseBasedChunkGeneratorMixin} skips their
 * fill, {@code WorldDisintegrationEvents} erodes the fade edges). Worldgen water/lava exposed on the
 * surrounding fade columns would otherwise pour into that void, scheduling a flood of fluid ticks every
 * server tick — heavy tick load, not a render cost.
 *
 * <p>The test is <b>per block</b> ({@link DisintegrationBand#isVoidSpace}), not per chunk. An earlier
 * version gated on {@code isChunkFullyEroded}, which covered only the band core and so missed the fade
 * zones entirely — and the fades are the one part of the band that still has terrain, hence the only
 * part that can hold an ocean, lake or aquifer in the first place. Worse, {@code BedrockFloorEvents}
 * already declines to place the bedrock row across the whole fade ({@code middleRamp > 0}), so a fade
 * column is floorless all the way down to the dimension's {@code min_y}: liquid draining through the
 * eroded surface fell ~130 blocks, puddled on the build floor and then spread across an unbounded
 * empty basement plane. That horizontal sheet, not the fall, was the runaway.</p>
 *
 * <p>Direct sibling of {@link FlowingFluidChuncksMixin} / {@link FlowingFluidUpsideDownMixin} /
 * {@link FlowingFluidExternalWaterMixin}: same {@code HEAD}-cancellable hook on
 * {@link FlowingFluid#canSpreadTo}, cancel-only so injection order is irrelevant and they compose.
 * Vetoing every water/lava spread whose destination is certain void leaves the liquid as static edge
 * blocks instead of a waterfall into nothing. Server-side, overworld-gated, water + lava only. Backed
 * by the memoised {@code WorldGenCycle.fromConfig()}; {@link VoidFluidGuard#ENABLED} is the Gate 2 A/B
 * kill-switch.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidDisintegrationMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$blockFluidIntoErodedVoid(
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
                && fluid != Fluids.LAVA && fluid != Fluids.FLOWING_LAVA) return; // water + lava only
        if (!VoidFluidGuard.ENABLED) return;                          // A/B kill-switch
        long startX = DisintegrationBand.startX(server);
        if (startX == DisintegrationBand.OFF) return;                 // band disabled / no train — fast out
        int chunkMinX = (toPos.getX() >> 4) << 4;
        if (chunkMinX + 15 < startX) return;                          // before the first band
        if (DisintegrationBand.isVoidSpace(server, toPos.getX(), toPos.getY(), toPos.getZ())) {
            VoidFluidGuard.countVeto();
            cir.setReturnValue(false); // no liquid may flow into certain void
        }
    }
}
