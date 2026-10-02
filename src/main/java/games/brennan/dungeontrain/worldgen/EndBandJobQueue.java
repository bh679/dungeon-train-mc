package games.brennan.dungeontrain.worldgen;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The samplers' pending jobs, handed out <b>nearest player first</b> — the one queue behind
 * {@link SamplerPool}, shared by {@link EndBandSampler} and {@link ForeignSphereSampler}. Free of Minecraft
 * types so the ordering is unit-testable.
 *
 * <p>The sampler used to be a first-in-first-out pool. The prefetch strip keeps queuing chunks beyond the
 * view distance, so under load the chunks right beside the player waited behind them — and showed as
 * squares of void in the islands until their copy finally landed (or never, if the player moved on). Here
 * each take re-scores every waiting job against the latest player snapshot, so a chunk you can see always
 * goes first, and a <b>droppable</b> job no player is near any more is dropped (its chunk asks again when
 * it reloads).</p>
 *
 * <p>A job is droppable only if something will ask for it again. One that isn't (a sphere owed to a loaded
 * chunk) is never dropped by distance and never evicted to make room: when the queue is full and holds
 * nothing droppable, {@link #offer} refuses the newcomer and the caller keeps it for later.</p>
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

    private record Entry<J>(int cx, int cz, J job, boolean droppable) {}

    private final Map<Object, Entry<J>> jobs = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final int capacity;
    private volatile Players players = Players.NONE;

    /** A queue with no cap. */
    public EndBandJobQueue() {
        this(Integer.MAX_VALUE);
    }

    /** A queue holding at most {@code capacity} waiting jobs ({@link #offer}). */
    public EndBandJobQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Replace the player snapshot the next takes are ordered by. Server thread. */
    public void setPlayers(Players snapshot) {
        players = snapshot == null ? Players.NONE : snapshot;
    }

    public Players players() {
        return players;
    }

    public int capacity() {
        return capacity;
    }

    /** Queue a droppable {@code job} for chunk {@code (cx, cz)} under {@code key}; a job already queued under it is replaced. */
    public void add(Object key, int cx, int cz, J job) {
        offer(key, cx, cz, job, true, evicted -> { });
    }

    /**
     * Queue {@code job} for chunk {@code (cx, cz)} under {@code key}, replacing a job already queued under
     * it. When the queue is full, room is made by evicting the droppable job furthest from every player —
     * handed to {@code onEvict} outside the lock — but only if the newcomer is not droppable or is nearer
     * than it. Returns {@code false} if the job was not queued: the queue is full and nothing may give way.
     */
    public boolean offer(Object key, int cx, int cz, J job, boolean droppable, Consumer<J> onEvict) {
        J evicted = null;
        lock.lock();
        try {
            if (jobs.size() >= capacity && !jobs.containsKey(key)) {
                Players snapshot = players;
                Object victim = furthestDroppableLocked(snapshot);
                if (victim == null) return false;
                Entry<J> v = jobs.get(victim);
                if (droppable && snapshot.distance(v.cx(), v.cz()) <= snapshot.distance(cx, cz)) return false;
                evicted = jobs.remove(victim).job();
            }
            jobs.put(key, new Entry<>(cx, cz, job, droppable));
        } finally {
            lock.unlock();
        }
        if (evicted != null) onEvict.accept(evicted);
        return true;
    }

    /** The key of the droppable job furthest from every player — the newest of them on a tie — or {@code null}. */
    private Object furthestDroppableLocked(Players snapshot) {
        Object worstKey = null;
        int worstDist = -1;
        for (Map.Entry<Object, Entry<J>> e : jobs.entrySet()) {
            if (!e.getValue().droppable()) continue;
            int d = snapshot.distance(e.getValue().cx(), e.getValue().cz());
            if (d >= worstDist) {
                worstDist = d;
                worstKey = e.getKey();
            }
        }
        return worstKey;
    }

    /**
     * Remove and return the waiting job nearest a player, or {@code null} if none is waiting. Never blocks.
     * With players present, droppable jobs further than {@code keepRadius} chunks from all of them are
     * removed and handed to {@code onDrop} (outside the lock) first. With no players (a headless forceload)
     * the oldest job goes first and nothing is dropped.
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
        Object bestKey = null;
        int bestDist = Integer.MAX_VALUE;
        Iterator<Map.Entry<Object, Entry<J>>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Entry<J>> e = it.next();
            int d = snapshot.distance(e.getValue().cx(), e.getValue().cz());
            if (e.getValue().droppable() && !snapshot.isEmpty() && d > keepRadius) {
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

    /** Drop every waiting job {@code match} accepts, droppable or not, handing each to {@code onDrop} outside the lock. */
    public void removeIf(Predicate<J> match, Consumer<J> onDrop) {
        List<J> removed = new ArrayList<>();
        lock.lock();
        try {
            Iterator<Entry<J>> it = jobs.values().iterator();
            while (it.hasNext()) {
                J job = it.next().job();
                if (!match.test(job)) continue;
                removed.add(job);
                it.remove();
            }
        } finally {
            lock.unlock();
        }
        removed.forEach(onDrop);
    }

    /** Drop every waiting job, handing each to {@code onDrop}. */
    public void clear(Consumer<J> onDrop) {
        removeIf(job -> true, onDrop);
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
