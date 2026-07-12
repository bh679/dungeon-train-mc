package games.brennan.dungeontrain.platform.event;

import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;

/**
 * Loader-neutral registrar handed to a {@link DtGuiLayerRegistrationCallback}. It
 * records a HUD overlay layer and its <b>anchor relative to the vanilla layer
 * stack</b> declaratively (layer id + anchor), so a Fabric bridge can reproduce the
 * ordering with {@code HudRenderCallback} / {@code HudLayerRegistrationCallback} in
 * a later stage. Mirrors NeoForge's {@code RegisterGuiLayersEvent} surface.
 *
 * <p>{@link LayeredDraw.Layer} and {@link ResourceLocation} are vanilla client
 * types available in {@code :common}. DT only uses {@link #registerAboveAll}; the
 * other anchors are declared for completeness so a future handler need not touch
 * the bridge.</p>
 */
public interface DtGuiLayerRegistrar {

    /** Draw this layer above every vanilla layer (NeoForge {@code registerAboveAll}). */
    void registerAboveAll(ResourceLocation id, LayeredDraw.Layer layer);

    /** Draw this layer below every vanilla layer (NeoForge {@code registerBelowAll}). */
    void registerBelowAll(ResourceLocation id, LayeredDraw.Layer layer);

    /** Draw this layer directly above the named vanilla layer (NeoForge {@code registerAbove}). */
    void registerAbove(ResourceLocation vanillaLayer, ResourceLocation id, LayeredDraw.Layer layer);

    /** Draw this layer directly below the named vanilla layer (NeoForge {@code registerBelow}). */
    void registerBelow(ResourceLocation vanillaLayer, ResourceLocation id, LayeredDraw.Layer layer);
}
