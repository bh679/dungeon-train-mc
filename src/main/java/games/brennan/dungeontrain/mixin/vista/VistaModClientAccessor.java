package games.brennan.dungeontrain.mixin.vista;

import net.mehvahdjukaar.vista.VistaModClient;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/**
 * Hands Vista an ffmpeg DT installed itself ({@code client/live/FfmpegInstaller}): Vista keeps the
 * one future every TV and the Live Feed read, and has no public setter for it.
 */
@Mixin(value = VistaModClient.class, remap = false)
public interface VistaModClientAccessor {

    @Accessor("ffmpegFuture")
    static void dungeontrain$setFfmpegFuture(CompletableFuture<FFmpeg> future) {
        throw new AssertionError();
    }
}
