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
 * Nether portals on the overworld ride lead to another Nether portal in the next (or, from inside one,
 * the previous) Nether band instead of the real Nether — see {@link NetherPortalBandJump}.
 *
 * <p>Hooked at the head of {@code getPortalDestination}: that is where vanilla decides the destination
 * (and searches / builds the exit portal there), so replacing its answer with a same-level
 * {@link DimensionTransition} built by vanilla's own {@code getExitPortal}
 * ({@link NetherPortalBlockInvoker}) keeps every other step — portal timing, cooldown, teleport, sound,
 * chunk ticket — exactly vanilla. A {@code null} answer is vanilla's own "no destination" path.</p>
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockBandJumpMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$portalAlongTheRide(ServerLevel level, Entity entity, BlockPos pos,
                                                  CallbackInfoReturnable<DimensionTransition> cir) {
        NetherPortalBlockInvoker self = (NetherPortalBlockInvoker) this;
        NetherPortalBandJump.Outcome outcome = NetherPortalBandJump.destination(level, entity, pos,
            self::dungeontrain$getExitPortal);
        if (outcome.handled()) cir.setReturnValue(outcome.transition());
    }
}
