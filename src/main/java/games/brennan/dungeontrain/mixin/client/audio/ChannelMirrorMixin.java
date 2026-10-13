package games.brennan.dungeontrain.mixin.client.audio;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import games.brennan.dungeontrain.client.live.audio.AudioMirror;
import games.brennan.dungeontrain.client.live.audio.MirrorTwin;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalInt;

/**
 * Replays every OpenAL call a sound {@link Channel} makes onto its twin in the stream's mirror
 * context ({@link AudioMirror}) — after Minecraft's own call, which runs exactly as before. With no
 * stream audio live each hook is one volatile read in {@code AudioMirror}.
 *
 * <p>Streamed sounds (music, records) are queued one-second chunk by chunk: the chunk's PCM is
 * mirrored just before vanilla uploads it and drops it.</p>
 */
@Mixin(Channel.class)
public abstract class ChannelMirrorMixin implements MirrorTwin.Channel {

    @Shadow @Final private int source;
    @Shadow @Nullable private AudioStream stream;

    @Unique private int dungeontrain$twinGen;
    @Unique private int dungeontrain$twinId;

    @Override
    public int dungeontrain$twinGen() { return dungeontrain$twinGen; }

    @Override
    public int dungeontrain$twinId() { return dungeontrain$twinId; }

    @Override
    public int dungeontrain$twinBytes() { return 0; }

    @Override
    public void dungeontrain$setTwin(int gen, int id, int bytes) {
        dungeontrain$twinGen = gen;
        dungeontrain$twinId = id;
    }

    @Override
    public int dungeontrain$speakerSource() { return source; }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void dungeontrain$mirrorDestroy(CallbackInfo ci) { AudioMirror.destroy(this); }

    @Inject(method = "play", at = @At("RETURN"))
    private void dungeontrain$mirrorPlay(CallbackInfo ci) { AudioMirror.play(this); }

    @Inject(method = "pause", at = @At("RETURN"))
    private void dungeontrain$mirrorPause(CallbackInfo ci) { AudioMirror.pause(this); }

    @Inject(method = "unpause", at = @At("RETURN"))
    private void dungeontrain$mirrorUnpause(CallbackInfo ci) { AudioMirror.unpause(this); }

    @Inject(method = "stop", at = @At("RETURN"))
    private void dungeontrain$mirrorStop(CallbackInfo ci) { AudioMirror.stop(this); }

    @Inject(method = "setSelfPosition", at = @At("RETURN"))
    private void dungeontrain$mirrorPosition(Vec3 pos, CallbackInfo ci) { AudioMirror.position(this, pos); }

    @Inject(method = "setPitch", at = @At("RETURN"))
    private void dungeontrain$mirrorPitch(float pitch, CallbackInfo ci) { AudioMirror.sourcef(this, AL10.AL_PITCH, pitch); }

    @Inject(method = "setLooping", at = @At("RETURN"))
    private void dungeontrain$mirrorLooping(boolean looping, CallbackInfo ci) {
        AudioMirror.sourcei(this, AL10.AL_LOOPING, looping ? 1 : 0);
    }

    @Inject(method = "setVolume", at = @At("RETURN"))
    private void dungeontrain$mirrorVolume(float volume, CallbackInfo ci) { AudioMirror.sourcef(this, AL10.AL_GAIN, volume); }

    @Inject(method = "disableAttenuation", at = @At("RETURN"))
    private void dungeontrain$mirrorNoAttenuation(CallbackInfo ci) {
        AudioMirror.sourcei(this, AL10.AL_DISTANCE_MODEL, AL10.AL_NONE);
    }

    @Inject(method = "linearAttenuation", at = @At("RETURN"))
    private void dungeontrain$mirrorLinearAttenuation(float distance, CallbackInfo ci) {
        AudioMirror.sourcei(this, AL10.AL_DISTANCE_MODEL, 0xD003); // AL_LINEAR_DISTANCE
        AudioMirror.sourcef(this, AL10.AL_MAX_DISTANCE, distance);
        AudioMirror.sourcef(this, AL10.AL_ROLLOFF_FACTOR, 1.0F);
        AudioMirror.sourcef(this, AL10.AL_REFERENCE_DISTANCE, 0.0F);
    }

    @Inject(method = "setRelative", at = @At("RETURN"))
    private void dungeontrain$mirrorRelative(boolean relative, CallbackInfo ci) {
        AudioMirror.sourcei(this, AL10.AL_SOURCE_RELATIVE, relative ? 1 : 0);
    }

    @Inject(method = "attachStaticBuffer", at = @At("RETURN"))
    private void dungeontrain$mirrorStaticBuffer(SoundBuffer buffer, CallbackInfo ci) { AudioMirror.attachStatic(this, buffer); }

    @Inject(method = "updateStream", at = @At("RETURN"))
    private void dungeontrain$mirrorUpdateStream(CallbackInfo ci) { AudioMirror.updateStream(this); }

    @WrapOperation(method = "pumpBuffers",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/SoundBuffer;releaseAlBuffer()Ljava/util/OptionalInt;"))
    private OptionalInt dungeontrain$mirrorStreamChunk(SoundBuffer chunk, Operation<OptionalInt> original) {
        AudioMirror.streamChunk(this, stream, (MirrorTwin.Buffer) (Object) chunk);
        return original.call(chunk);
    }
}
