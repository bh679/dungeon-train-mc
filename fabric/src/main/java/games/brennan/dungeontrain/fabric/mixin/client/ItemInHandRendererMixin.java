package games.brennan.dungeontrain.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtRenderHandCallback;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.RENDER_HAND} (NeoForge {@code RenderHandEvent},
 * cancellable). Fabric has no render-hand event; cancels the first-person hand render at
 * the HEAD of {@code ItemInHandRenderer.renderHandsWithItems} when a DT handler requests it
 * (cinematic hides the hand).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$renderHand(float partialTicks, PoseStack poseStack,
                                         MultiBufferSource.BufferSource buffer, LocalPlayer player,
                                         int combinedLight, CallbackInfo ci) {
        for (DtRenderHandCallback cb : DtEvents.RENDER_HAND.listeners()) {
            if (cb.onRenderHand()) {
                ci.cancel();
                return;
            }
        }
    }
}
