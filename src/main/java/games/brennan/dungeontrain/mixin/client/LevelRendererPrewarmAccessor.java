package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;

/**
 * Read-only access to the two private fields a portal prewarm needs — the grid of render sections and
 * the builder that fills them.
 *
 * <p>{@code PortalPrewarmTicker} does with them exactly what vanilla's own
 * {@code compileSections} does at the end of a frame ({@code rebuildSectionAsync} then
 * {@code setNotDirty}); the only difference is which sections it picks. Nothing is written and no
 * behaviour is changed, so this is an accessor rather than an injection.</p>
 *
 * <p>Both are null before a level is loaded and between level changes, which the ticker treats as
 * "nothing to prewarm" rather than as an error.</p>
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererPrewarmAccessor {

    @Nullable
    @Accessor("viewArea")
    ViewArea dungeontrain$viewArea();

    @Nullable
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher dungeontrain$sectionRenderDispatcher();
}
