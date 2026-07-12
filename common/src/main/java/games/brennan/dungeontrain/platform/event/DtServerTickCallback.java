package games.brennan.dungeontrain.platform.event;

import net.minecraft.server.MinecraftServer;

/**
 * Loader-neutral form of NeoForge's {@code ServerTickEvent.Post}: fired after
 * each server tick on the server thread. Not cancellable; exposes
 * {@link MinecraftServer}. All DT handlers were NORMAL priority.
 *
 * @param server the ticking server (matches {@code ServerTickEvent.getServer()})
 */
@FunctionalInterface
public interface DtServerTickCallback {
    void onServerTick(MinecraftServer server);
}
