package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.level.Level;

/**
 * Loader-neutral form of NeoForge's {@code LevelTickEvent.Post}: fired after each
 * level ticks, for BOTH client and server levels (handlers self-filter with
 * {@code instanceof ServerLevel}). Not cancellable; the only datum DT reads is
 * {@link Level}. All DT handlers were NORMAL priority — the bridge fires them in
 * registration order under a single NORMAL subscription.
 *
 * @param level the level that just ticked (matches {@code LevelTickEvent.getLevel()})
 */
@FunctionalInterface
public interface DtLevelTickCallback {
    void onLevelTick(Level level);
}
