package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.portal.PortalFluidBarrier;
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
 * Keeps fluid from crossing the boundary of a dimensional carriage, either way.
 *
 * <p>The rooms share the basement under the world, and water spilling off one room's open edge fell
 * onto the next. The rule and why it is drawn where it is live in {@link PortalFluidBarrier}; this is
 * only the hook. Same target as {@link FlowingFluidExternalWaterMixin}, which keeps the train dry, and
 * like it cancel-only, so the two never need ordering.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidPortalRoomMixin {
    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$keepFluidInsidePortalRooms(
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
        if (level instanceof ServerLevel server
            && PortalFluidBarrier.blocksFlow(server, fromPos, toPos)) {
            cir.setReturnValue(false);
        }
    }
}
