package games.brennan.dungeontrain.mixin.client.musicmods;

import games.brennan.dungeontrain.client.live.StreamHiddenOverlays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Show the Lyrics 3D lyrics in the world while this client streams. See {@link StreamHiddenOverlays}. */
@Pseudo
@Mixin(targets = "com.showthelyrics.render.Lyrics3DRenderer", remap = false)
public abstract class ShowTheLyricsHideMixin {

    @Inject(method = "render(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void dungeontrain$hideWhileStreaming(CallbackInfo ci) {
        if (StreamHiddenOverlays.hidden()) ci.cancel();
    }
}
