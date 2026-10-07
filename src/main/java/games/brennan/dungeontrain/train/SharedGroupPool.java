package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A small buffer of relay GROUP leases — drifting Group carriages, one build spanning a whole group —
 * prefetched ahead of spawn so {@code TrainAssembler.spawnGroup} can stamp another world's copy without
 * blocking on HTTP. The group twin of {@link SharedRoomPool}, and its own class for the same reason: a
 * group lease is keyed by its box as well as its stage, and must never be spent on a one-carriage slot.
 *
 * <p>A drifting group is rare (one Group carriage in fifty by default), so the pool only ever asks the
 * relay once {@link #noteDemand} has said a group drifted here — a world that never rolls one never
 * holds a group lease. Every buffered lease is a HELD lease, invisible to every other world, so the
 * buffers are tiny: one per key, {@link #MAX_TOTAL_BUFFERED} in all.</p>
 */
public final class SharedGroupPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One buffered lease per (stage, box) — drifting groups are many groups apart. */
    static final int TARGET_BUFFER = 1;
    /** Ceiling across every key and author: a held group lease locks it against every other world. */
    static final int MAX_TOTAL_BUFFERED = 2;
    private static final long EMPTY_BACKOFF_MS = 5_000L;
    private static final long OWN_EMPTY_BACKOFF_MS = 120_000L;

    /** What a drifting group last asked for — the key the prefetch tick buffers. Null until one drifts. */
    private static volatile Demand demand = null;

    private static final Map<String, Queue<PoolLease>> BUFFER = new ConcurrentHashMap<>();
    private static final Map<String, Queue<PoolLease>> OWN_BUFFER = new ConcurrentHashMap<>();
    private static volatile boolean fetchInFlight = false;
    private static volatile boolean ownFetchInFlight = false;
    private static volatile long backoffUntilMs = 0L;
    private static final Map<String, Long> OWN_BACKOFF_UNTIL_MS = new ConcurrentHashMap<>();

    private SharedGroupPool() {}

    /** A drifting group's stage and group-long box: what the prefetch tick asks the relay for. */
    public record Demand(String stage, CarriageDims box) {
        public String key() {
            return keyOf(stage, box);
        }
    }

    static String keyOf(String stage, CarriageDims box) {
        return stage + '\0' + box.length() + ',' + box.height() + ',' + box.width();
    }

    private static String ownKey(String key, String ownerUuid) {
        return key + '\0' + ownerUuid;
    }

    /** Record that a group drifted in {@code stage}, so the prefetch buffers a copy for the next one. */
    public static void noteDemand(String stage, CarriageDims box) {
        if (stage == null || stage.isEmpty() || box == null) return;
        demand = new Demand(stage, box);
    }

    /** The group to prefetch for, or null before any group has drifted this session. */
    public static Demand demand() {
        return demand;
    }

    /** Pop a buffered group lease for this stage and box, or null when none is ready. */
    public static PoolLease poll(String stage, CarriageDims box) {
        if (stage == null || stage.isEmpty() || box == null) return null;
        return takeMatching(BUFFER.get(keyOf(stage, box)), box);
    }

    /** Pop a buffered group lease authored by one of {@code ownerUuids}, first refusal in order. */
    public static PoolLease pollOwn(String stage, CarriageDims box, Collection<String> ownerUuids) {
        if (stage == null || stage.isEmpty() || box == null || ownerUuids == null) return null;
        String key = keyOf(stage, box);
        for (String owner : ownerUuids) {
            if (owner == null || owner.isEmpty()) continue;
            PoolLease l = takeMatching(OWN_BUFFER.get(ownKey(key, owner)), box);
            if (l != null) return l;
        }
        return null;
    }

    private static PoolLease takeMatching(Queue<PoolLease> q, CarriageDims box) {
        PoolLease l = q == null ? null : q.poll();
        if (l == null) return null;
        if (l.l() != box.length() || l.h() != box.height() || l.w() != box.width()) {
            LOGGER.warn("[DungeonTrain] buffered group lease id={} dims {}x{}x{} != requested {}x{}x{} — returning it unused.",
                    l.id(), l.l(), l.h(), l.w(), box.length(), box.height(), box.width());
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

    /** Top the demanded group's buffer up by leasing one copy off-thread (one in flight at a time). */
    public static void refreshAsync(Demand d, String hostUuid, String hostName, List<Integer> exclude, String mode) {
        if (d == null) return;
        if (fetchInFlight || totalBuffered() >= MAX_TOTAL_BUFFERED) return;
        Queue<PoolLease> q = BUFFER.computeIfAbsent(d.key(), k -> new ConcurrentLinkedQueue<>());
        if (q.size() >= TARGET_BUFFER) return;
        if (System.currentTimeMillis() < backoffUntilMs) return;
        fetchInFlight = true;
        try {
            SharedCarriageClient.lease(hostUuid, hostName, d.box().length(), d.box().height(), d.box().width(),
                            exclude, d.stage(), null, mode, PoolLease.KIND_CARRIAGE_GROUP, null)
                    .whenComplete((opt, err) -> {
                        try {
                            if (err == null && opt != null && opt.isPresent()) {
                                q.offer(opt.get());
                                backoffUntilMs = 0L;
                                LOGGER.debug("[DungeonTrain] shared-group pool buffered lease id={} stage={} (total={}).",
                                        opt.get().id(), d.stage(), totalBuffered());
                            } else {
                                backoffUntilMs = System.currentTimeMillis() + EMPTY_BACKOFF_MS;
                            }
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] shared-group pool refresh failed to start: {}", t.toString());
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
                            d.box().length(), d.box().height(), d.box().width(),
                            exclude, d.stage(), ownerUuid, mode, PoolLease.KIND_CARRIAGE_GROUP, null)
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
            LOGGER.debug("[DungeonTrain] shared-group own refresh failed to start: {}", t.toString());
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
    static void offerForTest(String stage, CarriageDims box, PoolLease lease) {
        BUFFER.computeIfAbsent(keyOf(stage, box), k -> new ConcurrentLinkedQueue<>()).offer(lease);
    }

    /** Test seam: place a lease in the own buffer without a relay round-trip. */
    static void offerOwnForTest(String stage, CarriageDims box, String ownerUuid, PoolLease lease) {
        OWN_BUFFER.computeIfAbsent(ownKey(keyOf(stage, box), ownerUuid),
                k -> new ConcurrentLinkedQueue<>()).offer(lease);
    }
}
