package games.brennan.dungeontrain.fabric.mixin.client;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtKeyInputCallback;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.KEY_INPUT} (NeoForge {@code InputEvent.Key}, non-cancellable).
 * Fabric has no raw-key event; fires at the HEAD of {@code KeyboardHandler.keyPress} (before
 * screen routing — handlers self-guard on {@code Minecraft.screen}). Drives the cinematic-skip
 * key + the worldspace-menu typing buffer.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void dungeonTrain$key(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (DtEvents.KEY_INPUT.isEmpty()) {
            return;
        }
        for (DtKeyInputCallback cb : DtEvents.KEY_INPUT.listeners()) {
            cb.onKey(key, scanCode, action, modifiers);
        }
    }
}
