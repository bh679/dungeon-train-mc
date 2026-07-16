package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Setter accessor on {@link Minecraft}'s private {@code mainRenderTarget}
 * field. Used by {@code RideSnapshotCapture} to temporarily redirect the world
 * render to a private off-screen {@code RenderTarget}: {@code renderLevel} (and
 * everything in {@code LevelRenderer}) binds whatever {@link Minecraft#getMainRenderTarget()}
 * returns, so swapping the field is the clean way to point that pass at our own
 * target and leave the player's on-screen frame untouched.
 *
 * <p>The vanilla field is {@code private final}, so {@link Mutable} is required
 * for Mixin to synthesise the setter. The capture always restores the real
 * target in a {@code finally} block, so the field is only ever off-main for the
 * duration of the extra render pass.</p>
 */
@Mixin(Minecraft.class)
public interface MinecraftMainRenderTargetAccessor {

    @Mutable
    @Accessor("mainRenderTarget")
    void dungeontrain$setMainRenderTarget(RenderTarget target);
}
