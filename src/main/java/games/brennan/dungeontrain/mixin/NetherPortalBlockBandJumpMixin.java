package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.NetherPortalBandJump;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nether portals on the overworld ride jump the train to the next (or, from inside one, the
 * previous) Nether band instead of opening the real Nether — see {@link NetherPortalBandJump}.
 *
 * <p>Hooked at the head of {@code getPortalDestination} rather than at
 * {@code EntityTravelToDimensionEvent}: vanilla resolves the destination <em>first</em>, and that
 * step searches for / builds an exit portal in the Nether (generating its chunks) before the
 * cancellable event ever fires. Returning {@code null} here is vanilla's own "no destination"
 * path ({@code Entity#handlePortal} skips the dimension change), and the portal cooldown has
 * already been armed, so nothing retries.</p>
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockBandJumpMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$jumpAlongTheRide(ServerLevel level, Entity entity, BlockPos pos,
                                                CallbackInfoReturnable<DimensionTransition> cir) {
        if (NetherPortalBandJump.handle(level, entity)) cir.setReturnValue(null);
    }
}
