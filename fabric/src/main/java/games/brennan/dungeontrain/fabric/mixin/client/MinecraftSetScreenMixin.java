package games.brennan.dungeontrain.fabric.mixin.client;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtScreenOpening;
import games.brennan.dungeontrain.platform.event.DtScreenOpeningCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.SCREEN_OPENING} (NeoForge {@code ScreenEvent.Opening},
 * cancellable + screen-replacing). Fabric has no screen-opening event; fires at the HEAD
 * of {@code Minecraft.setScreen}. A cancel suppresses the open; a replacement re-enters
 * {@code setScreen} with the new screen (guarded against recursion) and cancels the original.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSetScreenMixin {

    @Shadow public Screen screen;

    @Unique
    private boolean dungeonTrain$reentrant;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$screenOpening(Screen newScreen, CallbackInfo ci) {
        if (dungeonTrain$reentrant || DtEvents.SCREEN_OPENING.isEmpty()) {
            return;
        }
        final Screen current = this.screen;
        final Screen[] holder = { newScreen };
        final boolean[] canceled = { false };
        DtScreenOpening carrier = new DtScreenOpening() {
            @Override public Screen getScreen() { return newScreen; }
            @Override public Screen getCurrentScreen() { return current; }
            @Override public Screen getNewScreen() { return holder[0]; }
            @Override public void setNewScreen(Screen s) { holder[0] = s; }
            @Override public void setCanceled(boolean c) { canceled[0] = c; }
        };
        for (DtScreenOpeningCallback cb : DtEvents.SCREEN_OPENING.listeners()) {
            if (canceled[0]) {
                break;
            }
            cb.onScreenOpening(carrier);
        }
        if (canceled[0]) {
            ci.cancel();
            return;
        }
        if (holder[0] != newScreen) {
            dungeonTrain$reentrant = true;
            try {
                ((Minecraft) (Object) this).setScreen(holder[0]);
            } finally {
                dungeonTrain$reentrant = false;
            }
            ci.cancel();
        }
    }
}
