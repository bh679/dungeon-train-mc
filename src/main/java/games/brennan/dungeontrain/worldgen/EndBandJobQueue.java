package games.brennan.dungeontrain.worldgen;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The End-band sampler's pending jobs, handed out <b>nearest player first</b>. Free of Minecraft types
 * so the ordering is unit-testable.
 *
 * <p>The sampler used to be a first-in-first-out pool. The prefetch strip keeps queuing chunks beyond the
 * view distance, so under load the chunks right beside the player waited behind them — and showed as
 * squares of void in the islands until their copy finally landed (or never, if the player moved on). Here
 * each take re-scores every waiting job against the latest player snapshot, so a chunk you can see always
 * goes first, and a job no player is near any more is dropped (its chunk asks again when it reloads).</p>
 *
 * @param <J> the job payload
 */
public final class EndBandJobQueue<J> {

    /** Player chunk positions, snapshotted on the server thread. Empty ⇒ no ordering and no dropping. */
    public record Players(int[] chunkXs, int[] chunkZs) {
        public static final Players NONE = new Players(new int[0], new int[0]);

        public boolean isEmpty() {
            return chunkXs.length == 0;
        }

        /** Chebyshev chunk distance from {@code (cx, cz)} to the nearest player; {@code 0} when there are none. */
        public int distance(int cx, int cz) {
            if (isEmpty()) return 0;
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < chunkXs.length; i++) {
                int d = Math.max(Math.abs(cx - chunkXs[i]), Math.abs(cz - chunkZs[i]));
                if (d < best) best = d;
            }
            return best;
        }
    }

    private record Entry<J>(int cx, int cz, J job) {}

    private final Map<Long, Entry<J>> jobs = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Players players = Players.NONE;

    /** Replace the player snapshot the next takes are ordered by. Server thread. */
    public void setPlayers(Players snapshot) {
        players = snapshot == null ? Players.NONE : snapshot;
    }

    public Players players() {
        return players;
    }

    /** Queue {@code job} for chunk {@code (cx, cz)} under {@code key}; a job already queued under it is replaced. */
    public void add(long key, int cx, int cz, J job) {
        lock.lock();
        try {
            jobs.put(key, new Entry<>(cx, cz, job));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove and return the waiting job nearest a player, or {@code null} if none is waiting. Never blocks.
     * With players present, jobs further than {@code keepRadius} chunks from all of them are removed and
     * handed to {@code onDrop} (outside the lock) first. With no players (a headless forceload) the oldest
     * job goes first and nothing is dropped.
     */
    public J poll(int keepRadius, Consumer<J> onDrop) {
        List<J> dropped = new ArrayList<>();
        J picked = pollNearest(keepRadius, dropped);
        dropped.forEach(onDrop);
        return picked;
    }

    /** {@link #poll} with the dropped jobs collected into {@code dropped} rather than handed on. */
    J pollNearest(int keepRadius, List<J> dropped) {
        lock.lock();
        try {
            return pickLocked(players, keepRadius, dropped);
        } finally {
            lock.unlock();
        }
    }

    private J pickLocked(Players snapshot, int keepRadius, List<J> dropped) {
        Long bestKey = null;
        int bestDist = Integer.MAX_VALUE;
        Iterator<Map.Entry<Long, Entry<J>>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Entry<J>> e = it.next();
            int d = snapshot.distance(e.getValue().cx(), e.getValue().cz());
            if (!snapshot.isEmpty() && d > keepRadius) {
                dropped.add(e.getValue().job());
                it.remove();
                continue;
            }
            if (d < bestDist) {                              // strict: ties keep insertion (FIFO) order
                bestDist = d;
                bestKey = e.getKey();
            }
        }
        return bestKey == null ? null : jobs.remove(bestKey).job();
    }

    /** Drop every waiting job, handing each to {@code onDrop}. */
    public void clear(Consumer<J> onDrop) {
        List<J> all;
        lock.lock();
        try {
            all = new ArrayList<>(jobs.size());
            for (Entry<J> e : jobs.values()) all.add(e.job());
            jobs.clear();
        } finally {
            lock.unlock();
        }
        all.forEach(onDrop);
    }

    public int size() {
        lock.lock();
        try {
            return jobs.size();
        } finally {
            lock.unlock();
        }
    }
}
