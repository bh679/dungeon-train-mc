package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import org.slf4j.Logger;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A small buffer of relay carriage LEASES prefetched ahead of spawn, so the synchronous carriage
 * spawn path ({@code TrainAssembler.spawnGroup}) can hand out a pooled build without blocking on HTTP.
 * Modelled on {@link games.brennan.dungeontrain.narrative.SharedBookPool}'s async-refresh shape.
 *
 * <p>Each buffered {@link PoolLease} is a HELD lease on the relay (locked to this world). The buffer is
 * kept small ({@link #TARGET_BUFFER}) so we never speculatively lock more than a couple of carriages;
 * {@link #refreshAsync} tops it up off-thread, {@link #poll} pops one on the server thread, and
 * {@link #returnAllBuffered} hands back anything unused when the world unloads. Leases we never place
 * (e.g. a lingering buffer) are also freed by the relay's ~1h lease TTL as a backstop.</p>
 */
public final class SharedCarriagePool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How many leases to keep buffered PER STAGE ahead of demand — bounds speculative locking.
     *
     * <p>Per-stage, because a buffered {@code desert} lease cannot fill a {@code stone} slot: it would sit
     * there holding that carriage locked against every other world while being unusable here.</p>
     */
    static final int TARGET_BUFFER = 2;

    /**
     * Ceiling on buffered leases across ALL stages. With one queue per stage the per-stage target would
     * otherwise multiply by the stage count (nine today), locking that many carriages the world may never
     * place. A lease held here is invisible to every other world, so the cap is a fairness bound.
     */
    static final int MAX_TOTAL_BUFFERED = 12;

    /**
     * After a lease attempt finds nothing, wait this long before hitting the relay again.
     *
     * <p>Kept short deliberately. Leases are exclusive, so a world drains the available pool within
     * seconds of spawning its first rake and then sees one {@code RELAY_EMPTY}; a long back-off after
     * that single miss silences every subsequent shared slot for its whole duration, which is how a
     * train ends up almost entirely fresh templates even though carriages are returning to the pool as
     * groups get culled. Re-probing costs one small POST against a relay that answers from one indexed
     * SELECT.</p>
     */
    private static final long EMPTY_BACKOFF_MS = 5_000L;

    /** Buffered leases keyed by the stage they were leased for; a lease is only usable in its own stage. */
    private static final java.util.Map<String, Queue<PoolLease>> BUFFER = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean fetchInFlight = false;
    /** Set when the pool had nothing to lease; suppresses refetches until then so we don't poll every tick. */
    private static volatile long backoffUntilMs = 0L;
    /**
     * Last host uuid seen by the prefetch tick, so a lease taken off the spawn thread (which has no
     * player handy) still records a real holder on the relay. An empty holder leaves the relay's admin
     * view unable to say WHO has a carriage locked.
     */
    private static volatile String hostUuid = "";
    /**
     * The stage the spawn path most recently asked for. The prefetch tick has no way to know which stage
     * the train is currently extending into, and buffering the wrong stage locks carriages this world
     * cannot use — so the spawn path tells it.
     */
    private static volatile String demandStage = "";

    private SharedCarriagePool() {}

    /** Remember the world's host player uuid for leases taken outside the prefetch tick. */
    public static void setHostUuid(String uuid) {
        if (uuid != null && !uuid.isEmpty()) hostUuid = uuid;
    }

    /** Record which stage the spawn path is currently drawing from, so the prefetch buffers that one. */
    public static void noteStageDemand(String stage) {
        if (stage != null && !stage.isEmpty()) demandStage = stage;
    }

    /** The stage to prefetch for, or {@code ""} before any shared slot has spawned. */
    public static String demandStage() {
        return demandStage;
    }

    /**
     * The host uuid last seen, or {@code ""} before any player has joined. Leases claimed during the
     * world-load spawn predate that, so they go out unattributed and are backfilled by the heartbeat.
     */
    public static String hostUuid() {
        return hostUuid;
    }

    /**
     * Pop a buffered lease matching {@code dims} for the spawn path, or null when none is ready (the
     * caller then places a fresh local carriage instead). A dims-mismatched lease (shouldn't happen —
     * the relay filters by dims) is returned to the pool rather than placed.
     */
    public static PoolLease poll(CarriageDims dims, String stage) {
        if (stage == null || stage.isEmpty()) return null; // stageless slot → never draws from the pool
        Queue<PoolLease> q = BUFFER.get(stage);
        PoolLease l = q == null ? null : q.poll();
        if (l == null) return null;
        if (l.l() != dims.length() || l.h() != dims.height() || l.w() != dims.width()) {
            LOGGER.warn("[DungeonTrain] buffered lease id={} dims {}x{}x{} != requested {}x{}x{} — returning it unused.",
                    l.id(), l.l(), l.h(), l.w(), dims.length(), dims.height(), dims.width());
            returnLease(l);
            return null;
        }
        return l;
    }

    /** Total buffered leases across every stage — what {@link #MAX_TOTAL_BUFFERED} bounds. */
    private static int totalBuffered() {
        int n = 0;
        for (Queue<PoolLease> q : BUFFER.values()) n += q.size();
        return n;
    }

    /**
     * Top {@code stage}'s buffer up to {@link #TARGET_BUFFER} by leasing one carriage off-thread (one
     * in-flight at a time, and never past {@link #MAX_TOTAL_BUFFERED} across all stages).
     * {@code exclude} lists relay ids already resident/buffered so the relay never hands this world the
     * same carriage twice.
     */
    public static void refreshAsync(CarriageDims dims, String stage, String hostUuid, List<Integer> exclude) {
        if (stage == null || stage.isEmpty()) return; // nothing to prefetch for a stageless slot
        if (fetchInFlight || totalBuffered() >= MAX_TOTAL_BUFFERED) return;
        Queue<PoolLease> q = BUFFER.computeIfAbsent(stage, k -> new ConcurrentLinkedQueue<>());
        if (q.size() >= TARGET_BUFFER) return;
        if (System.currentTimeMillis() < backoffUntilMs) return; // pool was empty recently → don't hammer it
        fetchInFlight = true;
        try {
            SharedCarriageClient.lease(hostUuid, dims.length(), dims.height(), dims.width(), exclude, stage)
                    .whenComplete((opt, err) -> {
                        try {
                            if (err == null && opt != null && opt.isPresent()) {
                                q.offer(opt.get());
                                backoffUntilMs = 0L; // got one → resume eager refills
                                LOGGER.debug("[DungeonTrain] shared-carriage pool buffered lease id={} stage={} (stage buffer={}, total={}).",
                                        opt.get().id(), stage, q.size(), totalBuffered());
                            } else {
                                // Nothing available (or a transient failure) → back off before retrying.
                                backoffUntilMs = System.currentTimeMillis() + EMPTY_BACKOFF_MS;
                            }
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] shared-carriage pool refresh failed to start: {}", t.toString());
        }
    }

    /** Return one unused lease to the relay (best-effort). Never placed → no blocks/baseSeq to send. */
    public static void returnLease(PoolLease l) {
        if (l != null) SharedCarriageClient.returnLease(l.id(), l.token(), null, null, 0);
    }

    /** Return every buffered-but-unplaced lease, across every stage (world unload / server stop). */
    public static void returnAllBuffered() {
        for (Queue<PoolLease> q : BUFFER.values()) {
            PoolLease l;
            while ((l = q.poll()) != null) returnLease(l);
        }
        BUFFER.clear();
    }

    /** Buffered leases across all stages. */
    public static int buffered() {
        return totalBuffered();
    }

    /**
     * Whether the empty-pool back-off is currently suppressing lease attempts. Lets the spawn path
     * report "we didn't even ask the relay" distinctly from "the relay had nothing".
     */
    public static boolean isBackedOff() {
        return System.currentTimeMillis() < backoffUntilMs;
    }

    /** Test/reset seam. Does NOT return leases (tests don't hold real ones). */
    public static void clear() {
        BUFFER.clear();
        fetchInFlight = false;
        backoffUntilMs = 0L;
    }
}
