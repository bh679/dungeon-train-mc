package games.brennan.dungeontrain.worldgen;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.ship.CarriageDeck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * How much of a sphere's pull reaches an entity on the train. Shared by the server (mobs, items,
 * projectiles) and the client (its own player) so both make the same call from the same carriage blocks.
 *
 * <p>Carriage blocks live in Sable's plot, not the host level, so everything is read in the carriage's
 * own frame through {@link CarriageDeck#blockInPlot}. Three outcomes:</p>
 * <ul>
 *   <li>{@link Exposure#FREE} — not over a carriage: the full pull.</li>
 *   <li>{@link Exposure#RIDER} — out on the train (a roof, a flatbed, a coupling pad): the rider factor.</li>
 *   <li>{@link Exposure#SHELTERED} — inside a carriage, under its roof or between its walls: no pull.</li>
 * </ul>
 * A trimmed copy of the deck/cover/walls scans in the wind's {@code TrainExposure}; fold the two together
 * once both are on main.
 */
public final class SphereGravityShelter {

    /** Cells under the feet searched for a carriage block. */
    static final int DECK_SCAN = 6;
    /** Cells above the head searched for a carriage roof. */
    static final int COVER_SCAN = 8;
    /** Cells across the carriage searched for each side wall. */
    static final int WALL_SCAN = 8;
    /** Heights above the feet both side walls must be at (foot and head level). */
    private static final double[] WALL_ROWS = {0.3, 2.0};

    private SphereGravityShelter() {}

    public enum Exposure {
        FREE, RIDER, SHELTERED
    }

    /** {@link #classify} as a pull multiplier. */
    public static double factor(Entity e, double riderFactor) {
        return switch (classify(e)) {
            case FREE -> 1.0;
            case RIDER -> riderFactor;
            case SHELTERED -> 0.0;
        };
    }

    public static Exposure classify(Entity e) {
        SubLevel sub = subLevelUnder(e);
        if (sub == null) return Exposure.FREE;
        LevelPlot plot = sub.getPlot();
        if (plot == null) return Exposure.FREE;
        Vector3d feet = sub.logicalPose().transformPositionInverse(new Vector3d(e.getX(), e.getY() + 0.05, e.getZ()));
        if (!overDeck(plot, feet)) return Exposure.FREE;
        if (walledIn(plot, feet)) return Exposure.SHELTERED;
        BlockPos head = BlockPos.containing(feet.x, feet.y + Math.max(0.2, e.getEyeHeight()), feet.z);
        for (int dy = 1; dy <= COVER_SCAN; dy++) {
            if (solid(plot, head.above(dy))) return Exposure.SHELTERED;
        }
        return Exposure.RIDER;
    }

    /** Sable's tracking sub-level when it is carrying {@code e}, else any carriage whose bounds reach under it. */
    @Nullable
    static SubLevel subLevelUnder(Entity e) {
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(e);
        if (tracking != null && !tracking.isRemoved()) return tracking;
        BoundingBox3d probe = new BoundingBox3d(
                e.getX() - 0.5, e.getY() - DECK_SCAN, e.getZ() - 0.5,
                e.getX() + 0.5, e.getY() + 1.0, e.getZ() + 0.5);
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(e.level(), probe)) {
            if (!sub.isRemoved()) return sub;
        }
        return null;
    }

    private static boolean overDeck(LevelPlot plot, Vector3d feet) {
        BlockPos column = BlockPos.containing(feet.x, feet.y, feet.z);
        for (int dy = 0; dy <= DECK_SCAN; dy++) {
            if (solid(plot, column.below(dy))) return true;
        }
        return false;
    }

    /** Between both side walls at foot and head height — inside a carriage even under an open roof. */
    private static boolean walledIn(LevelPlot plot, Vector3d feet) {
        for (double rise : WALL_ROWS) {
            double y = feet.y + rise;
            if (!wallToward(plot, feet.x, y, feet.z, 1) || !wallToward(plot, feet.x, y, feet.z, -1)) return false;
        }
        return true;
    }

    private static boolean wallToward(LevelPlot plot, double x, double y, double z, int dir) {
        for (int step = 0; step <= WALL_SCAN; step++) {
            if (solid(plot, BlockPos.containing(x, y, z + dir * step))) return true;
        }
        return false;
    }

    private static boolean solid(LevelPlot plot, BlockPos local) {
        BlockState s = CarriageDeck.blockInPlot(plot, local);
        return !s.isAir() && s.blocksMotion();
    }
}
