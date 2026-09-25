package games.brennan.dungeontrain.ship.sable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure logic behind Dungeon Train's Sable save-stall fix (#1508), kept free of Sable types so it
 * can be unit-tested. See {@code mixin/SubLevelStorageFileNoDsyncMixin} for the seam and
 * {@code mixin/SubLevelHoldingChunkMapSaveTimingMixin} for the slow-save log.
 *
 * <p>Sable's {@code SubLevelStorageFile} opens its channel with {@code DSYNC}, so every write of
 * an autosave waits for the disk, on the server thread. Dropping {@code DSYNC} while keeping the
 * {@code force()} at flush gives one sync per changed file per save instead of one per write.</p>
 */
public final class SableStorageSync {

    /** A Sable holding save slower than this is logged, with its force/skip counts. */
    public static final long SLOW_SAVE_LOG_MS = 1000L;

    private static final AtomicInteger FORCED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    private SableStorageSync() {}

    /**
     * Returns {@code options} without {@link StandardOpenOption#DSYNC}, preserving the order of
     * the rest. Never mutates the input; returns it unchanged when there is nothing to strip.
     */
    public static OpenOption[] withoutDsync(OpenOption[] options) {
        if (options == null) return null;
        if (Arrays.stream(options).noneMatch(o -> o == StandardOpenOption.DSYNC)) return options;
        return Arrays.stream(options)
            .filter(o -> o != StandardOpenOption.DSYNC)
            .toArray(OpenOption[]::new);
    }

    /** A storage file with writes since its last flush was synced to disk. */
    public static void recordForced() {
        FORCED.incrementAndGet();
    }

    /** A storage file with no writes since its last flush skipped its sync. */
    public static void recordSkipped() {
        SKIPPED.incrementAndGet();
    }

    /** Resets the per-save counters; called at the start of each holding save. */
    public static void beginSave() {
        FORCED.set(0);
        SKIPPED.set(0);
    }

    public static int forcedSinceBegin() {
        return FORCED.get();
    }

    public static int skippedSinceBegin() {
        return SKIPPED.get();
    }
}
