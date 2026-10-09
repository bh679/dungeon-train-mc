package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ViewfinderSelfieHint;
import io.github.mortuusars.exposure.client.camera.viewfinder.ViewfinderOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the selfie hotkey hint ({@link ViewfinderSelfieHint}) on top of Exposure's viewfinder frame.
 *
 * <p>A mixin rather than a GUI layer: Exposure's {@code GuiMixin} draws this overlay at the head of
 * {@code Gui.render} and then cancels the rest of the HUD (its "hide HUD while in viewfinder" option,
 * on by default), so no DT layer would ever be drawn while looking through the camera.</p>
 */
@Mixin(value = ViewfinderOverlay.class, remap = false)
public abstract class ViewfinderOverlaySelfieHintMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void dungeontrain$drawSelfieHint(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ViewfinderSelfieHint.render(graphics, (ViewfinderOverlay) (Object) this);
    }
}
