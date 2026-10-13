package games.brennan.dungeontrain.mixin.client.audio;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.audio.SoundBuffer;
import games.brennan.dungeontrain.client.live.audio.AudioMirror;
import games.brennan.dungeontrain.client.live.audio.MirrorTwin;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * Remembers which sound file each decoded sound and each opened stream came from, so the stream audio
 * mirror ({@link AudioMirror}) can keep sounds from resource packs and unapproved mods off the stream
 * ({@code StreamSoundPolicy}). The engine gets back a future that completes only once the tag is on,
 * so it can never attach a sound before its origin is known.
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMirrorMixin {

    @ModifyReturnValue(method = "getCompleteBuffer", at = @At("RETURN"))
    private CompletableFuture<SoundBuffer> dungeontrain$tagBuffer(CompletableFuture<SoundBuffer> original, ResourceLocation id) {
        // A cached sound replayed: tagged the first time, nothing more to do.
        SoundBuffer done = original.isDone() && !original.isCompletedExceptionally() ? original.getNow(null) : null;
        if (done != null && ((MirrorTwin.Buffer) (Object) done).dungeontrain$source() != null) return original;
        return original.thenApply(buffer -> {
            ((MirrorTwin.Buffer) (Object) buffer).dungeontrain$setSource(id);
            return buffer;
        });
    }

    @ModifyReturnValue(method = "getStream", at = @At("RETURN"))
    private CompletableFuture<AudioStream> dungeontrain$tagStream(CompletableFuture<AudioStream> original, ResourceLocation id, boolean looping) {
        return original.thenApply(stream -> {
            AudioMirror.tagStream(stream, id);
            return stream;
        });
    }
}
