package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainMotionFreeze;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.primitives.AABBdc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * When a train should stand still because everyone who could see it is inside one of its rooms.
 *
 * <h2>The problem it removes</h2>
 * <p>A pocket room's corridors — the pair's own and every copy an endless room scatters through its
 * tiles — take a player back to a carriage that is <i>still moving while they are inside</i>. Walk
 * eight rooms out and the train has rolled a long way: out of the chunks that make the crossing
 * instant, out of the simulation bubble, and eventually into Sable's holding store, at which point
 * every corridor in the room is a dead end and the room repeats forever with nowhere else to walk.
 * Everything downstream of that — force-loading the group, asking it back from holding, telling the
 * player to wait — is treatment for a journey that had no reason to happen. Nobody was watching.</p>
 *
 * <p>So the train stops. It costs nothing to stop it: a carriage's position is a pure function of
 * elapsed ticks, and {@link TrainMotionFreeze} simply stops counting them.</p>
 *
 * <h2>Both halves of the rule earn their place</h2>
 * <p><b>Somebody in one of its rooms</b> is what makes stopping right rather than arbitrary — this is
 * a portal rule, not a general "pause when idle" one, and a train nobody is riding for any other
 * reason should keep going.</p>
 *
 * <p><b>Nobody on or near it</b> is what keeps stopping invisible. A second player riding the deck
 * must not have the train halt under them, and a player watching from beside the track must not see
 * it stop dead. Both are cases where the freeze would be a visible fault rather than an invisible
 * saving.</p>
 *
 * <h2>Why a player in a room is not "near" their train</h2>
 * <p>A pocket room is stamped in its carriage's <b>own chunk columns</b>, in the basement below the
 * world — that is what makes the crossing seamless. So a player standing at the base tile is directly
 * underneath the train and only a couple of hundred blocks from it in a straight line, and a plain
 * distance test would call them "near" in precisely the situation this exists for. Anyone inside a
 * portal structure is therefore excluded from the proximity half outright: whatever their
 * coordinates, they are not somewhere the train can be seen from.</p>
 */
public final class PortalTrainFreeze {

    /**
     * How close a player outside a room has to be for the train to keep running, in blocks.
     *
     * <p>Generous on purpose. The cost of being too generous is that a train keeps moving while
     * somebody is in a room — the old behaviour, recovered by the force-load ticket — while the cost
     * of being too tight is a train visibly stopping in front of somebody, which reads as a bug. Well
     * past the distance at which a moving carriage is legible against the terrain.</p>
     */
    public static final double NEAR_TRAIN_RANGE = 96.0;

    private PortalTrainFreeze() {}

    /**
     * Decide, for every train in the level, whether it should be standing still, and tell
     * {@link TrainMotionFreeze}.
     *
     * <p>Called once per server tick from {@code PortalCarriageEvents}, which is where the occupied
     * pairs have just been worked out for the force-load pass — asking a second time would mean a
     * second walk over every structure to answer a question that has just been answered.</p>
     *
     * @param occupiedPairs pair keys with a player inside their room this tick
     * @param inStructure   is this player inside <i>any</i> portal structure — the exclusion above
     */
    public static void tick(ServerLevel level, List<ServerPlayer> players, CarriageDims dims,
                            Set<Integer> occupiedPairs,
                            BiPredicate<CarriageDims, ServerPlayer> inStructure) {
        Set<UUID> trainsWithOccupiedRooms = trainsOf(occupiedPairs);

        for (Map.Entry<UUID, List<Trains.Carriage>> train : Trains.byTrainId(level).entrySet()) {
            UUID trainId = train.getKey();
            boolean freeze = trainsWithOccupiedRooms.contains(trainId)
                && !anyoneWatching(train.getValue(), players, dims, inStructure);
            TrainMotionFreeze.setFrozen(trainId, freeze);
        }

        // After the verdicts, so a train frozen this tick starts counting this tick.
        TrainMotionFreeze.tickFrozen();
    }

    /**
     * The trains these pairs ride on.
     *
     * <p>Read off the group handles {@link PortalPairResidency} already keeps rather than by
     * searching the train registry: a pair whose group has been culled is in no train-side lookup,
     * and that is exactly the pair whose room somebody is standing in.</p>
     */
    private static Set<UUID> trainsOf(Set<Integer> pairKeys) {
        Set<UUID> trains = new HashSet<>(pairKeys.size());
        for (int pairKey : pairKeys) {
            UUID trainId = trainIdOf(pairKey);
            if (trainId != null) trains.add(trainId);
        }
        return trains;
    }

    /** The train a pair's carriage group belongs to, or {@code null} if it is not known. */
    public static UUID trainIdOf(int pairKey) {
        var ship = PortalPairResidency.groupFor(pairKey);
        if (ship == null) return null;
        return ship.getKinematicDriver() instanceof TrainTransformProvider provider
            ? provider.getTrainId()
            : null;
    }

    /**
     * True when somebody outside every dimensional carriage is on this train or close enough to watch it.
     *
     * <p>The footprint test comes first and is the strict one — a rider is on the train however far
     * the carriage has drifted from anyone else — and the range test covers the ground beside the
     * track.</p>
     */
    private static boolean anyoneWatching(List<Trains.Carriage> carriages, List<ServerPlayer> players,
                                          CarriageDims dims,
                                          BiPredicate<CarriageDims, ServerPlayer> inStructure) {
        for (ServerPlayer player : players) {
            if (inStructure.test(dims, player)) continue;
            if (CarriageDeck.isOnTrainFootprint(carriages, player)) return true;
            if (withinRange(carriages, player, NEAR_TRAIN_RANGE)) return true;
        }
        return false;
    }

    /** True when the player is within {@code range} of any of these carriages' boxes. */
    private static boolean withinRange(List<Trains.Carriage> carriages, ServerPlayer player,
                                       double range) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double r2 = range * range;
        for (Trains.Carriage carriage : carriages) {
            AABBdc bb = carriage.ship().worldAABB();
            double dx = axisGap(px, bb.minX(), bb.maxX());
            double dy = axisGap(py, bb.minY(), bb.maxY());
            double dz = axisGap(pz, bb.minZ(), bb.maxZ());
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    /** Distance from {@code v} to the interval {@code [min, max]}, or 0 inside it. */
    private static double axisGap(double v, double min, double max) {
        if (v < min) return min - v;
        if (v > max) return v - max;
        return 0.0;
    }
}
