package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.SpheresBand;
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
 * Stops water and lava from flowing into the spheres band's <b>void space</b> — any block in the
 * band's entry fade or core that lies inside no sphere ({@link SpheresBand#isVoidSpace}). A lifted sphere carries
 * its natural lakes, aquifers and ocean water; without this veto that liquid pours out of the
 * sphere's curved underside and cascades into the bottomless void — an ever-spreading sheet of
 * flowing fluid scheduling fluid ticks forever (the runaway tick load the chuncks band hit).
 *
 * <p>Direct sibling of {@link FlowingFluidChuncksMixin} / {@link FlowingFluidDisintegrationMixin}:
 * the same {@code HEAD}-cancellable hook on {@link FlowingFluid#canSpreadTo}, cancel-only so
 * injection order is irrelevant and the four compose. Server-side only, overworld-gated, water +
 * lava only (modded fluids flow as vanilla). Fast-outs on the memoised cycle when the band is
 * disabled or the destination is out of band.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidSpheresMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$blockFluidIntoSphereVoid(
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
        if (SpheresBand.isVoidSpace(server, toPos.getX(), toPos.getY(), toPos.getZ())) {
            cir.setReturnValue(false); // no liquid may flow into the void between spheres
        }
    }
}
