package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import javax.annotation.Nullable;

/**
 * Reaches {@code ViewArea.getRenderSectionAt}, which is protected — the one lookup a portal prewarm
 * needs to turn a destination section into the {@code RenderSection} the chunk builder works on.
 *
 * <p>It answers null for anything outside the render distance, which is the honest answer for a
 * destination the player is too far from to be about to walk into, and the ticker treats it as
 * "nothing to do here" for exactly that reason.</p>
 */
@Mixin(ViewArea.class)
public interface ViewAreaPrewarmInvoker {

    @Nullable
    @Invoker("getRenderSectionAt")
    SectionRenderDispatcher.RenderSection dungeontrain$getRenderSectionAt(BlockPos pos);
}
