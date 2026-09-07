package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Re-derives which sections are visible on every frame of a portal arrival, which is what moving the
 * mouse was doing by hand.
 *
 * <h2>The bug this is for</h2>
 * <p>{@code LevelRenderer.setupRender} ends with:</p>
 * <pre>
 * this.sectionOcclusionGraph.update(smartCull, camera, frustum, this.visibleSections);
 * if (this.sectionOcclusionGraph.consumeFrustumUpdate() || rotX != prevCamRotX || rotY != prevCamRotY) {
 *     this.applyFrustum(offsetFrustum(frustum));
 * }
 * </pre>
 * <p>{@code applyFrustum} is the only thing that rebuilds {@code visibleSections}, and it runs on
 * exactly two triggers: the occlusion graph reporting a <i>finished</i> rebuild, or the camera
 * <b>rotating</b>. A swap moves the camera a hundred blocks and then it sits still. The rebuild is
 * handed to {@code Util.backgroundExecutor()}, so for as long as it takes, {@code currentGraph} is
 * still the graph walked from the old position and nothing re-derives the list — the frame keeps
 * drawing the sections that were visible from where the player used to be, which is nowhere near
 * where they are. It reads as arriving in an unrendered room, and it lasts until the player moves
 * the mouse (rotation → {@code applyFrustum}) or walks far enough to invalidate the graph again.
 * Both of those are players working around this line.</p>
 *
 * <h2>What this does</h2>
 * <p>Reports "the frustum needs applying" for every frame of the arrival window
 * ({@link ClientPortalSwap#inArrivalWindow}), so the list is re-derived each frame until the window
 * closes. The instant the background rebuild lands, the frame after it draws the destination — no
 * input required.</p>
 *
 * <p><b>Cost:</b> one {@code addSectionsInFrustum} per frame for a few hundred milliseconds, once per
 * swap. That is the same work vanilla does on every frame in which a player is turning their head, so
 * it is a rate the renderer already sustains indefinitely.</p>
 *
 * <p><b>Read, not consumed.</b> Deliberately {@code original ||} rather than a replacement: vanilla's
 * own answer still gets asked, so a real rebuild landing mid-window still clears
 * {@code needsFrustumUpdate} exactly as it would have. This only adds frames on which the list is
 * refreshed; it never skips one.</p>
 *
 * <p>{@code require = 0} like its siblings — a mod that replaces the chunk renderer (Sodium) leaves
 * this nothing to attach to, and vanishing quietly is the right outcome there.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrustumRefreshMixin {

    @ModifyExpressionValue(
        method = "setupRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;consumeFrustumUpdate()Z"),
        require = 0)
    private boolean dungeontrain$refreshFrustumWhileArriving(boolean original) {
        return original || ClientPortalSwap.inArrivalWindow();
    }
}
