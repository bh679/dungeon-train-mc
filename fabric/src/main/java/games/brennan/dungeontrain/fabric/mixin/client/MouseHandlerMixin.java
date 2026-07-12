package games.brennan.dungeontrain.fabric.mixin.client;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtMouseButtonCallback;
import games.brennan.dungeontrain.platform.event.DtMouseScrollCallback;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.MOUSE_BUTTON_PRE} + {@code MOUSE_SCROLL} (NeoForge
 * {@code InputEvent.MouseButton.Pre} / {@code InputEvent.MouseScrollingEvent}, both
 * cancellable). Fabric has no pre-routing mouse events; fires at the HEAD of
 * {@code MouseHandler.onPress}/{@code onScroll} and cancels on the first handler that
 * returns {@code true} (editor menus + cinematic input). Handlers self-guard on the screen.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$mousePress(long window, int button, int action, int mods, CallbackInfo ci) {
        for (DtMouseButtonCallback cb : DtEvents.MOUSE_BUTTON_PRE.listeners()) {
            if (cb.onMouseButton(button, action, mods)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$mouseScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        for (DtMouseScrollCallback cb : DtEvents.MOUSE_SCROLL.listeners()) {
            if (cb.onMouseScroll(xOffset, yOffset)) {
                ci.cancel();
                return;
            }
        }
    }
}
