package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
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
 * Stops water and lava from flowing off the islands of a void-below legacy band (Skylands) into the open
 * void under them — {@link LegacyBands#isVoidSpace}: a block in a chunk the old generator owns with only
 * air beneath it down to the bottom of the old column. The islands carry Beta lakes and springs; without
 * this veto that liquid spills over their edges and out of their undersides and falls to the world floor,
 * spreading forever (the runaway tick load the chuncks band hit). Streams across island tops and onto
 * lower islands still flow.
 *
 * <p>Direct sibling of {@link FlowingFluidSpheresMixin}: the same {@code HEAD}-cancellable hook on
 * {@link FlowingFluid#canSpreadTo}, cancel-only so injection order is irrelevant. Server-side only,
 * overworld-gated, water + lava only. Fast-outs on the memoised chunk classification outside the band.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidLegacyMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$blockFluidIntoLegacyVoid(
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
        if (LegacyBands.isVoidSpace(server, level, toPos.getX(), toPos.getY(), toPos.getZ())) {
            cir.setReturnValue(false); // no liquid may fall off an island into the void
        }
    }
}
