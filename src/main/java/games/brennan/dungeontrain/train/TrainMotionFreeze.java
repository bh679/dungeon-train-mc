package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticks that do not count, per train — how a train stands still without anything stopping.
 *
 * <h2>Why a counter rather than a brake</h2>
 * <p>A carriage's position is a pure function of game time:
 * {@code canonicalPos = spawnWorldPos + velocity × (now − spawnTick) × dt}, re-derived from scratch
 * on every physics tick so that a sub-level which misses ticks while its chunks are unloaded does not
 * drift behind its siblings ({@link TrainTransformProvider}). There is no per-tick step to skip and no
 * velocity to zero — setting the velocity to zero would re-evaluate the <i>whole history</i> of that
 * formula and yank the train back to where it spawned. The only place a train can be stopped is the
 * elapsed term, and the only way to stop it there is to stop counting.</p>
 *
 * <p>So a freeze is arithmetic, and resuming needs no code at all: the frozen ticks were never
 * counted, so elapsed continues from exactly where it stopped. No jump either way.</p>
 *
 * <h2>Counted on the server tick</h2>
 * <p>{@link #tickFrozen} is driven from the server tick rather than from any carriage's physics tick,
 * because the trains most worth freezing are the ones whose sub-levels are not ticking — a train
 * nobody is near is exactly a train Sable may have culled. A counter that advanced only while the
 * thing it describes was loaded would under-count precisely when it mattered, and the train would
 * jump forward the moment it came back.</p>
 *
 * <h2>Unknown trains are free</h2>
 * <p>{@link #frozenTicks} answers 0 for a train that has never frozen, and every carriage captures
 * that answer at spawn, so a train that never freezes computes bit-identical positions to one built
 * before this class existed.</p>
 */
public final class TrainMotionFreeze {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Train id → ticks it has spent frozen, cumulative and monotonic. */
    private static final Map<UUID, Long> FROZEN_TICKS = new ConcurrentHashMap<>();

    /** Trains frozen right now. */
    private static final Set<UUID> FROZEN = ConcurrentHashMap.newKeySet();

    private TrainMotionFreeze() {}

    /**
     * Set whether this train is standing still.
     *
     * <p>Idempotent per tick and logged only on the edges: the caller re-states its verdict every
     * tick, and a line either way per tick would be twenty a second for as long as somebody stays in
     * a room.</p>
     */
    public static void setFrozen(UUID trainId, boolean frozen) {
        if (trainId == null) return;
        boolean changed = frozen ? FROZEN.add(trainId) : FROZEN.remove(trainId);
        if (!changed) return;
        LOGGER.info("[DungeonTrain] Train {} {} — {} frozen ticks so far",
            trainId, frozen ? "stopped" : "moving again", frozenTicks(trainId));
    }

    /** True if this train is standing still right now. */
    public static boolean isFrozen(UUID trainId) {
        return trainId != null && FROZEN.contains(trainId);
    }

    /**
     * Advance the counter of every frozen train by one tick. Call once per server tick.
     *
     * <p>After the verdicts for that tick have been set, so a train frozen this tick starts counting
     * this tick rather than next.</p>
     */
    public static void tickFrozen() {
        if (FROZEN.isEmpty()) return;
        for (UUID trainId : FROZEN) {
            FROZEN_TICKS.merge(trainId, 1L, Long::sum);
        }
    }

    /**
     * Let every frozen train go, without touching what they have already accumulated.
     *
     * <p>For the paths that stop asking: portals switched off for the world, or no players online.
     * The verdict is re-stated every tick by whoever is asking, so a train only stays frozen while
     * something keeps saying so — but the asking itself can stop, and a flag left set with nothing
     * advancing its counter is a train that is neither stopped nor running.</p>
     *
     * <p><b>Not {@link #clear()}.</b> Dropping the counts mid-session would take every historical
     * frozen tick back out of the subtraction at once, and every carriage would lurch forward by the
     * whole time the train had ever spent standing still.</p>
     */
    public static void thawAll() {
        if (FROZEN.isEmpty()) return;
        LOGGER.info("[DungeonTrain] Releasing {} frozen train(s) — nothing is asking them to stop",
            FROZEN.size());
        FROZEN.clear();
    }

    /** How many ticks this train has spent frozen in total. 0 for a train that never has. */
    public static long frozenTicks(UUID trainId) {
        if (trainId == null) return 0L;
        return FROZEN_TICKS.getOrDefault(trainId, 0L);
    }

    /**
     * Forget everything.
     *
     * <p>Wired to server stop, with the rest of the train registries: a train id is regenerated per
     * world, and a surviving count would silently subtract somebody else's stopped time from a fresh
     * train's elapsed ticks — which is a train that starts life somewhere behind where it belongs.</p>
     */
    public static void clear() {
        FROZEN.clear();
        FROZEN_TICKS.clear();
    }
}
