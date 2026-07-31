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

    /** How many leases to keep buffered ahead of demand — bounds speculative locking. */
    static final int TARGET_BUFFER = 10; // TEMP Gate-2 test crank (was 2) — REVERT before commit

    /** After a lease attempt finds nothing, wait this long before hitting the relay again. */
    private static final long EMPTY_BACKOFF_MS = 60_000L;

    private static final Queue<PoolLease> BUFFER = new ConcurrentLinkedQueue<>();
    private static volatile boolean fetchInFlight = false;
    /** Set when the pool had nothing to lease; suppresses refetches until then so we don't poll every tick. */
    private static volatile long backoffUntilMs = 0L;
    /**
     * Last host uuid seen by the prefetch tick, so a lease taken off the spawn thread (which has no
     * player handy) still records a real holder on the relay. An empty holder leaves the relay's admin
     * view unable to say WHO has a carriage locked.
     */
    private static volatile String hostUuid = "";

    private SharedCarriagePool() {}

    /** Remember the world's host player uuid for leases taken outside the prefetch tick. */
    public static void setHostUuid(String uuid) {
        if (uuid != null && !uuid.isEmpty()) hostUuid = uuid;
    }

    /**
     * Pop a buffered lease matching {@code dims} for the spawn path, or null when none is ready (the
     * caller then places a fresh local carriage instead). A dims-mismatched lease (shouldn't happen —
     * the relay filters by dims) is returned to the pool rather than placed.
     */
    public static PoolLease poll(CarriageDims dims) {
        PoolLease l = BUFFER.poll();
        if (l == null) return null;
        if (l.l() != dims.length() || l.h() != dims.height() || l.w() != dims.width()) {
            LOGGER.warn("[DungeonTrain] buffered lease id={} dims {}x{}x{} != requested {}x{}x{} — returning it unused.",
                    l.id(), l.l(), l.h(), l.w(), dims.length(), dims.height(), dims.width());
            returnLease(l);
            return null;
        }
        return l;
    }

    /**
     * Top the buffer up to {@link #TARGET_BUFFER} by leasing one carriage off-thread (one in-flight at a
     * time). {@code exclude} lists relay ids already resident/buffered so the relay never hands this world
     * the same carriage twice.
     */
    public static void refreshAsync(CarriageDims dims, String hostUuid, List<Integer> exclude) {
        if (fetchInFlight || BUFFER.size() >= TARGET_BUFFER) return;
        if (System.currentTimeMillis() < backoffUntilMs) return; // pool was empty recently → don't hammer it
        fetchInFlight = true;
        try {
            SharedCarriageClient.lease(hostUuid, dims.length(), dims.height(), dims.width(), exclude)
                    .whenComplete((opt, err) -> {
                        try {
                            if (err == null && opt != null && opt.isPresent()) {
                                BUFFER.offer(opt.get());
                                backoffUntilMs = 0L; // got one → resume eager refills
                                LOGGER.debug("[DungeonTrain] shared-carriage pool buffered lease id={} (buffer={}).",
                                        opt.get().id(), BUFFER.size());
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

    /**
     * TEMP Gate-2 test — REVERT before commit. Synchronous lease on the spawn thread so a shared slot is
     * filled from the relay whenever the relay has ANYTHING available, only falling back to the local
     * template when the pool is truly empty ("100% from relay unless relay is empty"). Blocks briefly on
     * HTTP — NOT for production; the ship path relies on the async prefetch buffer + a &lt;1.0 poolChance
     * (e.g. 0.95) so the 5% template variety is intentional, not a starvation artifact.
     */
    public static PoolLease leaseNowBlocking(CarriageDims dims) {
        // Respect the shared empty-pool backoff: when the relay was just seen empty, don't fire a blocking
        // lease per spawning carriage (a whole-rake spawn would otherwise storm the relay + hitch the
        // server thread) — fall straight to template until the async prefetch re-confirms availability.
        if (System.currentTimeMillis() < backoffUntilMs) return null;
        try {
            java.util.Optional<PoolLease> opt = SharedCarriageClient
                    .lease(hostUuid, dims.length(), dims.height(), dims.width(), java.util.Collections.emptyList())
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (opt != null && opt.isPresent()) {
                PoolLease l = opt.get();
                if (l.l() == dims.length() && l.h() == dims.height() && l.w() == dims.width()) {
                    backoffUntilMs = 0L; // got one → relay has content, keep probing eagerly
                    return l;
                }
                LOGGER.warn("[DungeonTrain] blocking lease id={} dims {}x{}x{} != requested {}x{}x{} — returning it unused.",
                        l.id(), l.l(), l.h(), l.w(), dims.length(), dims.height(), dims.width());
                returnLease(l); // dims mismatch (shouldn't happen — relay filters) → hand it back
            } else {
                backoffUntilMs = System.currentTimeMillis() + EMPTY_BACKOFF_MS; // relay empty → back off the storm
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] blocking lease failed: {}", t.toString());
        }
        return null; // relay genuinely had nothing available → NEW/template fallback
    }

    /** Return one unused lease to the relay (best-effort). Never placed → no blocks/baseSeq to send. */
    public static void returnLease(PoolLease l) {
        if (l != null) SharedCarriageClient.returnLease(l.id(), l.token(), null, null, 0);
    }

    /** Return every buffered-but-unplaced lease (world unload / server stop). */
    public static void returnAllBuffered() {
        PoolLease l;
        while ((l = BUFFER.poll()) != null) returnLease(l);
    }

    public static int buffered() {
        return BUFFER.size();
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
