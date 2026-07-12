package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.level.LevelAccessor;

/**
 * Loader-neutral form of NeoForge's {@code LevelEvent.Unload}. Fires as a level
 * unloads (client and server; handlers self-filter via {@code instanceof
 * ServerLevel}). Not cancellable; read-only. The sole DT handler
 * ({@code WorldUpsideDownEvents}) drops the overworld's precomputed mirror-plan
 * cache. NORMAL. {@code LevelAccessor} is a vanilla type, so a Fabric bridge can
 * back this with {@code ServerWorldEvents.UNLOAD} / a client-level equivalent.
 */
@FunctionalInterface
public interface DtLevelUnloadCallback {

    void onLevelUnload(LevelAccessor level);
}
