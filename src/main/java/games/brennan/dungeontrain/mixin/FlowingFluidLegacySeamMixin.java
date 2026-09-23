package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
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
 * Stops water and lava from flowing <b>out of a modern chunk into an Indev floating chunk</b>
 * ({@link LegacyBandKind#FLOATING}). Across the band's fades modern chunks and floating chunks interleave,
 * so a modern ocean, river or aquifer can sit flush against a chunk that is mostly open void; without this
 * it would pour over the chunk wall into the void as an ever-spreading sheet that never settles — the same
 * runaway fluid-tick load the chuncks and stacks bands hit.
 *
 * <p>{@link FlowingFluidLegacyMixin} alone is not enough here: it only stops liquid entering a column that
 * is air all the way down, and a floating chunk has island layers under most of its air, so a seam ocean
 * would still pour over the wall onto them as standing waterfalls. Same {@code HEAD}-cancellable hook on
 * {@link FlowingFluid#canSpreadTo}, cancel-only so the fluid vetoes compose. Liquid inside the floating
 * band moves freely (Indev floating levels generate none; a player's bucket on an island behaves as it
 * would on any sky island). Server-side, overworld-gated, water + lava only; the per-chunk classification
 * is memoised and falls out fast outside the band.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidLegacySeamMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$blockFluidIntoFloatingVoid(
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
        int toChunkX = toPos.getX() >> 4;
        int toChunkZ = toPos.getZ() >> 4;
        if (toChunkX == fromPos.getX() >> 4 && toChunkZ == fromPos.getZ() >> 4) return; // same chunk
        if (LegacyBands.kindOfChunk(server, toChunkX, toChunkZ) != LegacyBandKind.FLOATING) return;
        if (LegacyBands.kindOfChunk(server, fromPos.getX() >> 4, fromPos.getZ() >> 4) != LegacyBandKind.FLOATING) {
            cir.setReturnValue(false); // no modern liquid may pour over the wall into the floating void
        }
    }
}
