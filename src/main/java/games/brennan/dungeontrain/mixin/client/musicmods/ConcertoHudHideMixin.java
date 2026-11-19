package games.brennan.dungeontrain.mixin.client.musicmods;

import games.brennan.dungeontrain.client.live.StreamHiddenOverlays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Concerto in-game player HUD (song, cover, lyrics) while this client streams. Concerto draws it
 * from its own {@code ChatComponent} mixin through this one static call. See {@link StreamHiddenOverlays}.
 */
@Pseudo
@Mixin(targets = "top.gregtao.concerto.screen.InGameHudRenderer", remap = false)
public abstract class ConcertoHudHideMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void dungeontrain$hideWhileStreaming(CallbackInfo ci) {
        if (StreamHiddenOverlays.hidden()) ci.cancel();
    }
}
