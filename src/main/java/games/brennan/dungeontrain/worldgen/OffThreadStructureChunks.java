package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.ChunkMapChunksInvoker;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Policy and lookups behind {@code StructureManagerNoSyncLoadMixin}: what a structure lookup made
 * <em>off</em> the server thread, through the real level's {@code StructureManager}, may do about a
 * chunk that is not loaded.
 *
 * <p><b>Why:</b> Distant Horizons generates LOD chunks on its own {@code DH-World Gen Thread}s against
 * the real {@code ServerLevel}. Its feature step hands vanilla a region-wrapped structure manager, but
 * two callers reach past it for the level's own: {@code Cat.finalizeSpawn} (a cat in a stamped
 * template asking whether it stands in a swamp hut) and Piglin Proliferation's huge-fungus mixin.
 * The level's manager calls {@code ServerChunkCache.getChunk}, which off-thread hands the load to the
 * <em>server thread</em> and waits. DH's chunks lie far outside the loaded area, so every such call
 * was a full DT chunk generation on the server thread: ~1.5 s per stall, 62 dumps across 13 players
 * in the 2026-10-28 lag sweep, the one stall class still growing on 0.1550–0.1569.</p>
 *
 * <p><b>What:</b> off the server thread, the chunk is read straight from the chunk map's visible
 * holders ({@link #loadedChunk}): present at the wanted status → that chunk, with no main-thread hop
 * at all; absent → the mixin answers with an empty stand-in, so the caller sees "no structure here".
 * On the server thread nothing changes.</p>
 */
public final class OffThreadStructureChunks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** At most one log line per level this often, so a busy LOD pass stays one line a minute. */
    static final long NOTE_INTERVAL_MS = 60_000L;

    private OffThreadStructureChunks() {}

    /**
     * @param serverLevel the manager's level is a {@code ServerLevel} (not a worldgen region)
     * @param sameThread  the caller is the server thread
     * @return {@code true} when the lookup must not be allowed to sync-load on the server thread
     */
    public static boolean shouldBypass(boolean serverLevel, boolean sameThread) {
        return serverLevel && !sameThread;
    }

    /**
     * The chunk at {@code (x, z)} if its holder already has it at {@code status} or better, read
     * from the chunk map's visible snapshot without touching the server thread. {@code null} when
     * the chunk is not loaded that far (or not at all).
     */
    @Nullable
    public static ChunkAccess loadedChunk(ServerLevel level, int x, int z, ChunkStatus status) {
        final ChunkMapChunksInvoker map = (ChunkMapChunksInvoker) level.getChunkSource().chunkMap;
        final ChunkHolder holder = map.dungeontrain$getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        return holder == null ? null : holder.getChunkIfPresent(status);
    }

    /** Rate-limited note that an off-thread lookup was answered empty instead of sync-loading. */
    public static final class SkipNote {
        private final AtomicLong lastNoteMs = new AtomicLong(0L);
        private final AtomicLong skippedSinceNote = new AtomicLong(0L);

        /** Records one skip; returns {@code true} when this one should be logged. */
        boolean record(long nowMs) {
            final long count = skippedSinceNote.incrementAndGet();
            final long last = lastNoteMs.get();
            if (nowMs - last < NOTE_INTERVAL_MS) return false;
            if (!lastNoteMs.compareAndSet(last, nowMs)) return false;
            skippedSinceNote.addAndGet(-count);
            return true;
        }

        /** Records a skip at {@code (x, z)} and logs it when the interval allows. */
        public void note(ServerLevel level, int x, int z) {
            if (!record(System.currentTimeMillis())) return;
            LOGGER.info("[DungeonTrain] Off-thread structure lookup on an unloaded chunk answered empty "
                            + "instead of loading it on the server thread: {} chunk ({}, {}) thread \"{}\"",
                    level.dimension().location(), x, z, Thread.currentThread().getName());
        }
    }
}
