package games.brennan.dungeontrain.mixin.client.musicmods;

import games.brennan.dungeontrain.client.live.StreamHiddenOverlays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Dango Music HUD overlay or 3D lyrics while this client streams. Both are cancelled at HEAD,
 * before Dango pushes any render state, so nothing is left half-set. See {@link StreamHiddenOverlays}.
 */
@Pseudo
@Mixin(targets = "me.sorihiko.dango.modern.ModernDango", remap = false)
public abstract class DangoMusicHideMixin {

    @Inject(method = "hudOverlay(Ljava/lang/Object;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void dungeontrain$hideHud(Object graphics, CallbackInfo ci) {
        if (StreamHiddenOverlays.hidden()) ci.cancel();
    }

    @Inject(method = "worldRenderEnd()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void dungeontrain$hideWorld(CallbackInfo ci) {
        if (StreamHiddenOverlays.hidden()) ci.cancel();
    }
}
