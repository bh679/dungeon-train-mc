package games.brennan.dungeontrain.ship.sable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure logic behind Dungeon Train's Sable save-stall fixes (#1508 and the autosave follow-up),
 * kept free of Sable types so it can be unit-tested. See {@code mixin/SubLevelStorageFileNoDsyncMixin}
 * for the file seam, {@code mixin/ServerLevelSaveFlushFlagMixin} for the flush flag,
 * {@code mixin/SubLevelSerializerTimingMixin} for the serialise timer and
 * {@code mixin/SubLevelHoldingChunkMapSaveTimingMixin} for the slow-save log.
 *
 * <p>Sable's {@code SubLevelStorageFile} opens its channel with {@code DSYNC}, so every write of
 * an autosave waits for the disk, on the server thread. Dropping {@code DSYNC} while keeping the
 * {@code force()} at flush gives one sync per changed file per save instead of one per write.</p>
 *
 * <p>Vanilla only syncs region files on a {@code flush} save (shutdown, {@code /save-all flush});
 * the periodic autosave and the singleplayer pause save pass {@code flush=false} and leave the
 * writes in the OS cache. {@link #shouldForce()} mirrors that for Sable's files: the flag is set
 * for the duration of {@code ServerLevel.save} and defaults to {@code true} outside it, so any
 * Sable flush DT does not know about keeps syncing.</p>
 */
public final class SableStorageSync {

    /** A Sable holding save slower than this is logged, with its force/skip counts. */
    public static final long SLOW_SAVE_LOG_MS = 1000L;

    /**
     * JVM escape hatch: {@code -Ddungeontrain.sable.forceOnAutosave=true} restores the sync on
     * every save (the pre-fix behaviour). Used for the A/B measurement and as a safety switch.
     */
    public static final String FORCE_ON_AUTOSAVE_PROPERTY = "dungeontrain.sable.forceOnAutosave";

    private static final boolean FORCE_ON_AUTOSAVE = Boolean.getBoolean(FORCE_ON_AUTOSAVE_PROPERTY);

    private static final AtomicInteger FORCED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();
    private static final AtomicInteger DEFERRED = new AtomicInteger();
    private static final AtomicLong SERIALISE_NANOS = new AtomicLong();
    private static final AtomicLong WRITE_NANOS = new AtomicLong();
    private static final AtomicLong SYNC_NANOS = new AtomicLong();

    /** Whether the save in progress asked for a flush; true outside any {@code ServerLevel.save}. */
    private static volatile boolean flushRequested = true;

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

    // ---- flush flag -------------------------------------------------------------------------

    /** A {@code ServerLevel.save} began; {@code flush} is vanilla's own argument. */
    public static void beginLevelSave(boolean flush) {
        flushRequested = flush;
    }

    /** The {@code ServerLevel.save} ended; outside a save every force goes through again. */
    public static void endLevelSave() {
        flushRequested = true;
    }

    /**
     * Whether a dirty storage file should be synced to disk right now: yes on a flush save or
     * outside any save, no on an autosave (the file stays dirty for the next flush save).
     */
    public static boolean shouldForce() {
        return FORCE_ON_AUTOSAVE || flushRequested;
    }

    /** True when the escape-hatch property restored the sync-on-every-save behaviour. */
    public static boolean forcesOnAutosave() {
        return FORCE_ON_AUTOSAVE;
    }

    // ---- per-save counters ------------------------------------------------------------------

    /** A storage file with writes since its last flush was synced to disk. */
    public static void recordForced() {
        FORCED.incrementAndGet();
    }

    /** A storage file with no writes since its last flush skipped its sync. */
    public static void recordSkipped() {
        SKIPPED.incrementAndGet();
    }

    /** A dirty storage file was left in the OS cache because this save is not a flush save. */
    public static void recordDeferred() {
        DEFERRED.incrementAndGet();
    }

    /** Time spent in Sable's {@code SubLevelSerializer.toData} (live sub-level to NBT). */
    public static void addSerialiseNanos(long nanos) {
        SERIALISE_NANOS.addAndGet(nanos);
    }

    /** Time spent in {@code SubLevelStorageFile.write(int, CompoundTag)} (gzip + file writes). */
    public static void addWriteNanos(long nanos) {
        WRITE_NANOS.addAndGet(nanos);
    }

    /** Time spent in {@code FileChannel.force} at flush. */
    public static void addSyncNanos(long nanos) {
        SYNC_NANOS.addAndGet(nanos);
    }

    /** Resets the per-save counters; called at the start of each holding save. */
    public static void beginSave() {
        FORCED.set(0);
        SKIPPED.set(0);
        DEFERRED.set(0);
        SERIALISE_NANOS.set(0);
        WRITE_NANOS.set(0);
        SYNC_NANOS.set(0);
    }

    public static int forcedSinceBegin() {
        return FORCED.get();
    }

    public static int skippedSinceBegin() {
        return SKIPPED.get();
    }

    public static int deferredSinceBegin() {
        return DEFERRED.get();
    }

    public static long serialiseMsSinceBegin() {
        return SERIALISE_NANOS.get() / 1_000_000L;
    }

    public static long writeMsSinceBegin() {
        return WRITE_NANOS.get() / 1_000_000L;
    }

    public static long syncMsSinceBegin() {
        return SYNC_NANOS.get() / 1_000_000L;
    }
}
