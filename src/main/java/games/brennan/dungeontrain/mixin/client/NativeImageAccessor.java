package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The address of a {@link NativeImage}'s pixel buffer (private in 1.21.1, no getter), so the Live Feed
 * decoder can {@code memCopy} a whole ffmpeg frame in instead of setting it pixel by pixel.
 */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {

    @Accessor("pixels")
    long dungeontrain$pixels();
}
