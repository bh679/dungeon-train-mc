package games.brennan.dungeontrain.platform.event;

import net.minecraft.server.MinecraftServer;

/**
 * Loader-neutral form of NeoForge's {@code ServerStoppedEvent}: fired on the
 * server thread after the server has fully stopped. Not cancellable; exposes
 * only {@link MinecraftServer}. DT handlers use HIGHEST and NORMAL tiers — the
 * bridge fires each under a matching {@code @SubscribeEvent} priority.
 *
 * @param server the stopped server (matches {@code ServerStoppedEvent.getServer()})
 */
@FunctionalInterface
public interface DtServerStoppedCallback {
    void onServerStopped(MinecraftServer server);
}
