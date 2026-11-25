package games.brennan.dungeontrain.train;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.CarriageBoxIndex;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.ResidentCarriageBoxes;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.joml.primitives.AABBdc;

/**
 * Mob AI's carriage lookups: "the carriage at this point" and "the nearest carriages to this point".
 *
 * <p>Mob goals ask these several times per mob per tick. Answering by walking
 * {@link Trains#allCarriages} (a shipyard walk, a list, a record and a fresh box per carriage) made
 * the cost mobs x carriages, every tick. Here the candidates come from the once-per-tick
 * {@link ResidentCarriageBoxes#trainsForTick train index}, and only those few are settled against
 * their live box, so a lookup costs about the same on a 5-carriage train as on a 200-carriage one.</p>
 *
 * <p>Brennan's rule: a mob never needs more than the closest two carriages, including the one it
 * stands on, so {@link #nearestTwo} returns at most two.</p>
 *
 * <p>Server thread only for the cached path; any other thread gets the old walk, same answers.</p>
 */
public final class NearbyCarriages {

    /** Per-call scratch. Server thread only, like the snapshot it reads. */
    private static final double[] SCRATCH = new double[6];
    private static final int[] PICKED = new int[2];

    private NearbyCarriages() {}

    /**
     * The train carriage whose live box, grown by {@code margin}, holds the point, or null. Where two
     * carriages' grown boxes overlap (a group join), the first in shipyard order, as the old loop did.
     */
    public static Trains.Carriage at(ServerLevel level, double x, double y, double z, double margin) {
        if (!level.getServer().isSameThread()) return atUncached(level, x, y, z, margin);
        CarriageBoxIndex<Trains.Carriage> index = ResidentCarriageBoxes.trainsForTick(level);
        int i = index.firstContaining(x, y, z, margin, NearbyCarriages::readLive, SCRATCH);
        return i < 0 ? null : index.handle(i);
    }

    /**
     * The (at most two) train carriages whose live box lies within {@code pad} of the point, nearest
     * first (one the point is inside is at distance 0). Only carriages with a live Sable sub-level.
     */
    public static List<ManagedShip> nearestTwo(ServerLevel level, double x, double y, double z, double pad) {
        if (!level.getServer().isSameThread()) return nearestTwoUncached(level, x, y, z, pad);
        CarriageBoxIndex<Trains.Carriage> index = ResidentCarriageBoxes.trainsForTick(level);
        if (index.size() == 0) return List.of();
        int n = index.nearestTwo(x, y, z, pad, NearbyCarriages::readLive, SCRATCH, PICKED);
        if (n == 0) return List.of();
        List<ManagedShip> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) out.add(index.handle(PICKED[k]).ship());
        return out;
    }

    /**
     * Reads a carriage's live world box with no allocation. A sub-level gone or removed since the
     * snapshot reads as an empty box, which the index skips (a culled wrapper's frozen box is not
     * where the carriage is).
     */
    static void readLive(Trains.Carriage c, double[] out) {
        ManagedShip ship = c.ship();
        if (ship instanceof SableManagedShip sable) {
            ServerSubLevel sl = sable.subLevel();
            if (sl == null || sl.isRemoved()) {
                Arrays.fill(out, 0.0);
                return;
            }
            BoundingBox3dc b = sl.boundingBox();
            out[0] = b.minX(); out[1] = b.minY(); out[2] = b.minZ();
            out[3] = b.maxX(); out[4] = b.maxY(); out[5] = b.maxZ();
            return;
        }
        if (!ship.isResident()) {
            Arrays.fill(out, 0.0);
            return;
        }
        AABBdc b = ship.worldAABB();
        out[0] = b.minX(); out[1] = b.minY(); out[2] = b.minZ();
        out[3] = b.maxX(); out[4] = b.maxY(); out[5] = b.maxZ();
    }

    /** The pre-cache walk, for callers off the server thread. */
    private static Trains.Carriage atUncached(ServerLevel level, double x, double y, double z, double margin) {
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            AABBdc bb = c.ship().worldAABB();
            if (bb == null) continue;
            if (x >= bb.minX() - margin && x <= bb.maxX() + margin
                && y >= bb.minY() - margin && y <= bb.maxY() + margin
                && z >= bb.minZ() - margin && z <= bb.maxZ() + margin) {
                return c;
            }
        }
        return null;
    }

    private static List<ManagedShip> nearestTwoUncached(ServerLevel level, double x, double y, double z, double pad) {
        List<Trains.Carriage> carriages = new ArrayList<>();
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            if (c.ship() instanceof SableManagedShip sable && sable.subLevel() != null) carriages.add(c);
        }
        CarriageBoxIndex<Trains.Carriage> index = CarriageBoxIndex.of(carriages, NearbyCarriages::readLive, 0.0);
        double[] scratch = new double[6];
        int[] picked = new int[2];
        int n = index.nearestTwo(x, y, z, pad, NearbyCarriages::readLive, scratch, picked);
        List<ManagedShip> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) out.add(index.handle(picked[k]).ship());
        return out;
    }
}
