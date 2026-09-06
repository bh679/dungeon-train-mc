package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientPortalSeal;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops the world on the far side of a portal twin's seal from being drawn, by telling the frustum it
 * is not visible.
 *
 * <h2>Why here</h2>
 * <p>A twin corridor is stamped into sealed world — under the bedrock, or over the upside-down band's
 * lid — and everything on the other side of that seal was still being rendered. What the player saw
 * was mostly the seal itself: a bedrock ceiling over a room that is supposed to read as a carriage.
 * Where the band clears the bedrock row, there is no ceiling to hide behind and the train's Sable
 * sub-levels sail past overhead instead.</p>
 *
 * <p>{@code isVisible} is the one seam vanilla funnels the question through: {@code applyFrustum}
 * culls chunk sections with it, {@code EntityRenderer.shouldRender} culls entities with it, and block
 * entities come from the sections that survive. One hook therefore covers terrain, mobs, nameplates
 * and item frames above the seal. Sable's sub-levels are drawn in their own pass against Veil's own
 * frustum wrapper and do not pass through here — {@code SubLevelSealCullMixin} is their half.</p>
 *
 * <h2>Cost</h2>
 * <p>This runs thousands of times a frame, so it does no work: {@link ClientPortalSeal} decides the
 * cut once per frame from the camera event, and what is left here is a volatile read and one
 * comparison, short-circuited by {@code sealed()} the overwhelming majority of the time — nobody is
 * inside a twin.</p>
 *
 * <p><b>Only ever hides.</b> A box straddling the plane is left alone, so the near side of the seal
 * and the twin the player is standing in are never touched, and a portal swap's arrival frame still
 * draws its destination — see {@link SectionOcclusionGraphPortalSwapMixin}, which is what makes that
 * frame land at all.</p>
 */
@Mixin(Frustum.class)
public abstract class FrustumPortalSealMixin {

    @Inject(method = "isVisible(Lnet/minecraft/world/phys/AABB;)Z", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$cullBeyondTwinSeal(AABB aabb, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientPortalSeal.sealed()) return;
        if (ClientPortalSeal.hides(aabb.minY, aabb.maxY)) cir.setReturnValue(false);
    }
}
