package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ChatClickActions;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a chat button run a DT client action instead of a command ({@code chat.ChatClick}). Runs
 * ahead of vanilla's click handling so a {@code dungeontrain:...} value is never treated as a
 * command: nothing is typed, nothing reaches the server, and there is no command to tab-complete.
 */
@Mixin(Screen.class)
public abstract class ScreenChatClickMixin {

    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$chatClickAction(Style style, CallbackInfoReturnable<Boolean> cir) {
        if (style != null && ChatClickActions.handle(style.getClickEvent())) cir.setReturnValue(true);
    }
}
