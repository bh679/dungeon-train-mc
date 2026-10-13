package games.brennan.dungeontrain.mixin.client.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import games.brennan.dungeontrain.client.live.audio.AudioMirror;
import games.brennan.dungeontrain.client.live.audio.MirrorTwin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * A decoded sound's twin buffer in the stream's mirror context ({@link AudioMirror}). Vanilla uploads
 * the PCM on first use and then drops it, so while stream audio is live the twin is uploaded from the
 * same PCM just before; and when vanilla deletes its buffer, the twin goes too.
 */
@Mixin(SoundBuffer.class)
public abstract class SoundBufferMirrorMixin implements MirrorTwin.Buffer {

    @Shadow @Nullable private ByteBuffer data;
    @Shadow @Final private AudioFormat format;
    @Shadow private boolean hasAlBuffer;
    @Shadow private int alBuffer;

    @Unique private int dungeontrain$twinGen;
    @Unique private int dungeontrain$twinId;
    @Unique private int dungeontrain$twinBytes;

    @Override
    public int dungeontrain$twinGen() { return dungeontrain$twinGen; }

    @Override
    public int dungeontrain$twinId() { return dungeontrain$twinId; }

    @Override
    public int dungeontrain$twinBytes() { return dungeontrain$twinBytes; }

    @Override
    public void dungeontrain$setTwin(int gen, int id, int bytes) {
        dungeontrain$twinGen = gen;
        dungeontrain$twinId = id;
        dungeontrain$twinBytes = bytes;
    }

    @Override
    public boolean dungeontrain$hasAlBuffer() { return hasAlBuffer; }

    @Override
    public int dungeontrain$alBuffer() { return alBuffer; }

    @Override
    @Nullable
    public ByteBuffer dungeontrain$data() { return data; }

    @Override
    public AudioFormat dungeontrain$format() { return format; }

    @Inject(method = "getAlBuffer", at = @At("HEAD"))
    private void dungeontrain$mirrorUpload(CallbackInfoReturnable<OptionalInt> cir) {
        if (!hasAlBuffer && data != null) AudioMirror.uploading(this);
    }

    @Inject(method = "discardAlBuffer", at = @At("HEAD"))
    private void dungeontrain$mirrorDiscard(CallbackInfo ci) {
        AudioMirror.discarded(this);
    }
}
