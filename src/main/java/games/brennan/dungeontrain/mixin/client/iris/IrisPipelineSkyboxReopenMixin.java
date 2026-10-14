package games.brennan.dungeontrain.mixin.client.iris;

import games.brennan.dungeontrain.client.skybox.SkyboxPunchRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the skybox hole reopen at the one point in an Iris frame that is after every opaque draw and
 * before the pack's deferred pass.
 *
 * <p>Iris replaces vanilla's entity buffer source with a fully buffered one and flushes it from its
 * own {@code renderLevel} callback at the {@code "translucent"} profiler constant — the same
 * instruction its second callback starts the deferred pass from. Nothing vanilla sits between the
 * two, so no {@code RenderLevelStageEvent} and no vanilla instruction can host the reopen: at
 * {@code AFTER_BLOCK_ENTITIES} it ran before any entity had been drawn, and mobs behind a skybox
 * block showed through it. The head of {@code beginTranslucents} is after Iris' flush (and its
 * solid hand draw) and before anything deferred, on Fancy and Fabulous alike.</p>
 *
 * <p>The tail of the same call is where the holes' real depth goes back
 * ({@link SkyboxPunchRenderer#restoreHoleDepth()}): the deferred pass has painted its sky, and Iris'
 * <em>translucent</em> entity flush — the second half of its {@code "translucent"} callback — has not
 * run yet. Restoring only at vanilla's {@code RenderType.translucent()} call, which comes after that
 * flush, left translucent entity layers behind a skybox block drawing against a hole still at the
 * far plane (a floating villager showed as a faint silhouette through a live-sky wall). The vanilla
 * anchor in {@code LevelRendererSkyboxDepthMixin} stays as a no-op fallback.</p>
 *
 * <p>Gated on Iris being present by {@code IrisMixinPlugin}; Iris stays off the compile classpath.
 * Both calls are no-ops unless {@code AFTER_BLOCK_ENTITIES} captured a frame, so a call from any
 * other pipeline state does nothing.</p>
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineSkyboxReopenMixin {

    @Inject(method = "beginTranslucents", at = @At("HEAD"))
    private void dungeontrain$reopenSkyboxHoles(CallbackInfo ci) {
        SkyboxPunchRenderer.runPendingReopen();
    }

    @Inject(method = "beginTranslucents", at = @At("TAIL"))
    private void dungeontrain$restoreSkyboxHoleDepth(CallbackInfo ci) {
        SkyboxPunchRenderer.restoreHoleDepth();
    }
}
