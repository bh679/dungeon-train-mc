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
    static final int TARGET_BUFFER = 2;

    private static final Queue<PoolLease> BUFFER = new ConcurrentLinkedQueue<>();
    private static volatile boolean fetchInFlight = false;

    private SharedCarriagePool() {}

    /**
     * Pop a buffered lease matching {@code dims} for the spawn path, or null when none is ready (the
     * caller then places a fresh local carriage instead). A dims-mismatched lease (shouldn't happen —
     * the relay filters by dims) is returned to the pool rather than placed.
     */
    public static PoolLease poll(CarriageDims dims) {
        PoolLease l = BUFFER.poll();
        if (l == null) return null;
        if (l.l() != dims.length() || l.h() != dims.height() || l.w() != dims.width()) {
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
        fetchInFlight = true;
        try {
            SharedCarriageClient.lease(hostUuid, dims.length(), dims.height(), dims.width(), exclude)
                    .whenComplete((opt, err) -> {
                        try {
                            if (err == null && opt != null && opt.isPresent()) {
                                BUFFER.offer(opt.get());
                                LOGGER.debug("[DungeonTrain] shared-carriage pool buffered lease id={} (buffer={}).",
                                        opt.get().id(), BUFFER.size());
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

    /** Return one unused lease to the relay (best-effort). */
    public static void returnLease(PoolLease l) {
        if (l != null) SharedCarriageClient.returnLease(l.id(), l.token(), null, null);
    }

    /** Return every buffered-but-unplaced lease (world unload / server stop). */
    public static void returnAllBuffered() {
        PoolLease l;
        while ((l = BUFFER.poll()) != null) returnLease(l);
    }

    public static int buffered() {
        return BUFFER.size();
    }

    /** Test/reset seam. Does NOT return leases (tests don't hold real ones). */
    public static void clear() {
        BUFFER.clear();
        fetchInFlight = false;
    }
}
