package games.brennan.dungeontrain.platform.event;

import net.minecraft.server.MinecraftServer;

/**
 * Loader-neutral form of NeoForge's {@code ServerStoppingEvent}: fired on the
 * server thread when the server begins shutting down, before worlds are saved
 * and unloaded. Not cancellable; exposes only {@link MinecraftServer}. DT
 * handlers use NORMAL and LOWEST tiers — the bridge fires each under a matching
 * {@code @SubscribeEvent} priority.
 *
 * @param server the stopping server (matches {@code ServerStoppingEvent.getServer()})
 */
@FunctionalInterface
public interface DtServerStoppingCallback {
    void onServerStopping(MinecraftServer server);
}
