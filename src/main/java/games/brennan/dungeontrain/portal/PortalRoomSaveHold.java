package games.brennan.dungeontrain.portal;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * The overworld chunks a standing dimensional carriage stands in, which Smaller saves must always write
 * ({@code UntouchedChunkSaves}).
 *
 * <p>DT's own stamp is not a player change, so a room's chunks are never touched. Each relocation puts
 * the room in new chunks, and with Smaller saves on those were dropped once nobody was near them: they
 * came back as empty basement, and the next relocation carried the empty room on, leaving its bedrock
 * skin and corridors and nothing a player had left in it.</p>
 *
 * <p>Kept in step with {@code PortalCarriageEvents.STRUCTURES}, so only the site a room stands at now is
 * held: an erased old site is released and drops as any other chunk. Counted per chunk because two
 * pairs' boxes can share one. Synchronized: the chunk save asks from the main thread, but nothing here
 * should assume the entity save always does.</p>
 */
public final class PortalRoomSaveHold {

    private static final Map<Integer, long[]> BY_PAIR = new HashMap<>();
    private static final Long2IntOpenHashMap HELD = new Long2IntOpenHashMap();

    private PortalRoomSaveHold() {}

    /** Holds the chunks under {@code box} for {@code pairKey}, replacing whatever it held before. */
    public static synchronized void hold(int pairKey, AABB box) {
        release(pairKey);
        long[] keys = chunkKeys(box);
        BY_PAIR.put(pairKey, keys);
        for (long key : keys) HELD.addTo(key, 1);
    }

    /** Lets go of {@code pairKey}'s chunks; a chunk another pair still stands in stays held. */
    public static synchronized void release(int pairKey) {
        long[] keys = BY_PAIR.remove(pairKey);
        if (keys == null) return;
        for (long key : keys) {
            if (HELD.addTo(key, -1) <= 1) HELD.remove(key);
        }
    }

    public static synchronized boolean isHeld(long chunkKey) {
        return HELD.containsKey(chunkKey);
    }

    /** Distinct chunks held, for {@code /dungeontrain debug chunk-saves}. */
    public static synchronized int heldChunks() {
        return HELD.size();
    }

    public static synchronized void clear() {
        BY_PAIR.clear();
        HELD.clear();
    }

    /** Every chunk column {@code box} reaches into, inclusive of a box ending exactly on a chunk edge. */
    static long[] chunkKeys(AABB box) {
        int minCx = Mth.floor(box.minX) >> 4;
        int minCz = Mth.floor(box.minZ) >> 4;
        int maxCx = Mth.floor(box.maxX) >> 4;
        int maxCz = Mth.floor(box.maxZ) >> 4;
        long[] keys = new long[(maxCx - minCx + 1) * (maxCz - minCz + 1)];
        int i = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) keys[i++] = ChunkPos.asLong(cx, cz);
        }
        return keys;
    }
}
