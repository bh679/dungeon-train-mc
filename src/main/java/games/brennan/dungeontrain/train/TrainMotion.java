package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A train's velocity, and when it changed — the one place either is decided.
 *
 * <h2>Why the train owns it and not the carriages</h2>
 * <p>A carriage's position is {@code anchor + velocity × ticks since the anchor}
 * ({@link TrainTransformProvider}), so a change of velocity has to move the anchor onto where the
 * carriage was <i>at the tick the velocity changed</i>, or the new speed re-prices travel it never
 * made. Telling each carriage separately cannot get that right for a carriage that is not being
 * ticked: one culled to Sable holding is not in {@code Shipyard.findAll()} and never hears about
 * the change at all, and one that does hear but is ticked eighty ticks later re-bases onto where it
 * stood eighty ticks ago. Both came back out of formation — overlapping the neighbour, or drifting
 * away from it at the difference between the two speeds.</p>
 *
 * <p>So the change is written down once, here, with the tick it happened on, and every carriage
 * replays what it missed the next time it is ticked ({@code TrainTransformProvider.nextTransform}).
 * Loaded, parked or held makes no difference: they all read the same schedule and land on the same
 * line. The same shape as {@link TrainMotionFreeze}, for the same reason.</p>
 *
 * <h2>Unknown trains are free</h2>
 * <p>A train whose speed has never been changed has no entry: {@link #epoch} answers 0,
 * {@link #changesSince} answers nothing, and its carriages compute exactly what they computed
 * before this class existed.</p>
 */
public final class TrainMotion {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One change of velocity.
     *
     * @param gameTick             the level game time it was made on
     * @param frozenTicksAtChange  {@link TrainMotionFreeze#frozenTicks} at that moment — the travel
     *                             up to the change is priced with it, so every carriage subtracts
     *                             the same stopped time whenever it gets round to replaying
     * @param velocity             the velocity from this tick on
     */
    public record Change(long gameTick, long frozenTicksAtChange, Vector3dc velocity) {}

    /** Train id → every change made to it this session, oldest first. Lists are immutable. */
    private static final Map<UUID, List<Change>> CHANGES = new ConcurrentHashMap<>();

    private TrainMotion() {}

    /** How many changes this train has had. A carriage that has applied this many is up to date. */
    public static int epoch(UUID trainId) {
        if (trainId == null) return 0;
        return CHANGES.getOrDefault(trainId, List.of()).size();
    }

    /** The changes a carriage at {@code epoch} has not applied yet, oldest first. */
    public static List<Change> changesSince(UUID trainId, int epoch) {
        if (trainId == null) return List.of();
        List<Change> all = CHANGES.getOrDefault(trainId, List.of());
        if (epoch >= all.size()) return List.of();
        return all.subList(Math.max(0, epoch), all.size());
    }

    /** The train's velocity now: its latest change, or {@code fallback} if it has never had one. */
    public static Vector3dc velocityOr(UUID trainId, Vector3dc fallback) {
        if (trainId == null) return fallback;
        List<Change> all = CHANGES.getOrDefault(trainId, List.of());
        return all.isEmpty() ? fallback : all.get(all.size() - 1).velocity();
    }

    /**
     * Change this train's velocity from {@code gameTick} on.
     *
     * <p>A write of the velocity it already has is not a change (the settings screen re-applies the
     * current speed on every save). {@code previous} is what the train was doing before its first
     * recorded change — any of its carriages can say; {@code null} if none can be found, in which
     * case the change is recorded and each carriage decides for itself whether it is one.</p>
     *
     * @return true if a change was recorded
     */
    public static boolean setVelocity(UUID trainId, Vector3dc previous, Vector3dc next, long gameTick) {
        if (trainId == null || next == null) return false;
        Vector3dc current = velocityOr(trainId, previous);
        if (current != null && !TrainTransformProvider.shouldRebaseOnVelocityChange(current, next)) {
            return false;
        }
        long frozen = TrainMotionFreeze.frozenTicks(trainId);
        CHANGES.merge(trainId, List.of(new Change(gameTick, frozen, new Vector3d(next))), (old, one) -> {
            // A change can never predate the one before it; a clock that somehow ran backwards
            // would otherwise price a negative stretch of travel into every carriage.
            Change last = old.get(old.size() - 1);
            Change added = one.get(0);
            List<Change> grown = new ArrayList<>(old);
            grown.add(added.gameTick() >= last.gameTick()
                ? added
                : new Change(last.gameTick(), added.frozenTicksAtChange(), added.velocity()));
            return List.copyOf(grown);
        });
        LOGGER.info("[DungeonTrain] Train {} velocity {} -> {} at tick {} (change #{})",
            trainId, current == null ? "?" : TrainTransformProvider.fmt(current),
            TrainTransformProvider.fmt(next), gameTick, epoch(trainId));
        return true;
    }

    /**
     * Distance along X a train covers from {@code fromTick} to {@code toTick}, starting at
     * {@code startVelX} and taking each of {@code changes} at its own tick. Frozen ticks are taken
     * out stretch by stretch, from the counts recorded at each end of it.
     *
     * <p>For extrapolating a position read at one tick to another without a carriage to ask —
     * {@code TrainCarriageAppender}'s line fix places a fully culled train this way. With no
     * changes it is {@code TrainTransformProvider.travelDistance(startVelX, elapsed)} exactly.</p>
     */
    public static double travelX(double startVelX, long fromTick, long frozenFrom,
                                 List<Change> changes, long toTick, long frozenTo) {
        double distance = 0.0;
        double velX = startVelX;
        long tick = fromTick;
        long frozen = frozenFrom;
        for (Change change : changes) {
            if (change.gameTick() >= toTick) break;
            if (change.gameTick() > tick) {
                distance += TrainTransformProvider.travelDistance(velX,
                    countedTicks(tick, frozen, change.gameTick(), change.frozenTicksAtChange()));
                tick = change.gameTick();
                frozen = change.frozenTicksAtChange();
            }
            velX = change.velocity().x();
        }
        return distance + TrainTransformProvider.travelDistance(velX,
            countedTicks(tick, frozen, toTick, frozenTo));
    }

    /** Ticks between two moments that were not spent frozen; both terms clamped at zero. */
    private static long countedTicks(long fromTick, long frozenFrom, long toTick, long frozenTo) {
        long frozenBetween = Math.max(0L, frozenTo - frozenFrom);
        return Math.max(0L, (toTick - fromTick) - frozenBetween);
    }

    /**
     * Forget everything. Wired wherever {@link TrainMotionFreeze#clear()} is, and for its reason: a
     * train id is regenerated per world, and the frozen counts each change was recorded against go
     * at the same moment.
     */
    public static void clear() {
        CHANGES.clear();
    }
}
