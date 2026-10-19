package games.brennan.dungeontrain.wind;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.ship.CarriageDeck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Is an entity out in the wind on top of the train? Shared by the server (mobs, items, projectiles) and the
 * client (the local player), so both sides make the same call from the same carriage blocks.
 *
 * <p>Everything is read in the carriage's own frame (ship-local / plot space) — Sable keeps carriage blocks in
 * the plot, not in the host level, so {@code level.getBlockState} would read air (see
 * {@link CarriageDeck#blockAt}). Three tests, all cheap block lookups:</p>
 * <ol>
 *   <li><b>Over the train</b>: a solid carriage block within {@link #DECK_SCAN} cells under any part of the
 *       footprint — a roof, a flatbed or a coupling pad. Once clear of the edge it stops being pushed and falls.</li>
 *   <li><b>Not under cover</b>: nothing solid within {@link #COVER_SCAN} cells above the head, so anyone
 *       inside an enclosed carriage is sheltered.</li>
 *   <li><b>Not in a lee</b>: nothing solid within {@link #LEE_SCAN} blocks upwind at foot or eye height, so
 *       crouching behind a wall, a chest or a parapet shelters you from the direction the wind comes from.</li>
 * </ol>
 */
public final class TrainExposure {

    static final int DECK_SCAN = 6;
    static final int COVER_SCAN = 8;
    static final int LEE_SCAN = 4;
    private static final double FOOT_PROBE = 0.3;

    private TrainExposure() {}

    /** Why an entity is or isn't in the wind (shown on the F3+8 wind panel). */
    public enum Result {
        NOT_ON_TRAIN, UNDER_COVER, IN_LEE, EXPOSED;

        public boolean exposed() {
            return this == EXPOSED;
        }
    }

    /**
     * True when {@code e} stands (or hangs) over {@code sub} in the open and nothing upwind shelters it.
     *
     * @param dirX,dirZ world-space direction the wind blows toward (unit)
     */
    public static boolean isExposed(SubLevel sub, Entity e, double dirX, double dirZ) {
        return classify(sub, e, dirX, dirZ).exposed();
    }

    /** {@link #isExposed} with the reason. */
    public static Result classify(SubLevel sub, Entity e, double dirX, double dirZ) {
        if (sub == null || sub.isRemoved()) return Result.NOT_ON_TRAIN;
        LevelPlot plot = sub.getPlot();
        if (plot == null) return Result.NOT_ON_TRAIN;
        Pose3dc pose = sub.logicalPose();
        Vector3d feet = pose.transformPositionInverse(new Vector3d(e.getX(), e.getY() + 0.05, e.getZ()));

        if (!overDeck(plot, feet, e.getBbWidth() / 2.0)) return Result.NOT_ON_TRAIN;

        double eye = Math.max(0.2, e.getEyeHeight());
        BlockPos head = BlockPos.containing(feet.x, feet.y + eye, feet.z);
        for (int dy = 1; dy <= COVER_SCAN; dy++) {
            if (solid(plot, head.above(dy))) return Result.UNDER_COVER;
        }

        // Upwind = against the wind, rotated into the carriage frame (trains only yaw, but don't assume it).
        Vector3d up = pose.transformNormalInverse(new Vector3d(-dirX, 0.0, -dirZ));
        double len = Math.sqrt(up.x * up.x + up.z * up.z);
        if (len < 1e-6) return Result.EXPOSED;
        double ux = up.x / len;
        double uz = up.z / len;
        for (int step = 1; step <= LEE_SCAN; step++) {
            double x = feet.x + ux * step;
            double z = feet.z + uz * step;
            if (solid(plot, BlockPos.containing(x, feet.y + FOOT_PROBE, z))) return Result.IN_LEE;
            if (solid(plot, BlockPos.containing(x, feet.y + eye, z))) return Result.IN_LEE;
        }
        return Result.EXPOSED;
    }

    /**
     * The carriage the client-side player is over: Sable's tracking sub-level when it is carrying them, else
     * any sub-level whose bounds reach under their feet (mid-jump, or standing on a pad Sable hasn't latched).
     */
    @Nullable
    public static SubLevel subLevelUnder(Level level, Entity e) {
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(e);
        if (tracking != null && !tracking.isRemoved()) return tracking;
        BoundingBox3d probe = new BoundingBox3d(
                e.getX() - 0.5, e.getY() - DECK_SCAN, e.getZ() - 0.5,
                e.getX() + 0.5, e.getY() + 1.0, e.getZ() + 0.5);
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, probe)) {
            if (!sub.isRemoved()) return sub;
        }
        return null;
    }

    /**
     * Any part of the entity's footprint still over the carriage: its centre or one of its four corners. Using
     * the corners keeps the wind pushing something that overhangs the edge until it is clear of the train, so it
     * can actually be blown off rather than parked on the lip. (Corner offsets are taken in the carriage frame;
     * a yawed carriage turns the footprint a little, which only matters at the edge.)
     */
    private static boolean overDeck(LevelPlot plot, Vector3d feet, double half) {
        double[][] probes = {{0, 0}, {-half, -half}, {-half, half}, {half, -half}, {half, half}};
        for (double[] o : probes) {
            BlockPos column = BlockPos.containing(feet.x + o[0], feet.y, feet.z + o[1]);
            for (int dy = 0; dy <= DECK_SCAN; dy++) {
                if (solid(plot, column.below(dy))) return true;
            }
        }
        return false;
    }

    private static boolean solid(LevelPlot plot, BlockPos local) {
        BlockState s = CarriageDeck.blockInPlot(plot, local);
        return !s.isAir() && s.blocksMotion();
    }
}
