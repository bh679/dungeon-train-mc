package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import net.minecraft.core.Vec3i;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A small buffer of relay ROOM leases prefetched ahead of a pair being planned, so
 * {@code PortalCarriageBuilder.planStructure} — synchronous, on the portal tick — can hand a pair
 * somebody else's copy of its room without blocking on HTTP. The room twin of
 * {@link SharedCarriagePool}, and deliberately its own class: a room lease is keyed by more than a
 * stage.
 *
 * <p>A room is only ever served back into the same room — same template name, same box — so each
 * buffer is keyed by {@code stage + roomName + size}. The pool cannot guess which room the next pair
 * will roll; {@link #noteDemand} is the plan telling it, so the first pair to roll a room gets the
 * template and the next pair to roll that same room may get a drifted copy. That is the same shape
 * {@link SharedCarriagePool#noteStageDemand} has: demand steers the prefetch.</p>
 *
 * <p>Every buffered lease is a HELD lease on the relay, invisible to every other world, so the
 * buffers are tiny: one per key, {@link #MAX_TOTAL_BUFFERED} in all, with the same empty back-offs
 * the carriage pool uses.</p>
 */
public final class SharedRoomPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One buffered lease per (stage, room, size) — pairs rolling the same room are minutes apart. */
    static final int TARGET_BUFFER = 1;
    /** Ceiling across every key and author: a held room lease locks it against every other world. */
    static final int MAX_TOTAL_BUFFERED = 3;
    private static final long EMPTY_BACKOFF_MS = 5_000L;
    private static final long OWN_EMPTY_BACKOFF_MS = 120_000L;

    /** What a pair last asked for — the key the prefetch tick buffers. Null before any pair has drifted. */
    private static volatile Demand demand = null;

    private static final Map<String, Queue<PoolLease>> BUFFER = new ConcurrentHashMap<>();
    private static final Map<String, Queue<PoolLease>> OWN_BUFFER = new ConcurrentHashMap<>();
    private static volatile boolean fetchInFlight = false;
    private static volatile boolean ownFetchInFlight = false;
    private static volatile long backoffUntilMs = 0L;
    private static final Map<String, Long> OWN_BACKOFF_UNTIL_MS = new ConcurrentHashMap<>();

    private SharedRoomPool() {}

    /** A room a pair has planned: what the prefetch tick asks the relay for. */
    public record Demand(String stage, String roomName, Vec3i size) {
        public String key() {
            return keyOf(stage, roomName, size);
        }
    }

    static String keyOf(String stage, String roomName, Vec3i size) {
        return stage + '\0' + roomName + '\0' + size.getX() + ',' + size.getY() + ',' + size.getZ();
    }

    private static String ownKey(String key, String ownerUuid) {
        return key + '\0' + ownerUuid;
    }

    /** Record which room the planner most recently rolled, so the prefetch buffers a copy of it. */
    public static void noteDemand(String stage, String roomName, Vec3i size) {
        if (stage == null || stage.isEmpty() || roomName == null || roomName.isEmpty() || size == null) return;
        demand = new Demand(stage, roomName, size);
    }

    /** The room to prefetch for, or null before any pair has planned a drifting room. */
    public static Demand demand() {
        return demand;
    }

    /** Pop a buffered lease of this exact room for the planner, or null when none is ready. */
    public static PoolLease poll(String stage, String roomName, Vec3i size) {
        if (stage == null || stage.isEmpty()) return null;
        return takeMatching(BUFFER.get(keyOf(stage, roomName, size)), size);
    }

    /** Pop a buffered lease of this room authored by one of {@code ownerUuids}, first refusal in order. */
    public static PoolLease pollOwn(String stage, String roomName, Vec3i size, Collection<String> ownerUuids) {
        if (stage == null || stage.isEmpty() || ownerUuids == null) return null;
        String key = keyOf(stage, roomName, size);
        for (String owner : ownerUuids) {
            if (owner == null || owner.isEmpty()) continue;
            PoolLease l = takeMatching(OWN_BUFFER.get(ownKey(key, owner)), size);
            if (l != null) return l;
        }
        return null;
    }

    private static PoolLease takeMatching(Queue<PoolLease> q, Vec3i size) {
        PoolLease l = q == null ? null : q.poll();
        if (l == null) return null;
        if (l.l() != size.getX() || l.h() != size.getY() || l.w() != size.getZ()) {
            LOGGER.warn("[DungeonTrain] buffered room lease id={} dims {}x{}x{} != requested {}x{}x{} — returning it unused.",
                    l.id(), l.l(), l.h(), l.w(), size.getX(), size.getY(), size.getZ());
            returnLease(l);
            return null;
        }
        return l;
    }

    private static int totalBuffered() {
        int n = 0;
        for (Queue<PoolLease> q : BUFFER.values()) n += q.size();
        for (Queue<PoolLease> q : OWN_BUFFER.values()) n += q.size();
        return n;
    }

    /** Top the demanded room's buffer up by leasing one copy off-thread (one in flight at a time). */
    public static void refreshAsync(Demand d, String hostUuid, String hostName, List<Integer> exclude, String mode) {
        if (d == null) return;
        if (fetchInFlight || totalBuffered() >= MAX_TOTAL_BUFFERED) return;
        Queue<PoolLease> q = BUFFER.computeIfAbsent(d.key(), k -> new ConcurrentLinkedQueue<>());
        if (q.size() >= TARGET_BUFFER) return;
        if (System.currentTimeMillis() < backoffUntilMs) return;
        fetchInFlight = true;
        try {
            SharedCarriageClient.lease(hostUuid, hostName, d.size().getX(), d.size().getY(), d.size().getZ(),
                            exclude, d.stage(), null, mode, PoolLease.KIND_PORTAL_ROOM, d.roomName())
                    .whenComplete((opt, err) -> {
                        try {
                            if (err == null && opt != null && opt.isPresent()) {
                                q.offer(opt.get());
                                backoffUntilMs = 0L;
                                LOGGER.debug("[DungeonTrain] shared-room pool buffered lease id={} room={} stage={} (total={}).",
                                        opt.get().id(), d.roomName(), d.stage(), totalBuffered());
                            } else {
                                backoffUntilMs = System.currentTimeMillis() + EMPTY_BACKOFF_MS;
                            }
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] shared-room pool refresh failed to start: {}", t.toString());
        }
    }

    /** As {@link #refreshAsync}, restricted to one author, with a per-(key, author) back-off. */
    public static void refreshOwnAsync(Demand d, String ownerUuid, List<Integer> exclude, String mode) {
        if (d == null || ownerUuid == null || ownerUuid.isEmpty()) return;
        if (ownFetchInFlight || totalBuffered() >= MAX_TOTAL_BUFFERED) return;
        String key = ownKey(d.key(), ownerUuid);
        Queue<PoolLease> q = OWN_BUFFER.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        if (q.size() >= TARGET_BUFFER) return;
        Long until = OWN_BACKOFF_UNTIL_MS.get(key);
        if (until != null && System.currentTimeMillis() < until) return;
        ownFetchInFlight = true;
        try {
            SharedCarriageClient.lease(SharedCarriagePool.hostUuid(), SharedCarriagePool.hostName(),
                            d.size().getX(), d.size().getY(), d.size().getZ(),
                            exclude, d.stage(), ownerUuid, mode, PoolLease.KIND_PORTAL_ROOM, d.roomName())
                    .whenComplete((opt, err) -> {
                        try {
                            if (err == null && opt != null && opt.isPresent()) {
                                q.offer(opt.get());
                                OWN_BACKOFF_UNTIL_MS.remove(key);
                            } else {
                                OWN_BACKOFF_UNTIL_MS.put(key, System.currentTimeMillis() + OWN_EMPTY_BACKOFF_MS);
                            }
                        } finally {
                            ownFetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            ownFetchInFlight = false;
            LOGGER.debug("[DungeonTrain] shared-room own refresh failed to start: {}", t.toString());
        }
    }

    /** Return one unused lease to the relay (best-effort). */
    public static void returnLease(PoolLease l) {
        if (l != null) SharedCarriageClient.returnLease(l.id(), l.token(), null, null, 0);
    }

    /** Return every buffered-but-unplaced lease (world unload / server stop / pool flip). */
    public static void returnAllBuffered() {
        for (Map<String, Queue<PoolLease>> buf : List.of(BUFFER, OWN_BUFFER)) {
            for (Queue<PoolLease> q : buf.values()) {
                PoolLease l;
                while ((l = q.poll()) != null) returnLease(l);
            }
            buf.clear();
        }
        OWN_BACKOFF_UNTIL_MS.clear();
    }

    public static int buffered() {
        return totalBuffered();
    }

    public static boolean isBackedOff() {
        return System.currentTimeMillis() < backoffUntilMs;
    }

    /** Test/reset seam. Does NOT return leases. */
    public static void clear() {
        BUFFER.clear();
        OWN_BUFFER.clear();
        OWN_BACKOFF_UNTIL_MS.clear();
        demand = null;
        fetchInFlight = false;
        ownFetchInFlight = false;
        backoffUntilMs = 0L;
    }

    /** Test seam: place a lease in the shared buffer without a relay round-trip. */
    static void offerForTest(String stage, String roomName, Vec3i size, PoolLease lease) {
        BUFFER.computeIfAbsent(keyOf(stage, roomName, size), k -> new ConcurrentLinkedQueue<>()).offer(lease);
    }

    /** Test seam: place a lease in the own buffer without a relay round-trip. */
    static void offerOwnForTest(String stage, String roomName, Vec3i size, String ownerUuid, PoolLease lease) {
        OWN_BUFFER.computeIfAbsent(ownKey(keyOf(stage, roomName, size), ownerUuid),
                k -> new ConcurrentLinkedQueue<>()).offer(lease);
    }
}
