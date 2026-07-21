package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.ChuncksBand;
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
 * Stops water and lava from flowing into the chuncks band's <b>empty void space</b> — both a whole
 * {@link games.brennan.dungeontrain.worldgen.ChuncksBand.Kind#VOID void chunk} and the erased underside
 * of a {@link games.brennan.dungeontrain.worldgen.ChuncksBand.Kind#SLICE slice} chunk (below its flat cut
 * Y). The band is mostly void, so every kept chunk that exposes worldgen water/lava (ocean/lake/aquifer
 * edges) sits against void on most sides, and a slice's surface water would pour straight out its flat
 * bottom. Without this, that liquid cascades into the bottomless void — an ever-spreading sheet of
 * flowing fluid that never settles, scheduling a flood of fluid ticks every server tick. That is the
 * dominant cost that made the band feel far heavier than normal terrain (fewer chunks to <em>render</em>,
 * but a runaway <em>tick</em> load).
 *
 * <p>Direct sibling of {@link FlowingFluidUpsideDownMixin} / {@link FlowingFluidExternalWaterMixin}: the
 * same {@code HEAD}-cancellable hook on {@link FlowingFluid#canSpreadTo}, cancel-only so injection order
 * is irrelevant and the three compose. Vetoing every water/lava spread whose destination lands in a void
 * chunk leaves the liquid as static edge blocks on the kept chunk instead of a waterfall into the void.
 * Server-side only (fluid spreading is driven server-side); overworld-gated; scoped to water + lava
 * (modded fluids flow as vanilla). Backed by the memoised {@code WorldGenCycle.fromConfig()} +
 * seed-stable per-chunk classification, so the per-spread test is cheap and falls out fast when the band
 * is disabled or the destination is not a void chunk.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidChuncksMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$blockFluidIntoVoidChuncks(
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
        // Per-BLOCK void test: a whole void chunk, OR the erased underside of a slice chunk (below its cut
        // Y) — so liquid can't pour down out of a slice's flat bottom, not just across a void boundary.
        // Fast-outs internally when the band is off or the destination is kept terrain.
        if (ChuncksBand.isVoidSpace(server, toPos.getX(), toPos.getY(), toPos.getZ())) {
            cir.setReturnValue(false); // no liquid may flow into empty chuncks space
        }
    }
}
