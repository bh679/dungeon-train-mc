package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The entity-outline target, which every outline render type draws into. Chest Sense points it at its
 * own target for the length of one batch ({@code client/ChestSenseGlow}), so its glow gets its own,
 * thicker edge pass and glowing mobs keep vanilla's.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererEntityTargetAccessor {

    @Accessor("entityTarget")
    RenderTarget dungeontrain$entityTarget();

    @Accessor("entityTarget")
    void dungeontrain$setEntityTarget(RenderTarget target);
}
