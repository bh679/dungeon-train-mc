package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import games.brennan.dungeontrain.client.live.EarlyToasts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the end-of-frame toast draw when a chat-off Live Eye stream already drew the popups ahead of
 * the chat layer this frame ({@link EarlyToasts}), so they are drawn once.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererToastsMixin {

    @WrapWithCondition(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lnet/minecraft/client/gui/GuiGraphics;)V"))
    private boolean dungeontrain$skipToastsDrawnEarly(ToastComponent toasts, GuiGraphics graphics) {
        return !EarlyToasts.consume();
    }
}
