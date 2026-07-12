package games.brennan.dungeontrain.platform.event;

import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * Loader-neutral sink for registering a {@link PreparableReloadListener} onto a
 * resource/data reload pipeline. Backs both the server-data channel (NeoForge
 * {@code AddReloadListenerEvent.addListener}) and the client-resource channel
 * ({@code RegisterClientReloadListenersEvent.registerReloadListener}); the two are
 * distinguished by which {@code DtEvents} field a handler registers on, not by the
 * sink type. {@code PreparableReloadListener} is a vanilla type, so a Fabric bridge
 * can back this with {@code ResourceManagerHelper.registerReloadListener}.
 */
@FunctionalInterface
public interface DtReloadListenerRegistrar {

    void register(PreparableReloadListener listener);
}
