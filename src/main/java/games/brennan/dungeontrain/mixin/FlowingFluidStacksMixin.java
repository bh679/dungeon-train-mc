package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.StacksBand;
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
 * Stops water and lava from flowing into the stacks band's <b>empty void chunks</b>
 * ({@link games.brennan.dungeontrain.worldgen.StacksBand.Kind#VOID}). Many vanilla pieces carry
 * liquid — village wells, bastion lava, underwater ruins — and a tower's edge sits against void on
 * every side, so without this that liquid cascades into the bottomless void: an ever-spreading sheet
 * of flowing fluid that never settles, scheduling a flood of fluid ticks every server tick (the same
 * runaway tick load the chuncks band hit).
 *
 * <p>Direct sibling of {@link FlowingFluidChuncksMixin}: the same {@code HEAD}-cancellable hook on
 * {@link FlowingFluid#canSpreadTo}, cancel-only so injection order is irrelevant and the fluid vetoes
 * compose. Liquid stays free to move <em>inside</em> a tower chunk (down through its own layers is
 * fine); it may not leave into an empty chunk. Server-side only; overworld-gated; water + lava only.
 * Backed by the memoised {@code WorldGenCycle.fromConfig()} + seed-stable per-chunk classification, so
 * the per-spread test is cheap and falls out fast when the band is off or the destination is terrain.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidStacksMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$blockFluidIntoVoidStacks(
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
        // Fast-outs internally when the band is off or the destination is terrain / a tower chunk.
        if (StacksBand.isVoidChunk(server, toPos.getX(), toPos.getZ())) {
            cir.setReturnValue(false); // no liquid may flow into an empty stacks-band chunk
        }
    }
}
