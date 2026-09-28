package games.brennan.dungeontrain.mixin.client.sodium;

import games.brennan.dungeontrain.client.ClientPortalSeal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The portal twin's seal cut on <b>Sodium's</b> chunk culling — the same thing
 * {@code FrustumPortalSealMixin} does on the vanilla one.
 *
 * <p>Sodium does not ask {@code net.minecraft.client.renderer.culling.Frustum} whether a section is
 * visible; it walks its own occlusion graph and tests each section against its own
 * {@code Viewport}. So without this the world above a twin's bedrock keeps drawing on every install
 * with Sodium — which is every install with <b>Iris shaders</b> (Iris requires it) and every install
 * of the Dungeon Train modpack, which ships both.</p>
 *
 * <p>{@code isBoxVisible} is where every section-culling path lands
 * ({@code OcclusionCuller.isWithinFrustum} and its nearby-section variant both call straight
 * through), and it takes the section's centre in world block coordinates — three ints, no Sodium
 * types — so this hooks it without Sodium being a compile dependency. A section reaches eight blocks
 * either side of that centre, which is the span the cut is asked about.</p>
 *
 * <p>Targeted by class name and applied only when Sodium is loaded ({@code SodiumMixinPlugin}); the
 * config sets {@code defaultRequire: 0}, so a future Sodium refactor of these methods costs the cut
 * under Sodium rather than the client's launch.</p>
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.viewport.Viewport", remap = false)
public abstract class ViewportPortalSealMixin {

    /** Half a chunk section: the reach of the box {@code isBoxVisible} is asked about. */
    private static final int DUNGEONTRAIN$SECTION_RADIUS = 8;

    @Inject(method = "isBoxVisible(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void dungeontrain$cullBeyondTwinSeal(int x, int y, int z,
                                                 CallbackInfoReturnable<Boolean> cir) {
        dungeontrain$applySeal(y, cir);
    }

    @Inject(method = "isBoxVisibleLooser(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void dungeontrain$cullNearbyBeyondTwinSeal(int x, int y, int z,
                                                       CallbackInfoReturnable<Boolean> cir) {
        dungeontrain$applySeal(y, cir);
    }

    private static void dungeontrain$applySeal(int centreY, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientPortalSeal.sealed()) return;
        if (ClientPortalSeal.hides(centreY - DUNGEONTRAIN$SECTION_RADIUS,
                                   centreY + DUNGEONTRAIN$SECTION_RADIUS)) {
            cir.setReturnValue(false);
        }
    }
}
