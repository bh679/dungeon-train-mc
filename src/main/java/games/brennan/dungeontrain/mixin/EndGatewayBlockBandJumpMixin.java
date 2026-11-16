package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.EndGatewayBandJump;
import games.brennan.dungeontrain.event.NetherPortalBandJump;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * End gateways on the overworld ride lead back along the track instead of to the exit they generated with — see
 * {@link EndGatewayBandJump}. Same hook point as {@code NetherPortalBlockBandJumpMixin}: the head of
 * {@code getPortalDestination}, so the gateway's cooldown, beam and teleport stay vanilla's. A {@code null} answer
 * is vanilla's own "no destination" path.
 */
@Mixin(EndGatewayBlock.class)
public abstract class EndGatewayBlockBandJumpMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$gatewayAlongTheRide(ServerLevel level, Entity entity, BlockPos pos,
                                                  CallbackInfoReturnable<DimensionTransition> cir) {
        NetherPortalBandJump.Outcome outcome = EndGatewayBandJump.destination(level, entity, pos);
        if (outcome.handled()) cir.setReturnValue(outcome.transition());
    }
}
