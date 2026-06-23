package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.TrainFluidBarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops <em>external</em> world fluid from flowing into (or through) the train.
 *
 * <p>Train carriages are Sable sub-levels: their blocks live in a far-away
 * "plot"/storage region and are only <em>rendered and collided</em> at the
 * train's world position. The overworld chunks the train occupies therefore
 * hold air (the carriage voxels were moved out at assembly — see
 * {@code TrainAssembler} / {@code SableShipyard#assemble}). Vanilla fluid
 * physics only consults real world block state, so world water/lava sees that
 * air and spreads straight into the train's footprint, flooding the deck and
 * carriage interiors.</p>
 *
 * <p>This mirrors Sable's own
 * {@code dev.ryanhcode.sable.mixin.fluids_on_sub_levels.FlowingFluidMixin}
 * (which governs fluid spreading <em>inside</em> a sub-level's plot box). We
 * inject at the same {@code HEAD}-cancellable point of
 * {@link FlowingFluid#canSpreadTo} and veto the spread when the destination
 * lands inside a live carriage. Both injectors are cancel-only, so injection
 * order is irrelevant.</p>
 *
 * <p>The footprint test (JOML/Sable types) lives in
 * {@link TrainFluidBarrier} rather than here: those classes are not visible to
 * the mixin transformer's bootstrap classloader, so keeping them out of the
 * mixin body avoids a {@code ClassNotFoundException} at mixin-apply time.</p>
 *
 * <p>Scope: this blocks <em>new flow</em> at the train boundary (streams,
 * lakes, rain runoff, buckets, the train crossing flowing water). It does not
 * expel pre-existing source blocks that the moving train's leading edge drives
 * into (a deep-ocean crossing) — {@code canSpreadTo} only governs spreading,
 * not removal. Covers all flowing fluids (water + lava + modded) because the
 * mixin targets the {@link FlowingFluid} superclass.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidExternalWaterMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$blockExternalFlowIntoTrain(
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
        // Fluid spreading is driven server-side; client-side ticks (and the
        // sub-level's own plot accessor, which is not a ServerLevel) are left
        // to vanilla / Sable.
        if (level instanceof ServerLevel server
            && TrainFluidBarrier.blocksExternalFlowInto(server, toPos)) {
            cir.setReturnValue(false);
        }
    }
}
