package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
        /** Set when a real (non-loot) change is observed; cleared once a save/submit lands. */
        private volatile boolean dirty;
        /** Guards against overlapping submit/save calls for this carriage. */
        private volatile boolean callInFlight;
        /** Last successful save/heartbeat wall-clock ms (for throttling). */
        private volatile long lastContactMs;

        Instance(ServerLevel level, UUID subLevelId, UUID trainId, int pIdx, BlockPos shipyardOrigin,
                 CarriageDims dims, String variantId, boolean leasedFromPool,
                 Integer relayId, String leaseToken) {
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
        public boolean isDirty() { return dirty; }
        public boolean isOnRelay() { return relayId != null && leaseToken != null; }
        public boolean isCallInFlight() { return callInFlight; }
        public long lastContactMs() { return lastContactMs; }

        public void markDirty() { this.dirty = true; }
        public void clearDirty() { this.dirty = false; }
        public void setCallInFlight(boolean v) { this.callInFlight = v; }
        public void stampContact(long ms) { this.lastContactMs = ms; }

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

    /** Register a freshly-placed shared carriage. */
    public static Instance register(ServerLevel level, UUID subLevelId, UUID trainId, int pIdx,
                                    BlockPos shipyardOrigin, CarriageDims dims, String variantId,
                                    boolean leasedFromPool, Integer relayId, String leaseToken) {
        Instance inst = new Instance(level, subLevelId, trainId, pIdx, shipyardOrigin, dims, variantId,
                leasedFromPool, relayId, leaseToken);
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
