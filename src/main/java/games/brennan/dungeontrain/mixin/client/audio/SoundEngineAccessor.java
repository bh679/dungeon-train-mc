package games.brennan.dungeontrain.mixin.client.audio;

import com.mojang.blaze3d.audio.Listener;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The engine parts the stream audio mirror ({@code AudioMirror}) needs at go-live: the decoded-sound
 * cache, the playing channels, the listener, and the sound thread to run on.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {

    @Accessor("soundBuffers")
    SoundBufferLibrary dungeontrain$soundBuffers();

    @Accessor("channelAccess")
    ChannelAccess dungeontrain$channelAccess();

    @Accessor("listener")
    Listener dungeontrain$listener();

    @Accessor("executor")
    SoundEngineExecutor dungeontrain$executor();
}
