package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.DeathCameraCycle;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the death moment's camera shots level. {@code GameRenderer.bobHurt} rolls the view by the
 * death tilt (and the hurt flinch) whenever the camera entity is dying; that belongs to the
 * player's own view (shot 0) only, not to a detached camera looking at the train.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererDeathTiltMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noDeathTiltOnCameraShots(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (DeathCameraCycle.isActive() && !DeathCameraCycle.isPov()) ci.cancel();
    }
}
