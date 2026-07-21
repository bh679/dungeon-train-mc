package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.OceanBand;
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
 * Keeps the {@link games.brennan.dungeontrain.worldgen.OceanBand ocean band}'s raised sea static and its
 * corridor dry. The band's water is placed as source blocks up to the track bed height (well above vanilla
 * sea level), which leaves three open faces that vanilla fluid physics would spread across: the band's
 * longitudinal X-edges (where the raised slab faces the open overworld gap ~12 blocks below it), the water
 * surface, and the dry corridor channel carved through the sea. Left alone, that spread cascades an
 * ever-flowing sheet of water out of the band and down into the corridor.
 *
 * <p>Direct sibling of {@link FlowingFluidChuncksMixin} / {@link FlowingFluidExternalWaterMixin}: the same
 * {@code HEAD}-cancellable hook on {@link FlowingFluid#canSpreadTo}, cancel-only so injection order is
 * irrelevant and they compose. The containment test ({@link OceanBand#vetoSpread}) consults <b>both</b> the
 * source and destination positions — vetoing spread that leaves the band, climbs above the surface, or
 * enters the corridor — so the interior (all-source, inert) never ticks and only the perimeter is frozen.
 * Server-side only, overworld-gated, water + lava; falls out fast when the band is off or the spread is
 * nowhere near it.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidOceanMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$containOceanBand(
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
        if (OceanBand.vetoSpread(server, fromPos.getX(), fromPos.getY(), toPos.getX(), toPos.getY(), toPos.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
