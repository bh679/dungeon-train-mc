package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What Sable is holding on disk, tracked by Dungeon Train because Sable's own in-memory record
 * is not durable.
 *
 * <p><b>Why this exists.</b> When Sable culls a sub-level it files the data in a holding chunk
 * and registers it in {@code SubLevelHoldingChunkMap.allHoldingSubLevels}. But
 * {@code saveAll()} — every autosave, every Esc-menu pause, every {@code /save-all} — writes
 * those holding chunks to disk and then <em>evicts</em> the hidden ones, calling
 * {@code allHoldingSubLevels.remove(uuid)} for each sub-level in them. The data stays on disk
 * and Sable will happily resurrect it when the chunk loads again, but until then the in-memory
 * map has forgotten it. {@link SableShipyard#isHeld} reads that map, so without this index it
 * answers "gone for good" about a carriage group that is merely asleep — and
 * {@code TrainCarriageAppender} then reaps the anchor and respawns an identical group, which
 * collides with the original when Sable brings it back. That is the duplicate-overlapping-
 * carriages bug.</p>
 *
 * <p><b>How it is fed.</b> {@code SubLevelHoldingChunkFileMixin} calls {@link #filed} from
 * {@code SubLevelHoldingChunk.acceptHoldingSubLevel}, which is the one method every filing path
 * goes through — the physics-ticket cull ({@code moveToUnloaded}), the chunk-status cull
 * ({@code processUnload}, which files inline and never calls {@code moveToUnloaded}), and the
 * re-materialise-from-disk path ({@code getOrLoadHoldingChunk}). {@code
 * SubLevelHoldingChunkMapLoadMixin} calls {@link #loaded} from {@code loadHoldingSubLevel},
 * the one funnel every resurrection goes through.</p>
 *
 * <p><b>Ordering.</b> Both cull paths call {@code acceptHoldingSubLevel} strictly before
 * {@code container.removeSubLevel(..., UNLOADED)}, and {@code loadHoldingSubLevel} clears its
 * entry after {@code fullyLoad} has already added the new instance to the container. So there
 * is never a tick-instant in which a group is absent from both {@code findAll()} and this
 * index. That is the whole correctness argument — do not reorder the injection points.</p>
 *
 * <p><b>Bounded claims.</b> Claiming a group is recoverable and then never recovering it would
 * park the backward spawn lane forever ({@code decideFrontierAction} returns RELOAD_DEFER while
 * held), which is worse than the duplicate. So every failed reload calls {@link #recordFailure},
 * and after {@link #MAX_RECOVERY_ATTEMPTS} the claim is retracted: {@code isHeld} goes false,
 * the anchor becomes reapable and the lane resumes with today's behaviour.</p>
 *
 * <p><b>Sable-version coupling.</b> Verified against {@code sable-2.0.5+mc1.21.1}. Re-verify
 * both mixin targets on every {@code sable_version} bump.</p>
 *
 * <p>Server thread only, like every other Dungeon Train registry; the concurrent map is for
 * consistency rather than contention.</p>
 */
public final class SableHoldingIndex {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How many failed recovery attempts before the claim is retracted. Small on purpose: with a
     * 40-tick re-issue interval this burns in ~2 seconds of a genuinely unrecoverable group.
     */
    public static final int MAX_RECOVERY_ATTEMPTS = 3;

    /**
     * Where Sable filed each held sub-level, and how many times DT has failed to get it back.
     *
     * <p><b>Only ever write this map with a plain {@code put} / {@code remove}. Never
     * {@code compute*}.</b> {@link SableShipyard#reloadFromHolding} probes the holding store,
     * which calls {@code getOrLoadHoldingChunk} → {@code acceptHoldingSubLevel} → the mixin →
     * {@link #filed} — a write into this map from inside one of its own read paths. A
     * {@code compute} on the same key would throw or livelock. The re-entrancy is deliberate and
     * useful: the probe re-files every sibling in the chunk, so the index repairs itself after a
     * save-time eviction.</p>
     */
    private static final Map<UUID, Entry> HELD = new ConcurrentHashMap<>();

    /** Set when DT lacks the reflective handle it would need to act on a claim (see {@link #disable}). */
    private static volatile boolean enabled = true;

    private static final AtomicInteger FILED_COUNT = new AtomicInteger();
    private static final AtomicInteger GAVE_UP_COUNT = new AtomicInteger();

    /** A held sub-level: the holding chunk Sable filed it in, and DT's failed-recovery count. */
    private record Entry(ChunkPos pos, int failures) {}

    private SableHoldingIndex() {}

    /**
     * Record that Sable filed {@code subLevelId} into the holding chunk at {@code pos}.
     * Last write wins — a re-file after a disk re-materialise is the authoritative position.
     */
    public static void filed(UUID subLevelId, ChunkPos pos) {
        if (!enabled || subLevelId == null || pos == null) return;
        HELD.put(subLevelId, new Entry(pos, 0)); // plain put — see the field javadoc
        FILED_COUNT.incrementAndGet();
    }

    /**
     * Record that Sable has taken {@code subLevelId} back out of holding. Called for BOTH a
     * successful load and a failed one: {@code loadHoldingSubLevel} drops its own entry
     * unconditionally, so after a failed {@code fullyLoad} the data is unreachable through
     * Sable's holding store and the anchor must be allowed to become reapable.
     */
    public static void loaded(UUID subLevelId) {
        if (subLevelId == null) return;
        HELD.remove(subLevelId);
    }

    /** Drop any claim on {@code subLevelId} without implying it was loaded. */
    public static void forget(UUID subLevelId) {
        if (subLevelId == null) return;
        HELD.remove(subLevelId);
    }

    /** Whether Sable should still have {@code subLevelId} on disk. */
    public static boolean contains(UUID subLevelId) {
        return subLevelId != null && HELD.containsKey(subLevelId);
    }

    /** The holding chunk {@code subLevelId} was filed in, or null if it is not claimed. */
    @Nullable
    public static ChunkPos chunkOf(UUID subLevelId) {
        if (subLevelId == null) return null;
        Entry e = HELD.get(subLevelId);
        return (e == null) ? null : e.pos();
    }

    /**
     * Note that a recovery attempt for {@code subLevelId} failed. At
     * {@link #MAX_RECOVERY_ATTEMPTS} the claim is retracted so the anchor can be reaped and
     * respawned instead of parking the lane forever.
     *
     * <p>Never creates an entry for an unclaimed id — a failure against something this index
     * never claimed says nothing about disk.</p>
     */
    public static void recordFailure(UUID subLevelId) {
        if (subLevelId == null) return;
        Entry e = HELD.get(subLevelId);
        if (e == null) return;
        int failures = e.failures() + 1;
        if (failures >= MAX_RECOVERY_ATTEMPTS) {
            giveUp(subLevelId, "exhausted " + MAX_RECOVERY_ATTEMPTS + " recovery attempts");
            return;
        }
        HELD.put(subLevelId, new Entry(e.pos(), failures));
    }

    /** How many failed recovery attempts {@code subLevelId} has accumulated (0 when unclaimed). */
    public static int failures(UUID subLevelId) {
        if (subLevelId == null) return 0;
        Entry e = HELD.get(subLevelId);
        return (e == null) ? 0 : e.failures();
    }

    /**
     * Retract the claim on {@code subLevelId}: DT can no longer get it back, so it must stop
     * telling the spawn lanes to wait for it.
     */
    public static void giveUp(UUID subLevelId, String why) {
        if (subLevelId == null) return;
        if (HELD.remove(subLevelId) != null) {
            GAVE_UP_COUNT.incrementAndGet();
            LOGGER.warn("[Sable] Giving up on held sub-level {} ({}) — its anchor may now be reaped and respawned",
                subLevelId, why);
        }
    }

    /**
     * Stop tracking anything. Mandatory on server stop: a singleplayer world switch reuses the
     * JVM, so world A's sub-level ids would otherwise answer {@code isHeld} for world B.
     */
    public static void clear() {
        int n = HELD.size();
        HELD.clear();
        if (n > 0) LOGGER.debug("[Sable] Cleared {} held sub-level record(s)", n);
    }

    /**
     * Give up on the index entirely. Called when {@code getOrLoadHoldingChunk} is unreachable:
     * DT must not claim a group is recoverable when it has no way to recover it. Behaviour falls
     * back to reading Sable's in-memory map alone — the pre-fix behaviour, which can duplicate a
     * carriage across a save, but is at least not a deadlock.
     */
    public static void disable() {
        setEnabled(false);
    }

    /**
     * Set the enabled flag. In production this only ever goes false, from {@link SableShipyard}'s
     * static guard; it is package-private rather than private so tests can restore it.
     */
    static void setEnabled(boolean value) {
        enabled = value;
        if (!value) HELD.clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** How many sub-levels are currently claimed as on-disk. For the debug readout. */
    public static int size() {
        return HELD.size();
    }

    /** Total files recorded this session. For the debug readout. */
    public static int filedCount() {
        return FILED_COUNT.get();
    }

    /** Total claims retracted this session. For the debug readout. */
    public static int gaveUpCount() {
        return GAVE_UP_COUNT.get();
    }
}
