package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builds the sections around the camera before the frame a portal swap lands on is drawn, so the
 * arrival shows the corridor rather than the sky through it.
 *
 * <h2>Why this is needed on top of the occlusion fix</h2>
 * <p>{@code SectionOcclusionGraphPortalSwapMixin} makes the renderer's list of visible sections correct
 * for the destination on the arrival frame. It does not make those sections <i>exist</i> as geometry: a
 * section is only ever compiled once it has been visible, and the twin's never have — it is sealed under
 * the bedrock with nothing able to see into it. So the fixed list names sections whose meshes are empty,
 * {@code compileSections} queues them for a background build, and the frame draws the skybox through
 * the gap. It only shows in one direction: going back to the train needs no build at all, because
 * riding it kept those sections compiled the whole time.</p>
 *
 * <h2>What this does</h2>
 * <p>Nothing new — it borrows vanilla's own answer to "I need this built now". The Chunk Builder option
 * has a {@link PrioritizeChunkUpdates#NEARBY} setting under which {@code compileSections} rebuilds any
 * dirty section within about 28 blocks of the camera <i>synchronously</i> and uploads it, all before the
 * terrain layers are drawn in that same frame. This reports that setting for the one frame after a swap
 * and leaves the player's actual choice alone otherwise. Roughly twenty sections, once per swap: a
 * single frame's work in exchange for the flash.</p>
 *
 * <p>{@code require = 0} on both members — a mod that replaces the chunk renderer (Sodium, which Sable
 * ships pipeline compatibility for) leaves this nothing to attach to, and vanishing quietly is the right
 * outcome there. The token is time-bounded too, so even a missed {@code RETURN} cannot leave a client
 * building chunks synchronously for good.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPortalSwapCompileMixin {

    /**
     * Vanilla asks for this setting once per dirty section, so this reads the token rather than
     * consuming it; {@link #dungeontrain$endNearbyCompile} is what ends the frame.
     */
    @ModifyExpressionValue(
        method = "compileSections",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"),
        require = 0)
    private Object dungeontrain$prioritiseNearbyOnPortalArrival(Object original) {
        // Guarded on the type as well as the token: compileSections happens to read only this one
        // option today, and a future one must not be answered with a chunk-update policy.
        if (!(original instanceof PrioritizeChunkUpdates)) return original;
        return ClientPortalSwap.wantsNearbyCompile() ? PrioritizeChunkUpdates.NEARBY : original;
    }

    @Inject(method = "compileSections", at = @At("RETURN"), require = 0)
    private void dungeontrain$endNearbyCompile(CallbackInfo ci) {
        ClientPortalSwap.finishNearbyCompile();
    }
}
