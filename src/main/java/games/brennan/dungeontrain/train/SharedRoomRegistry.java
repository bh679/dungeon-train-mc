package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory registry of the drifting dimensional carriages currently standing in the world — the
 * rooms of portal pairs that take part in the shared-carriage relay — keyed by pair.
 *
 * <p>The room twin of {@link SharedCarriageRegistry}. A carriage lives in a Sable sub-level and is
 * found by plot coordinates; a room is a box of ordinary world blocks under the floor, so it is found
 * by world position and its edits are queued as <b>room-local offsets</b>: the pair is re-stamped
 * further down the line as the train drifts, and an offset survives that where a world position would
 * point at the old site. {@link #relocate} is the whole of what a relocation has to tell the
 * registry.</p>
 *
 * <p>Populated when a pair's room is stamped (see {@code SharedRoomEvents.onStructureStamped}); read
 * by the block-event hook ({@code SharedRoomEditEvents}) to queue a real edit, and by
 * {@code SharedRoomEvents} to upload and lease-manage. Transient — server-session lifetime only;
 * cleared on server stop.</p>
 */
public final class SharedRoomRegistry {

    /** pairKey → the drifting room that pair is standing. */
    private static final Map<Integer, Instance> BY_PAIR = new ConcurrentHashMap<>();

    private SharedRoomRegistry() {}

    /**
     * One drifting room. Identity fields are final; the room's world origin moves with relocation,
     * and relay/lease state is updated from async callbacks, so those are {@code volatile}.
     */
    public static final class Instance implements SharedBuild {
        public final ServerLevel level;
        public final int pairKey;
        /** The room's template name — what the relay matches a room lease on ({@code subKind}). */
        public final String roomName;
        /** The room box, as stamped: {@code x} = length, {@code y} = height, {@code z} = width. */
        public final Vec3i size;
        /** True once this room was leased from the relay pool; false for a fresh local stamp. */
        public final boolean leasedFromPool;
        /** True when the leased build was authored by a player currently in this world. */
        public final boolean authoredHere;
        /** Dashless uuid of the relay-recorded author, or "" when unknown. */
        public final String authorUuid;
        /** The worldgen stage the pair sits in; null when none covers it (then never shared). */
        private final String stageId;
        public final Credits credits;
        private volatile Deaths deaths;
        /** Minimum corner of the room box in world space — moves on relocation. */
        private volatile BlockPos roomOrigin;

        private volatile Integer relayId;
        private volatile String leaseToken;
        /** Pending changed cells as room-local offsets, deduped (last-write-wins; the flush re-reads). */
        private final Set<BlockPos> outbox = ConcurrentHashMap.newKeySet();
        private final AtomicInteger seq;
        private volatile boolean culled;
        private volatile boolean rebaseline;
        private volatile boolean callInFlight;
        private volatile long lastContactMs;
        private volatile boolean blockEditedThisSession;
        private volatile boolean attributed;
        private volatile long entitySig;
        private volatile boolean entitySigSeeded;
        private volatile long lastEntityScanMs = System.currentTimeMillis();

        Instance(ServerLevel level, int pairKey, String roomName, BlockPos roomOrigin, Vec3i size,
                 boolean leasedFromPool, boolean authoredHere, String authorUuid, Integer relayId,
                 String leaseToken, int seqSeed, String stageId, Credits credits, Deaths deaths) {
            this.level = level;
            this.pairKey = pairKey;
            this.roomName = roomName;
            this.roomOrigin = roomOrigin.immutable();
            this.size = size;
            this.leasedFromPool = leasedFromPool;
            this.authoredHere = authoredHere;
            this.authorUuid = authorUuid == null ? "" : authorUuid;
            this.relayId = relayId;
            this.leaseToken = leaseToken;
            this.seq = new AtomicInteger(Math.max(0, seqSeed));
            this.stageId = stageId;
            this.credits = credits == null ? Credits.EMPTY : credits;
            this.deaths = deaths == null ? Deaths.EMPTY : deaths;
        }

        /** Minimum corner of the room box in world space, as currently stamped. */
        public BlockPos roomOrigin() { return roomOrigin; }

        public Deaths deaths() { return deaths; }

        /** Fold a death that just happened here into the log — newest first, one traveller once. */
        public void addLocalDeath(String name) {
            Deaths cur = deaths;
            List<String> names = cur.names();
            if (name != null && !name.isEmpty()) {
                List<String> next = new ArrayList<>(names.size() + 1);
                next.add(name);
                for (String n : names) if (!n.equals(name)) next.add(n);
                names = List.copyOf(next);
            }
            deaths = new Deaths(names, cur.total() + 1);
        }

        /** Whether world position {@code (x,y,z)} falls inside this room's box. */
        public boolean contains(int x, int y, int z) {
            BlockPos o = roomOrigin;
            return x >= o.getX() && x < o.getX() + size.getX()
                && y >= o.getY() && y < o.getY() + size.getY()
                && z >= o.getZ() && z < o.getZ() + size.getZ();
        }

        /** World position → room-local offset (the caller has checked {@link #contains}). */
        public BlockPos offsetOf(BlockPos world) {
            return world.subtract(roomOrigin);
        }

        /** Whether {@code playerId} is the player the relay credits with building this room. */
        public boolean isAuthoredBy(UUID playerId) {
            return playerId != null && !authorUuid.isEmpty()
                && authorUuid.equals(playerId.toString().replace("-", ""));
        }

        public void markBlockEdited() { this.blockEditedThisSession = true; }
        public boolean isBlockEditedThisSession() { return blockEditedThisSession; }

        // ---- SharedBuild ----

        @Override public Integer relayId() { return relayId; }
        @Override public String leaseToken() { return leaseToken; }
        @Override public boolean isOnRelay() { return relayId != null && leaseToken != null; }
        @Override public void onRelayLease(int id, String token) { this.relayId = id; this.leaseToken = token; }
        @Override public void clearRelayLease() { this.relayId = null; this.leaseToken = null; }
        @Override public int nextSeq() { return seq.incrementAndGet(); }
        @Override public int currentSeq() { return seq.get(); }
        @Override public boolean hasPending() { return !outbox.isEmpty(); }

        /** Record a changed room-local offset for the next delta flush (no-op once culled). */
        public void enqueue(BlockPos offset) { if (!culled) outbox.add(offset.immutable()); }

        @Override
        public Set<BlockPos> drainPending() {
            Set<BlockPos> snap = new HashSet<>(outbox);
            outbox.removeAll(snap);
            return snap;
        }

        @Override public void reenqueue(Collection<BlockPos> offsets) { outbox.addAll(offsets); }
        @Override public boolean isCulled() { return culled; }
        @Override public void markCulled() { this.culled = true; }
        @Override public boolean needsRebaseline() { return rebaseline; }
        @Override public void markRebaseline() { this.rebaseline = true; }
        @Override public void clearRebaseline() { this.rebaseline = false; }
        @Override public boolean isCallInFlight() { return callInFlight; }
        @Override public void setCallInFlight(boolean v) { this.callInFlight = v; }
        @Override public long lastContactMs() { return lastContactMs; }
        @Override public void stampContact(long ms) { this.lastContactMs = ms; }
        @Override public long entitySig() { return entitySig; }
        @Override public boolean hasEntitySigBaseline() { return entitySigSeeded; }
        @Override public void setEntitySig(long sig) { this.entitySig = sig; this.entitySigSeeded = true; }

        @Override
        public boolean dueForEntityScan(long nowMs, long intervalMs) {
            if (nowMs - lastEntityScanMs < intervalMs) return false;
            lastEntityScanMs = nowMs;
            return true;
        }

        @Override public boolean isAttributed() { return attributed; }
        @Override public void markAttributed() { this.attributed = true; }
        @Override public String stageId() { return stageId; }
        @Override public String describe() { return "pair=" + pairKey + " room=" + roomName; }
    }

    /**
     * Register a freshly-stamped drifting room. {@code seqSeed} is 0 for a fresh local stamp, or the
     * lease's floor for a pooled one; {@code credits}/{@code deaths} are off the lease or EMPTY.
     * Replaces any earlier record for the pair — a pair stamps one room at a time.
     */
    public static Instance register(ServerLevel level, int pairKey, String roomName, BlockPos roomOrigin,
                                    Vec3i size, boolean leasedFromPool, boolean authoredHere,
                                    String authorUuid, Integer relayId, String leaseToken, int seqSeed,
                                    String stageId, Credits credits, Deaths deaths) {
        Instance inst = new Instance(level, pairKey, roomName, roomOrigin, size, leasedFromPool,
                authoredHere, authorUuid, relayId, leaseToken, seqSeed, stageId, credits, deaths);
        BY_PAIR.put(pairKey, inst);
        return inst;
    }

    /** The drifting room pair {@code pairKey} is standing, or null. */
    public static Instance byPair(int pairKey) {
        return BY_PAIR.get(pairKey);
    }

    /** The drifting room whose box holds world position {@code pos} in {@code level}, or null. */
    public static Instance byWorldPos(ServerLevel level, BlockPos pos) {
        if (BY_PAIR.isEmpty()) return null;
        for (Instance inst : BY_PAIR.values()) {
            if (inst.level == level && inst.contains(pos.getX(), pos.getY(), pos.getZ())) return inst;
        }
        return null;
    }

    /** The pair's room now stands at {@code newRoomOrigin}; queued offsets carry across unchanged. */
    public static void relocate(int pairKey, BlockPos newRoomOrigin) {
        Instance inst = BY_PAIR.get(pairKey);
        if (inst != null) inst.roomOrigin = newRoomOrigin.immutable();
    }

    /** Snapshot of every registered room (for the events tick). */
    public static List<Instance> all() {
        return new ArrayList<>(BY_PAIR.values());
    }

    /** Drop the pair's record, returning it (or null). */
    public static Instance remove(int pairKey) {
        return BY_PAIR.remove(pairKey);
    }

    public static boolean isEmpty() {
        return BY_PAIR.isEmpty();
    }

    public static void clear() {
        BY_PAIR.clear();
    }
}
