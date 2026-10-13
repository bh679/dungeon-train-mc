package games.brennan.dungeontrain.mixin.client.audio;

import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;
import games.brennan.dungeontrain.client.live.audio.AudioMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The streamer's ears, copied to the stream's mirror context ({@link AudioMirror}): where the camera
 * is and which way it faces. Not the master volume — the stream is heard at full volume whatever the
 * streamer's sliders say ({@code StreamVolume}).
 */
@Mixin(Listener.class)
public abstract class ListenerMirrorMixin {

    @Inject(method = "setTransform", at = @At("RETURN"))
    private void dungeontrain$mirrorTransform(ListenerTransform transform, CallbackInfo ci) {
        AudioMirror.listenerTransform(transform);
    }
}
