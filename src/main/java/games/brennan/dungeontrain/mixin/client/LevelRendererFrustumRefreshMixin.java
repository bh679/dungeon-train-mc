package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.ClientPortalSeal;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import games.brennan.dungeontrain.client.portal.PortalArrivalTrace;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <h2>Gated on Sodium being absent, and why that is not optional</h2>
 * <p>Sodium's {@code core.render.world.LevelRendererMixin} <b>merges</b> {@code setupRender}. Mixin
 * refuses to inject into a method merged by another mixin of equal priority, and that refusal is an
 * {@code InvalidInjectionException} raised during INJECT_PREPARE — <b>fatal</b>, aborting mod
 * loading before the client reaches the title screen. {@code require = 0} does not help: it
 * suppresses "no injection point matched" and nothing else. This class shipped briefly without that
 * gate and crashed every Sodium and Iris install; {@code VanillaRendererMixinPlugin} is what stands
 * it down now, and nothing is lost by it — Sodium replaces this whole path anyway.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrustumRefreshMixin {

    /**
     * Recompute the seal from the camera this frame's frustum is about to be built from.
     *
     * <p>{@code ClientPortalSeal.beginFrame} runs from {@code ViewportEvent.ComputeCameraAngles}, and
     * on the frame a portal swap lands that event sees the camera before it has moved. The cut it
     * leaves behind is the on-train one — hide everything at or below bedrock — while the camera is
     * already below bedrock, so {@code applyFrustum} throws away precisely the room the player is
     * standing in, for exactly one frame. Measured: {@code cut=32..MAX} with {@code camY=-51.4},
     * every one of the frame's frustum rejections the seal's, and none on the frame after.</p>
     *
     * <p>This is the same {@link Camera} object, read at the point that matters. Unconditional and
     * every frame: it is the once-a-frame work the camera event already does, and doing it twice on
     * an ordinary frame costs a config read and four comparisons.</p>
     */
    @Inject(method = "setupRender", at = @At("HEAD"), require = 0)
    private void dungeontrain$sealFromThisFramesCamera(Camera camera, Frustum frustum,
                                                       boolean hasCapturedFrustum, boolean isSpectator,
                                                       CallbackInfo ci) {
        ClientPortalSeal.beginFrame(camera.getPosition().x, camera.getPosition().y);
    }

    @ModifyExpressionValue(
        method = "setupRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;consumeFrustumUpdate()Z"),
        require = 0)
    private boolean dungeontrain$refreshFrustumWhileArriving(boolean original) {
        if (original || !ClientPortalSwap.inArrivalWindow()) return original;
        // Said out loud rather than assumed: require = 0 means a mixin that never attached looks
        // exactly like one that works, and this hook forcing the re-derive is half of what the
        // arrival trace is trying to establish.
        PortalArrivalTrace.noteForced();
        return true;
    }
}
