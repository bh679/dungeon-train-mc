package games.brennan.dungeontrain.ship.sable;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

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
    private static final AtomicLongArray PHASE_NANOS = new AtomicLongArray(SavePhase.values().length);

    /**
     * The calls {@code SubLevelHoldingChunkMap.saveAll} makes, each timed where saveAll makes it.
     * They do not overlap, so whatever the save spends outside all of them is the line's
     * {@code other}. Exists because player autosaves of Nov 2026 took 1.0 to 1.4 s while
     * serialise + write + sync came to 20 to 50 ms: the rest was in none of DT's timers.
     * The serialise/write/sync timers run <i>inside</i> these phases; they are a finer view, not
     * a further share of the total.
     */
    public enum SavePhase {
        /** {@code processChanges()}: queued sub-level loads and unloads. */
        CHANGES("changes"),
        /** {@code SubLevelHelper.getLoadingDependencyChain}, once per loaded sub-level. */
        DEPS("deps"),
        /** Both {@code moveAndSaveSubLevel} overloads: serialise and file each sub-level. */
        MOVE("move"),
        /** {@code SubLevelStorage.attemptSaveSubLevel}: the queued sub-level saves. */
        SAVE("save"),
        /** {@code attemptSaveHoldingChunk} and {@code attemptRemoveHoldingChunk}. */
        HOLDING("holding"),
        /** {@code SubLevelStorage.flush()}. */
        FLUSH("flush"),
        /** {@code SubLevelStorage.pruneCache()}. */
        PRUNE("prune"),
        /** {@code SubLevelTicketsSavedData.getOrLoad}. */
        TICKETS("tickets");

        private final String label;

        SavePhase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

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

    /** Time spent in one of {@code saveAll}'s own calls. */
    public static void addPhaseNanos(SavePhase phase, long nanos) {
        PHASE_NANOS.addAndGet(phase.ordinal(), nanos);
    }

    /** Each phase's milliseconds this save, indexed by {@link SavePhase#ordinal()}. */
    public static long[] phaseMsSinceBegin() {
        long[] ms = new long[PHASE_NANOS.length()];
        for (int i = 0; i < ms.length; i++) ms[i] = PHASE_NANOS.get(i) / 1_000_000L;
        return ms;
    }

    /**
     * The {@code phases:} part of the save line: each phase, then {@code other} (the total less
     * every phase, never negative: the phases are rounded down separately). Pure, unit-tested.
     */
    public static String formatPhases(long totalMs, long[] phaseMs) {
        SavePhase[] phases = SavePhase.values();
        if (phaseMs.length != phases.length) {
            throw new IllegalArgumentException("expected " + phases.length + " phases, got " + phaseMs.length);
        }
        StringBuilder sb = new StringBuilder("phases: ");
        long sum = 0;
        for (int i = 0; i < phases.length; i++) {
            sb.append(phases[i].label()).append(' ').append(phaseMs[i]).append(", ");
            sum += phaseMs[i];
        }
        return sb.append("other ").append(Math.max(0, totalMs - sum)).append(" ms").toString();
    }

    /**
     * Total collection time of every JVM collector so far, in ms. Read at both ends of a save so
     * the line can say how much of it was the collector: a GC landing in a save looks exactly
     * like an unexplained gap. Concurrent collectors (ZGC, Shenandoah) report cycle time rather
     * than pauses, so read it as an upper bound there.
     */
    public static long gcMillisTotal() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) total += t;
        }
        return total;
    }

    /** Resets the per-save counters; called at the start of each holding save. */
    public static void beginSave() {
        FORCED.set(0);
        SKIPPED.set(0);
        DEFERRED.set(0);
        SERIALISE_NANOS.set(0);
        WRITE_NANOS.set(0);
        SYNC_NANOS.set(0);
        for (int i = 0; i < PHASE_NANOS.length(); i++) PHASE_NANOS.set(i, 0);
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
