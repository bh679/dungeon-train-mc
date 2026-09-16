package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the no-sync-load guards (#1450), flushed to the log at most once a minute
 * so a player's {@code latest.log} shows whether the guards are firing without spamming it.
 *
 * <p>Two counters: lava/water interaction checks skipped ({@code FluidInteractionNoSyncLoadMixin}) and physics neighbour
 * reads answered with air ({@code LevelAcceleratorNoSyncLoadMixin}). All three fire on the server
 * thread, but the accelerator is also used from Sable worker threads, so the counters are atomic
 * and the flush is guarded by a CAS on the window start.</p>
 */
public final class NoSyncLoadStats {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long FLUSH_INTERVAL_MS = 60_000;

    private static final AtomicLong SKIPPED_INTERACTIONS = new AtomicLong();
    private static final AtomicLong AIR_CHUNK_READS = new AtomicLong();
    private static final AtomicLong WINDOW_START_MS = new AtomicLong(System.currentTimeMillis());

    private NoSyncLoadStats() {}

    public static void interactionSkipped() { bump(SKIPPED_INTERACTIONS); }

    public static void airChunkRead() { bump(AIR_CHUNK_READS); }

    private static void bump(final AtomicLong counter) {
        counter.incrementAndGet();
        final long now = System.currentTimeMillis();
        final long start = WINDOW_START_MS.get();
        if (now - start < FLUSH_INTERVAL_MS || !WINDOW_START_MS.compareAndSet(start, now)) return;
        LOGGER.info("[DungeonTrain] [fluid.nosync] last {}s: skipped {} lava/water checks, "
                        + "{} physics reads of unloaded chunks answered as air (server thread never sync-loaded a chunk for them)",
                (now - start) / 1000, SKIPPED_INTERACTIONS.getAndSet(0),
                AIR_CHUNK_READS.getAndSet(0));
    }
}
