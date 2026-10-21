package games.brennan.dungeontrain.mixin.deeperdarker;

import com.kyanite.deeperdarker.content.blocks.OthersidePortalBlock;
import games.brennan.dungeontrain.event.NetherPortalBandJump;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deeper Darker's Otherside portals on the overworld ride work like DT's Nether portals: they lead to a Deeper Dark
 * core along the track (or, from inside one, back to the overworld before it) instead of the real Otherside —
 * see {@link NetherPortalBandJump} with {@code Kind.OTHERSIDE}. Same hook point as
 * {@code NetherPortalBlockBandJumpMixin}: the head of {@code getPortalDestination}, so the mod's portal timing,
 * overlay, sound and chunk ticket stay its own. Outside the overworld, or with the band-jump off, the mod's own
 * destination runs untouched.
 *
 * <p>Guarded by {@code ThirdPartyMixinPlugin} ({@code compat/mixinguard/ThirdPartyMixinTargets}): Deeper Darker is
 * declared {@code [floor,)}, so on a newer build that moved {@code getPortalDestination} or one of the members
 * {@code OthersideExitPortal} uses, this mixin is skipped with a WARN and the mod's own destination runs. A
 * {@link LinkageError} that only shows at call time is caught in {@link NetherPortalBandJump#othersideDestination}
 * the same way.</p>
 */
@Mixin(OthersidePortalBlock.class)
public abstract class OthersidePortalBlockBandJumpMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$portalAlongTheRide(ServerLevel level, Entity entity, BlockPos pos,
                                                  CallbackInfoReturnable<DimensionTransition> cir) {
        NetherPortalBandJump.Outcome outcome = NetherPortalBandJump.othersideDestination(level, entity, pos);
        if (outcome.handled()) cir.setReturnValue(outcome.transition());
    }
}
