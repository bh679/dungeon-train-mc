package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.skybox.SkyboxPunchRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives {@link SkyboxPunchRenderer#restoreHoleDepth()} the one instruction in the frame where it
 * can run.
 *
 * <p>A skybox hole has to read as sky to a shader pack's deferred pass, which means its depth must
 * be at the far plane when that pass runs, and it has to occlude translucent geometry behind it,
 * which means its depth must be the block's when the translucent layer draws. Those two demands are
 * minutes apart in wall-clock terms and adjacent in the frame: NeoForge dispatches no stage between
 * them, so there is nothing to subscribe to.</p>
 *
 * <p>{@code RenderType.translucent()} is evaluated as the argument to the translucent
 * {@code renderSectionLayer} call, on the line after the {@code "translucent"} profiler constant
 * that Iris injects its deferred pass at. Injecting on that call therefore lands after the pack has
 * painted the sky and before anything translucent is drawn — which is precisely the window.</p>
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
