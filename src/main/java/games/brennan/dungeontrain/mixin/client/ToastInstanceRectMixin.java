package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.live.LiveToastRects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Records where each popup draws while live, so the stream can put it back over its name tag
 * ({@link LiveToastRects}). Reads the position vanilla has just translated to; draws nothing.
 */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastComponent$ToastInstance")
public abstract class ToastInstanceRectMixin {

    @WrapOperation(
        method = "render(ILnet/minecraft/client/gui/GuiGraphics;)Z",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/Toast;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/components/toasts/ToastComponent;J)Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;"))
    private Toast.Visibility dungeontrain$recordRect(Toast toast, GuiGraphics graphics, ToastComponent toasts,
                                                     long sinceVisible, Operation<Toast.Visibility> original) {
        if (LiveToastRects.active()) {
            Matrix4f pose = graphics.pose().last().pose();
            LiveToastRects.record(pose.m30(), pose.m31(), toast.width(), toast.height());
        }
        return original.call(toast, graphics, toasts, sinceVisible);
    }
}
