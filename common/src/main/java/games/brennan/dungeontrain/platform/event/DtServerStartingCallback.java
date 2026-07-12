package games.brennan.dungeontrain.platform.event;

import net.minecraft.server.MinecraftServer;

/**
 * Loader-neutral form of NeoForge's {@code ServerStartingEvent}: fired on the
 * server thread once per server start (integrated and dedicated), after the
 * server is constructed but before it begins ticking. Not cancellable; the only
 * datum the NeoForge event exposes is {@link MinecraftServer}. DT handlers span
 * three priority tiers (HIGHEST/HIGH/NORMAL) — the bridge fires each tier under a
 * matching {@code @SubscribeEvent} priority to keep cross-mod ordering.
 *
 * @param server the starting server (matches {@code ServerStartingEvent.getServer()})
 */
@FunctionalInterface
public interface DtServerStartingCallback {
    void onServerStarting(MinecraftServer server);
}
