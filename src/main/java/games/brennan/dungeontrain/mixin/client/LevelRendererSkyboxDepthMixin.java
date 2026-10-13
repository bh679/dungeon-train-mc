package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.skybox.SkyboxPunchRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the two halves of the skybox hole reopen the exact instructions in the frame where they
 * can run — neither has a NeoForge stage.
 *
 * <p>A skybox hole has to read as sky to a shader pack's deferred pass, which means its depth must
 * be at the far plane when that pass runs, and it has to occlude translucent geometry behind it,
 * which means its depth must be the block's when the translucent layer draws. Those two demands are
 * minutes apart in wall-clock terms and adjacent in the frame: NeoForge dispatches no stage between
 * them, so there is nothing to subscribe to.</p>
 *
 * <p><b>Reopen</b> ({@link SkyboxPunchRenderer#runPendingReopen()}) lives in the Iris-gated
 * {@code client.iris.IrisPipelineSkyboxReopenMixin}, at the head of Iris' own
 * {@code beginTranslucents}. There is no vanilla instruction it could sit on: Iris swaps in a fully
 * buffered entity source that holds <em>every</em> entity back and flushes it from its own callback
 * at the {@code "translucent"} profiler constant — the same instruction its deferred pass starts
 * at. A reopen at {@code AFTER_BLOCK_ENTITIES} therefore ran before any entity was drawn, and a mob
 * behind a skybox block passed the depth test against a hole already at the far plane and showed
 * through the block (Complementary Unbound, 2026-10-13); and the next vanilla flush after the stage
 * differs between Fancy and Fabulous. Iris' pipeline call is the one point that is after its flush
 * and before its deferred pass on every path.</p>
 *
 * <p><b>Restore</b> ({@link SkyboxPunchRenderer#restoreHoleDepth()}): {@code RenderType.translucent()}
 * is evaluated as the argument to the translucent {@code renderSectionLayer} call, on the line after
 * that profiler constant. Injecting on that call lands after the pack has painted the sky and
 * before anything translucent is drawn — which is precisely the window.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyboxDepthMixin {

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;translucent()Lnet/minecraft/client/renderer/RenderType;"
            )
    )
    private void dungeontrain$restoreSkyboxHoleDepth(CallbackInfo ci) {
        SkyboxPunchRenderer.restoreHoleDepth();
    }
}
