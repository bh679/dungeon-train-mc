package games.brennan.dungeontrain.client.snapshot;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Store of the run's ride photos. Photos start in memory (a live texture); when the game has
 * headroom and a menu is open the director drains them to disk via {@link #flushSomeToDisk}
 * ({@link RideSnapshot#flush()} releases the texture), freeing VRAM/RAM mid-run. Two caps,
 * decided by {@link SnapshotRetention}, bound the store: at most {@code maxInMemory} unflushed
 * photos in memory (oldest dropped if no offload window is available — the old fixed-ring
 * behaviour) and at most {@code maxOnDisk} retained in total (oldest discarded, deleting its
 * file). The gallery is cleared on world leave by {@link RideSnapshotDirector}, so each run's
 * death screen only ever shows that run's photos.
 *
 * <p>While the death screen draws (between {@link #freeze()} and {@link #clear()}) the store is
 * frozen: no flush, eviction or release runs, so a texture being blitted can't be pulled out from
 * under the painter.</p>
 *
 * <p>All access is on the client main thread; the {@code synchronized} guards are belt-and-braces
 * against an unexpected caller (the director's pending-flag setters can fire on the network thread).</p>
 */
public final class RideSnapshotGallery {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Deque<RideSnapshot> SHOTS = new ArrayDeque<>();
    private static volatile boolean frozen;

    private RideSnapshotGallery() {}

    /** Append a freshly-captured (in-memory) photo and enforce both retention caps. */
    public static synchronized void add(RideSnapshot shot, int maxInMemory, int maxOnDisk) {
        SHOTS.addLast(shot);
        enforceCaps(maxInMemory, maxOnDisk);
    }

    /**
     * Offload up to {@code budget} of the oldest in-memory photos to disk, releasing their
     * textures. No-op while frozen (death screen open). Returns how many were flushed.
     */
    public static synchronized int flushSomeToDisk(int budget) {
        if (frozen || budget <= 0) return 0;
        int done = 0;
        for (RideSnapshot s : SHOTS) {
            if (done >= budget) break;
            if (s.isInMemoryUnflushed() && s.flush()) done++;
        }
        if (done > 0) {
            LOGGER.debug("[DungeonTrain] Offloaded {} ride snapshot(s) to disk (gallery={})", done, SHOTS.size());
        }
        return done;
    }

    public static synchronized List<RideSnapshot> all() {
        return new ArrayList<>(SHOTS);
    }

    public static synchronized boolean isEmpty() {
        return SHOTS.isEmpty();
    }

    public static synchronized int size() {
        return SHOTS.size();
    }

    /** Freeze the store while the death screen draws — blocks flush / eviction / release. */
    public static synchronized void freeze() {
        frozen = true;
    }

    /** Lift the freeze (death screen dismissed without a world leave). */
    public static synchronized void unfreeze() {
        frozen = false;
    }

    public static synchronized boolean isFrozen() {
        return frozen;
    }

    /** Release every live texture, empty the store, and delete the run's disk files (run end / world leave). */
    public static synchronized void clear() {
        int n = SHOTS.size();
        for (RideSnapshot s : SHOTS) s.releaseTexture();
        SHOTS.clear();
        frozen = false;
        RideSnapshotDisk.deleteRunDir();
        if (n > 0) LOGGER.debug("[DungeonTrain] Ride gallery cleared ({} shots released)", n);
    }

    /** Apply the {@link SnapshotRetention} decision, fully discarding the chosen (oldest) photos. */
    private static void enforceCaps(int maxInMemory, int maxOnDisk) {
        int n = SHOTS.size();
        if (n == 0) return;
        boolean[] onDisk = new boolean[n];
        int i = 0;
        for (RideSnapshot s : SHOTS) onDisk[i++] = s.isFlushed();

        int[] remove = SnapshotRetention.toRemove(onDisk, maxInMemory, maxOnDisk);
        if (remove.length == 0) return;

        Set<Integer> drop = new HashSet<>();
        for (int idx : remove) drop.add(idx);

        List<RideSnapshot> kept = new ArrayList<>(n - remove.length);
        i = 0;
        for (RideSnapshot s : SHOTS) {
            if (drop.contains(i)) s.discard();
            else kept.add(s);
            i++;
        }
        SHOTS.clear();
        SHOTS.addAll(kept);
    }
}
