package games.brennan.dungeontrain.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.function.LongFunction;

/**
 * The terrain part of {@link UntouchedChunkSaves#verdict} for each loaded chunk: whether it is behind
 * spawn, has End islands, or is ordinary terrain. That depends only on where the chunk is for a whole
 * session, and finding it out reads the world-gen cycle, so it is worked out once per chunk instead of on
 * every save attempt. Entries leave when the chunk unloads ({@link #forget}) and at server stop.
 *
 * <p>Only the terrain is cached. Whether the option is on, the chunk was touched, a dimensional
 * carriage stands in it, or the server is stopping with a player near can all change while the chunk is
 * loaded, so {@link UntouchedChunkSaves} reads those fresh every time.</p>
 */
final class TerrainVerdictCache {

    private static final byte MISSING = -1;

    /** How a lookup uses the cache. */
    enum Use {
        /** Read it, and store a worked-out answer: periodic saves of a loaded chunk. */
        REMEMBER,
        /** Read it, store nothing: entity saves, which can run after the chunk unloaded. */
        PEEK,
        /** Work it out and store nothing: the unload save, the one that loses data if it is wrong. */
        FRESH
    }

    private final Long2ByteOpenHashMap byChunk = new Long2ByteOpenHashMap();
    private long hits;
    private long misses;

    TerrainVerdictCache() {
        byChunk.defaultReturnValue(MISSING);
    }

    /** The terrain verdict for chunk {@code key}, from the cache or {@code compute}, as {@code use} says. */
    synchronized UntouchedChunkSaves.Verdict get(long key, Use use,
                                                 LongFunction<UntouchedChunkSaves.Verdict> compute) {
        byte cached = use == Use.FRESH ? MISSING : byChunk.get(key);
        if (cached != MISSING) {
            hits++;
            return UntouchedChunkSaves.Verdict.values()[cached];
        }
        misses++;
        UntouchedChunkSaves.Verdict verdict = compute.apply(key);
        if (use == Use.REMEMBER) byChunk.put(key, (byte) verdict.ordinal());
        return verdict;
    }

    synchronized void forget(long key) {
        byChunk.remove(key);
    }

    synchronized void clear() {
        byChunk.clear();
        hits = 0L;
        misses = 0L;
    }

    synchronized int size() { return byChunk.size(); }

    synchronized long hits() { return hits; }

    synchronized long misses() { return misses; }
}
