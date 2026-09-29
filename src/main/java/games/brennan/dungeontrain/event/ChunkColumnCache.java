package games.brennan.dungeontrain.event;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-sweep memo of block column → chunk, for server-thread code that must never block on chunk
 * loading.
 *
 * <p>Why a dedicated resolver rather than {@code level.hasChunkAt} + {@code level.getBlockState}:
 * on the server thread {@code ServerChunkCache.hasChunk} only checks that a chunk holder exists at
 * FULL ticket level, NOT that its FULL future has completed, while {@code Level.getBlockState} goes
 * through {@code getChunk(x, z, FULL, true)}, which {@code managedBlock}s until it does. For a chunk
 * still generating ahead of the train that pair blocks the whole server (a player log caught
 * {@code sweepFootprint} parked there for 377 ms). {@code getChunk(x, z, FULL, false)} blocks too —
 * {@code requireChunk=false} only skips adding a ticket. The one truly non-blocking accessor is
 * {@code ServerChunkCache.getChunkNow}, which is what production passes as the {@link Resolver}.</p>
 *
 * <p>Null results are memoised as well, so an unloaded column costs one lookup per sweep rather
 * than one per cell. Build a fresh instance per sweep: nothing may be held across ticks, both so a
 * chunk that finishes loading is picked up next tick and so no unloaded chunk is retained.</p>
 *
 * <p>Pure (no Minecraft types) so it is unit-testable; see {@code ChunkColumnCacheTest}.</p>
 *
 * @param <C> the chunk type ({@code LevelChunk} in production)
 */
final class ChunkColumnCache<C> {

    /** Non-blocking chunk lookup by chunk coordinates; returns null when the chunk is not ready. */
    @FunctionalInterface
    interface Resolver<C> {
        C getNow(int chunkX, int chunkZ);
    }

    private final Resolver<C> resolver;
    private final Map<Long, C> resolved = new HashMap<>();

    ChunkColumnCache(Resolver<C> resolver) {
        this.resolver = resolver;
    }

    /** The chunk holding block column ({@code blockX}, {@code blockZ}), or null if not loaded. */
    C get(int blockX, int blockZ) {
        return getChunk(blockX >> 4, blockZ >> 4);
    }

    /**
     * Whether every chunk holding a horizontal neighbour of block column ({@code blockX},
     * {@code blockZ}) is loaded — the precondition for a neighbour-notifying write there, since
     * vanilla's neighbour/shape updates read each neighbour through the blocking
     * {@code Level.getBlockState}. Assumes the column's own chunk was already checked. Interior
     * columns (not on a chunk edge) need no extra lookup; vertical neighbours share the column.
     */
    boolean neighboursLoaded(int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        int lx = blockX & 15;
        int lz = blockZ & 15;
        if (lx == 0 && getChunk(cx - 1, cz) == null) return false;
        if (lx == 15 && getChunk(cx + 1, cz) == null) return false;
        if (lz == 0 && getChunk(cx, cz - 1) == null) return false;
        return lz != 15 || getChunk(cx, cz + 1) != null;
    }

    private C getChunk(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (resolved.containsKey(key)) return resolved.get(key);
        C chunk = resolver.getNow(cx, cz);
        resolved.put(key, chunk);
        return chunk;
    }
}
