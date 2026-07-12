package games.brennan.dungeontrain.platform.event;

import net.minecraft.client.Camera;

/**
 * Loader-neutral form of NeoForge's {@code ViewportEvent.ComputeFogColor} (client
 * game bus / render thread). Fires while the fog colour is computed each frame. Not
 * cancellable; the handler MUTATES the fog colour through {@link DtFogColor}. The
 * {@link Camera} (vanilla) gives the render-position the handlers read. A Fabric
 * bridge supplies both from its fog hooks.
 */
@FunctionalInterface
public interface DtFogColorCallback {

    void onComputeFogColor(Camera camera, DtFogColor color);
}
