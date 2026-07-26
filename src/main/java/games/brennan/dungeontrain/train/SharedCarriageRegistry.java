package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory registry of the shared (relay-sourced) carriages currently resident in the world, keyed by
 * their Sable sub-level UUID. A grouped train packs several carriages into one sub-level, so each key
 * maps to a small list; a change is resolved to the right carriage by its shipyard-space AABB (with a
 * fast path when a sub-level holds exactly one shared carriage).
 *
 * <p>Populated at spawn (see {@code TrainAssembler.spawnGroup}); read by the block-change hook
 * ({@code SableBlockChangeGuardMixin}) to mark a carriage dirty on a real edit, and by
 * {@code SharedCarriageEvents} to upload/lease-manage dirty carriages. Transient — server-session
 * lifetime only; a carriage culled and re-spawned re-registers under a fresh sub-level id. Cleared on
 * server stop.</p>
 */
public final class SharedCarriageRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** subLevelId → the shared carriages hosted in that sub-level (usually one; more for grouped trains). */
    private static final Map<UUID, CopyOnWriteArrayList<Instance>> BY_SUBLEVEL = new ConcurrentHashMap<>();

    private SharedCarriageRegistry() {}

    /**
     * One shared carriage instance. Identity fields are final; relay/lease state is mutable and may be
     * updated from an async relay callback thread, so those fields are {@code volatile}.
     */
    public static final class Instance {
        public final ServerLevel level;
        public final UUID subLevelId;
        public final UUID trainId;
        public final int pIdx;
        /** Per-carriage min corner in shipyard/plot space; region is [origin, origin+dims). */
        public final BlockPos shipyardOrigin;
        public final CarriageDims dims;
        public final String variantId;
        /** True once this carriage was leased from the relay pool (PR C); false for a fresh local build. */
        public final boolean leasedFromPool;

        /** Relay row id once this carriage exists on the relay (via submit or lease); null until then. */
        private volatile Integer relayId;
        /** Active lease token for save/heartbeat/return; null until submitted/leased. */
        private volatile String leaseToken;
        /**
         * Pending changed shipyard-space positions awaiting upload — deduped by pos (last-write-wins, since
         * the flush re-captures current state) and drained at flush. Replaces the old single dirty bit so
         * the flusher can send only the changed cells as a delta.
         */
        private final Set<BlockPos> outbox = ConcurrentHashMap.newKeySet();
        /**
         * Monotonic per-carriage delta sequence, tied to the RELAY carriage identity — seeded from
         * {@code max(baseSeq, maxDeltaSeq)} on a pooled lease and NEVER reset to 0 across the cull→
         * re-register churn, so a relay drop-watermark ({@code seq <= baseSeq}) stays meaningful.
         */
        private final AtomicInteger seq;
        /** Set once this carriage is being culled — the flusher stops issuing new POSTs for it. */
        private volatile boolean culled;
        /** Set when the relay asked for a re-baseline (delta log near/at full) — flusher does a full save. */
        private volatile boolean rebaseline;
        /** Guards against overlapping submit/save/delta calls for this carriage. */
        private volatile boolean callInFlight;
        /** Last successful save/heartbeat/delta wall-clock ms (for throttling). */
        private volatile long lastContactMs;

        Instance(ServerLevel level, UUID subLevelId, UUID trainId, int pIdx, BlockPos shipyardOrigin,
                 CarriageDims dims, String variantId, boolean leasedFromPool,
                 Integer relayId, String leaseToken, int seqSeed) {
            this.level = level;
            this.subLevelId = subLevelId;
            this.trainId = trainId;
            this.pIdx = pIdx;
            this.shipyardOrigin = shipyardOrigin.immutable();
            this.dims = dims;
            this.variantId = variantId;
            this.leasedFromPool = leasedFromPool;
            this.relayId = relayId;
            this.leaseToken = leaseToken;
            this.seq = new AtomicInteger(Math.max(0, seqSeed));
        }

        /** Whether shipyard-space (x,y,z) falls inside this carriage's footprint. */
        public boolean contains(int x, int y, int z) {
            int ox = shipyardOrigin.getX(), oy = shipyardOrigin.getY(), oz = shipyardOrigin.getZ();
            return x >= ox && x < ox + dims.length()
                && y >= oy && y < oy + dims.height()
                && z >= oz && z < oz + dims.width();
        }

        public Integer relayId() { return relayId; }
        public String leaseToken() { return leaseToken; }
        public boolean isOnRelay() { return relayId != null && leaseToken != null; }
        public boolean isCallInFlight() { return callInFlight; }
        public long lastContactMs() { return lastContactMs; }
        public boolean isCulled() { return culled; }
        public boolean hasPending() { return !outbox.isEmpty(); }

        /** Record a changed shipyard-space position for the next delta flush (no-op once culled). */
        public void enqueue(BlockPos pos) { if (!culled) outbox.add(pos.immutable()); }

        /**
         * Snapshot the pending positions and remove exactly those (positions enqueued concurrently
         * remain queued). The caller re-captures current block state at each; on a failed upload it
         * {@link #reenqueue}s them so nothing is lost.
         */
        public Set<BlockPos> drainPending() {
            Set<BlockPos> snap = new HashSet<>(outbox);
            outbox.removeAll(snap);
            return snap;
        }

        /** Re-queue positions whose upload failed so the next flush re-captures them. */
        public void reenqueue(Collection<BlockPos> positions) { outbox.addAll(positions); }

        /** Allocate the next strictly-increasing delta sequence (tied to the relay carriage). */
        public int nextSeq() { return seq.incrementAndGet(); }
        /** The max seq allocated so far — the {@code baseSeq} to stamp on a compacting save/return. */
        public int currentSeq() { return seq.get(); }

        public void setCallInFlight(boolean v) { this.callInFlight = v; }
        public void stampContact(long ms) { this.lastContactMs = ms; }
        /** Mark this carriage as culling — stops further enqueue + flusher POSTs (belt for the cull hook). */
        public void markCulled() { this.culled = true; }

        public boolean needsRebaseline() { return rebaseline; }
        /** Ask the flusher to re-baseline (full save) — the relay's delta log is near/at full. */
        public void markRebaseline() { this.rebaseline = true; }
        public void clearRebaseline() { this.rebaseline = false; }

        /** Record that this carriage now lives on the relay under {@code id} with lease {@code token}. */
        public void onRelayLease(int id, String token) {
            this.relayId = id;
            this.leaseToken = token;
        }

        /** Clear relay/lease state (e.g. after a return). */
        public void clearRelayLease() {
            this.relayId = null;
            this.leaseToken = null;
        }
    }

    /**
     * Register a freshly-placed shared carriage. {@code seqSeed} is the delta-sequence floor tied to the
     * relay carriage — 0 for a fresh local build, or {@code max(baseSeq, maxDeltaSeq)} for one leased from
     * the pool (so its first upload's seq clears the relay's drop-watermark).
     */
    public static Instance register(ServerLevel level, UUID subLevelId, UUID trainId, int pIdx,
                                    BlockPos shipyardOrigin, CarriageDims dims, String variantId,
                                    boolean leasedFromPool, Integer relayId, String leaseToken, int seqSeed) {
        Instance inst = new Instance(level, subLevelId, trainId, pIdx, shipyardOrigin, dims, variantId,
                leasedFromPool, relayId, leaseToken, seqSeed);
        BY_SUBLEVEL.computeIfAbsent(subLevelId, k -> new CopyOnWriteArrayList<>()).add(inst);
        LOGGER.debug("[DungeonTrain] Registered shared carriage variant={} pIdx={} subLevel={} leased={}.",
                variantId, pIdx, subLevelId, leasedFromPool);
        return inst;
    }

    /**
     * Resolve the shared carriage a block change at shipyard-space (x,y,z) belongs to, or null. Always
     * matches by footprint AABB — a grouped train packs several carriages (some not shared) into one
     * sub-level, so a bare "one entry" shortcut would misattribute a neighbour's edit.
     */
    public static Instance resolve(UUID subLevelId, int x, int y, int z) {
        List<Instance> list = BY_SUBLEVEL.get(subLevelId);
        if (list == null || list.isEmpty()) return null;
        for (Instance inst : list) {
            if (inst.contains(x, y, z)) return inst;
        }
        return null;
    }

    /** Whether any shared carriage is registered under {@code subLevelId} (cheap hot-path pre-check). */
    public static boolean hasSubLevel(UUID subLevelId) {
        List<Instance> list = BY_SUBLEVEL.get(subLevelId);
        return list != null && !list.isEmpty();
    }

    /** A snapshot of the shared carriages hosted in {@code subLevelId} (empty if none) — for the cull hook. */
    public static List<Instance> bySubLevel(UUID subLevelId) {
        CopyOnWriteArrayList<Instance> list = BY_SUBLEVEL.get(subLevelId);
        return list == null ? java.util.Collections.emptyList() : new ArrayList<>(list);
    }

    /** Snapshot of every registered instance (for the events tick). */
    public static List<Instance> all() {
        List<Instance> out = new ArrayList<>();
        for (CopyOnWriteArrayList<Instance> list : BY_SUBLEVEL.values()) out.addAll(list);
        return out;
    }

    /** Drop every instance under {@code subLevelId} (e.g. the group was culled/returned). */
    public static void removeSubLevel(UUID subLevelId) {
        BY_SUBLEVEL.remove(subLevelId);
    }

    /** Drop a single instance. */
    public static void remove(Instance inst) {
        CopyOnWriteArrayList<Instance> list = BY_SUBLEVEL.get(inst.subLevelId);
        if (list != null) {
            list.remove(inst);
            if (list.isEmpty()) BY_SUBLEVEL.remove(inst.subLevelId, list);
        }
    }

    public static void clear() {
        BY_SUBLEVEL.clear();
    }
}
