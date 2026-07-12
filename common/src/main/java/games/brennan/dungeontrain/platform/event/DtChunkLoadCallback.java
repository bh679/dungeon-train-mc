package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Loader-neutral form of NeoForge's {@code ChunkEvent.Load}: fired when a chunk is
 * loaded, on both client and server (handlers self-filter with
 * {@code instanceof ServerLevel}). Not cancellable; read-only. All six DT handlers
 * were NORMAL priority. (DT has no {@code ChunkEvent.Unload} handlers.)
 *
 * @param level    the level the chunk loaded into (matches {@code getLevel()})
 * @param chunk    the loaded chunk (matches {@code getChunk()})
 * @param newChunk whether this is a freshly generated chunk (matches {@code Load.isNewChunk()})
 */
@FunctionalInterface
public interface DtChunkLoadCallback {
    void onChunkLoad(LevelAccessor level, ChunkAccess chunk, boolean newChunk);
}
