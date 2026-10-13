package games.brennan.dungeontrain.mixin.client.audio;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.live.audio.AudioMirror;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * While a stream with sound is live ({@link AudioMirror#live()}), a sound whose slider is at 0 — its
 * category's, or master — still plays: silently on the streamer's speakers (gain 0, or listener gain
 * 0) and at full volume on the stream. Vanilla would skip it, or stop it when the slider hits 0, and
 * the stream would never hear it. With no stream live every hook here is one volatile read and
 * vanilla runs unchanged.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineStreamVolumeMixin {

    @Shadow private boolean loaded;
    @Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Shadow
    protected abstract float calculateVolume(SoundInstance sound);

    /** play(): "volume was zero" — start it anyway, silent on the speakers. */
    @ModifyExpressionValue(method = "play",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/SoundInstance;canStartSilent()Z"))
    private boolean dungeontrain$startSilentWhileStreaming(boolean original) {
        return original || AudioMirror.live();
    }

    /** play(): "master volume was zero" — start it anyway; the speakers' listener gain keeps it silent. */
    @ModifyExpressionValue(method = "play",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Listener;getGain()F"))
    private float dungeontrain$ignoreMutedMasterWhileStreaming(float original) {
        return original <= 0.0F && AudioMirror.live() ? 1.0F : original;
    }

    /** tickNonPaused(): a sound whose category slider is 0 is stopped and dropped — keep it, silent. */
    @ModifyExpressionValue(method = "tickNonPaused",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getSoundSourceVolume(Lnet/minecraft/sounds/SoundSource;)F"))
    private float dungeontrain$keepMutedCategoryWhileStreaming(float original) {
        return original <= 0.0F && AudioMirror.live() ? Float.MIN_VALUE : original;
    }

    /** updateCategoryVolume(): a slider moved to 0 stops its sounds — set them silent instead. */
    @Inject(method = "updateCategoryVolume", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$silenceInsteadOfStop(SoundSource category, float volume, CallbackInfo ci) {
        if (!loaded || category == SoundSource.MASTER || !AudioMirror.live()) return;
        instanceToChannel.forEach((sound, handle) -> {
            float gain = calculateVolume(sound);
            handle.execute(channel -> channel.setVolume(gain));
        });
        ci.cancel();
    }
}
