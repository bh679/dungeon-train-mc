package games.brennan.dungeontrain.mixin.client.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The decoded-sound cache and where it reads from, so the stream audio mirror can re-decode sounds
 * the engine uploaded before the stream started ({@code MirrorPredecoder}).
 */
@Mixin(SoundBufferLibrary.class)
public interface SoundBufferLibraryAccessor {

    @Accessor("cache")
    Map<ResourceLocation, CompletableFuture<SoundBuffer>> dungeontrain$cache();

    @Accessor("resourceManager")
    ResourceProvider dungeontrain$resourceManager();
}
