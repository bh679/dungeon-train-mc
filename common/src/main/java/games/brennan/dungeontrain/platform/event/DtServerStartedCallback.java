package games.brennan.dungeontrain.platform.event;

import net.minecraft.server.MinecraftServer;

/**
 * Loader-neutral form of NeoForge's {@code ServerStartedEvent}: fired on the
 * server thread once the server has fully started (after {@code ServerStarting}).
 * Not cancellable; exposes only {@link MinecraftServer}. DT handlers use NORMAL
 * and LOW tiers — the bridge fires each under a matching {@code @SubscribeEvent}
 * priority.
 *
 * @param server the started server (matches {@code ServerStartedEvent.getServer()})
 */
@FunctionalInterface
public interface DtServerStartedCallback {
    void onServerStarted(MinecraftServer server);
}
