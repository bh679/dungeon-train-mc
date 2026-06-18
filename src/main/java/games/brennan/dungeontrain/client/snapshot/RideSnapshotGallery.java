package games.brennan.dungeontrain.client.snapshot;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory store of the run's ride photos. A bounded ring (oldest evicted
 * first) keeps VRAM small; each evicted/cleared snapshot's
 * {@link net.minecraft.client.renderer.texture.DynamicTexture} is released
 * through the texture manager. The gallery is cleared on world leave / run
 * start by {@link RideSnapshotDirector}, so each run's death screen only ever
 * shows that run's photos.
 *
 * <p>All access is on the client main thread; the {@code synchronized} guards
 * are belt-and-braces against an unexpected caller.</p>
 */
public final class RideSnapshotGallery {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Deque<RideSnapshot> SHOTS = new ArrayDeque<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private RideSnapshotGallery() {}

    public static synchronized void add(RideSnapshot shot, int maxStored) {
        SHOTS.addLast(shot);
        while (SHOTS.size() > Math.max(1, maxStored)) {
            RideSnapshot evicted = SHOTS.pollFirst();
            if (evicted != null) release(evicted);
        }
    }

    /** Newest shot carrying {@code tag}, or {@code null} if none. */
    public static synchronized RideSnapshot latestOf(SnapshotTag tag) {
        RideSnapshot match = null;
        for (RideSnapshot s : SHOTS) {
            if (s.tag() == tag) match = s; // iteration is oldest→newest, so last match wins
        }
        return match;
    }

    /** Newest shot of any tag, or {@code null}. */
    public static synchronized RideSnapshot latest() {
        return SHOTS.peekLast();
    }

    /** Uniformly-random shot of any tag, or {@code null}. */
    public static synchronized RideSnapshot random() {
        if (SHOTS.isEmpty()) return null;
        int idx = RANDOM.nextInt(SHOTS.size());
        int i = 0;
        for (RideSnapshot s : SHOTS) {
            if (i++ == idx) return s;
        }
        return SHOTS.peekLast();
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

    /** Release every texture and empty the store (run end / world leave). */
    public static synchronized void clear() {
        int n = SHOTS.size();
        for (RideSnapshot s : SHOTS) release(s);
        SHOTS.clear();
        if (n > 0) LOGGER.debug("[DungeonTrain] Ride gallery cleared ({} shots released)", n);
    }

    private static void release(RideSnapshot shot) {
        try {
            Minecraft.getInstance().getTextureManager().release(shot.texture());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Failed releasing ride snapshot texture {}", shot.texture(), e);
        }
    }
}
