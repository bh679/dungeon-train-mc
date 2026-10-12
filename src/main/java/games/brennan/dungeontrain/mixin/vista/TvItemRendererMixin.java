package games.brennan.dungeontrain.mixin.vista;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.live.HeldTvScreen;
import net.mehvahdjukaar.vista.client.renderer.TvItemRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A hand-held Vista TV that is switched on shows the live feed on its screen — {@link HeldTvScreen}.
 *
 * <p>Vista's {@code renderTvHead} draws the block model and, on a head, a smile. Around it, in the
 * hand contexts only: a pose that turns the screen to the viewer before, the feed quad after. The
 * pose is pushed at HEAD and popped at TAIL, so Vista's own push/pop pair inside stays balanced.
 * Vista registers this renderer itself, so this hooks it rather than registering a second one.</p>
 */
@Mixin(value = TvItemRenderer.class, remap = false)
public abstract class TvItemRendererMixin {

    @Inject(method = "renderTvHead", at = @At("HEAD"))
    private static void dungeontrain$poseHeldScreen(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                                                    MultiBufferSource buffer, int light, int overlay, LivingEntity entity,
                                                    CallbackInfo ci) {
        if (!HeldTvScreen.shows(stack, context)) return;
        poseStack.pushPose();
        HeldTvScreen.pose(context, poseStack);
    }

    @Inject(method = "renderTvHead", at = @At("TAIL"))
    private static void dungeontrain$drawHeldScreen(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                                                    MultiBufferSource buffer, int light, int overlay, LivingEntity entity,
                                                    CallbackInfo ci) {
        if (!HeldTvScreen.shows(stack, context)) return;
        HeldTvScreen.draw(poseStack, buffer, light);
        poseStack.popPose();
    }
}
