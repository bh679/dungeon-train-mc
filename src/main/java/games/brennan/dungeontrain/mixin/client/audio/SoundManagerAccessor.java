package games.brennan.dungeontrain.mixin.client.audio;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The engine behind {@link SoundManager}, for the stream audio mirror ({@code AudioMirror}). */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {

    @Accessor("soundEngine")
    SoundEngine dungeontrain$soundEngine();
}
