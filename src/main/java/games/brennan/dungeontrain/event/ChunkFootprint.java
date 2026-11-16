package games.brennan.dungeontrain.event;

/**
 * Whether every chunk around a block column is ready, for server-thread code about to read near it
 * through the blocking {@code Level} accessors (see {@link ChunkColumnCache} for why only
 * {@code getChunkNow} is a real guard).
 *
 * <p>Pure (no Minecraft types) so it is unit-testable; see {@code ChunkFootprintTest}.</p>
 */
final class ChunkFootprint {

    private ChunkFootprint() {}

    /**
     * True when every chunk overlapping the square of {@code margin} blocks around column
     * ({@code blockX}, {@code blockZ}) resolves. A margin under 16 spans at most two chunks per axis,
     * so the four corners cover them all.
     */
    static <C> boolean ready(ChunkColumnCache.Resolver<C> resolver, int blockX, int blockZ, int margin) {
        int minCx = (blockX - margin) >> 4;
        int maxCx = (blockX + margin) >> 4;
        int minCz = (blockZ - margin) >> 4;
        int maxCz = (blockZ + margin) >> 4;
        if (resolver.getNow(minCx, minCz) == null) return false;
        if (maxCx != minCx && resolver.getNow(maxCx, minCz) == null) return false;
        if (maxCz != minCz && resolver.getNow(minCx, maxCz) == null) return false;
        return (maxCx == minCx || maxCz == minCz) || resolver.getNow(maxCx, maxCz) != null;
    }
}
