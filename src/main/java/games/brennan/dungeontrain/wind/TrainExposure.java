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
 *       footprint — a roof, a flatbed or a coupling pad. Once clear of the edge it falls; on the ground
 *       {@link GroundExposure} takes over for players.</li>
 *   <li><b>Not in a tunnel</b>: no solid terrain within {@link #TERRAIN_COVER_SCAN} blocks overhead
 *       ({@link #underTerrain}) — tunnels, bridges and overhangs stop the wind.</li>
 *   <li><b>Not between the walls</b>: inside a carriage counts even under an open roof ({@link #walledIn}).</li>
 *   <li><b>Not under cover</b>: nothing solid within {@link #COVER_SCAN} cells above the head, so anyone
 *       inside an enclosed carriage is sheltered.</li>
 *   <li><b>Not in a lee</b>: nothing solid within {@link #LEE_SCAN} blocks upwind at foot or eye height, so
 *       crouching behind a wall, a chest or a parapet shelters you from the direction the wind comes from.</li>
 * </ol>
 */
public final class TrainExposure {

    static final int DECK_SCAN = 6;
    static final int COVER_SCAN = 8;
    /** How far downwind a solid block shelters — a wall covers the whole 9-block room behind it. */
    static final int LEE_SCAN = 8;
    /** How far across the carriage to look for each side wall. */
    static final int WALL_SCAN = 8;
    /** Heights above the feet the side walls must be at (foot and head level). */
    private static final double[] WALL_ROWS = {0.3, 2.0};
    /**
     * How far above the head terrain still shelters you. A DT tunnel's ceiling sits 3 blocks above the
     * carriage roof ({@code TunnelGeometry}), so 12 covers it with room for bridges and overhangs, while a tall
     * open space (a Nether band's high roof) stays windy.
     */
    static final int TERRAIN_COVER_SCAN = 12;
    private static final double FOOT_PROBE = 0.3;

    private TrainExposure() {}

    /** Why an entity is or isn't in the wind (shown on the F3+8 wind panel). */
    public enum Result {
        NOT_ON_TRAIN, IN_TUNNEL, UNDER_COVER, IN_LEE, EXPOSED;

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
        if (underTerrain(e)) return Result.IN_TUNNEL;
        if (walledIn(plot, feet)) return Result.UNDER_COVER;

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
     * Boxed in by carriage blocks on every side — the frostbite's only shelter. Solid plot blocks within
     * {@link #WALL_SCAN} along both axes at foot and head height, within {@link #COVER_SCAN} overhead and within
     * {@link #DECK_SCAN} underfoot. A flatbed, a coupling pad, an open roof or a doorway-sized gap in the direction
     * scanned leaves you in the cold; the wind's lee and cover tests are not enough here.
     */
    public static boolean enclosed(SubLevel sub, Entity e) {
        if (sub == null || sub.isRemoved()) return false;
        LevelPlot plot = sub.getPlot();
        if (plot == null) return false;
        Vector3d feet = sub.logicalPose().transformPositionInverse(new Vector3d(e.getX(), e.getY() + 0.05, e.getZ()));
        for (double rise : WALL_ROWS) {
            double y = feet.y + rise;
            if (!wallToward(plot, feet.x, y, feet.z, 0, 1) || !wallToward(plot, feet.x, y, feet.z, 0, -1)) return false;
            if (!wallToward(plot, feet.x, y, feet.z, 1, 0) || !wallToward(plot, feet.x, y, feet.z, -1, 0)) return false;
        }
        BlockPos column = BlockPos.containing(feet.x, feet.y, feet.z);
        boolean floor = false;
        for (int dy = 0; dy <= DECK_SCAN && !floor; dy++) floor = solid(plot, column.below(dy));
        if (!floor) return false;
        double eye = Math.max(0.2, e.getEyeHeight());
        BlockPos head = BlockPos.containing(feet.x, feet.y + eye, feet.z);
        for (int dy = 1; dy <= COVER_SCAN; dy++) {
            if (solid(plot, head.above(dy))) return true;
        }
        return false;
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
    /**
     * Between the carriage's two side walls at both foot and head height — inside a carriage even when its
     * roof is open (open-roof and sunroof templates), so nothing in a room is blown about. The train runs along
     * local X, so the walls are across local Z. A roof's 1-block railing doesn't reach head height, and a
     * flatbed or coupling pad has no walls, so those stay exposed.
     */
    public static boolean walledIn(LevelPlot plot, Vector3d feet) {
        for (double rise : WALL_ROWS) {
            double y = feet.y + rise;
            if (!wallToward(plot, feet.x, y, feet.z, 1) || !wallToward(plot, feet.x, y, feet.z, -1)) return false;
        }
        return true;
    }

    private static boolean wallToward(LevelPlot plot, double x, double y, double z, int dir) {
        return wallToward(plot, x, y, z, 0, dir);
    }

    private static boolean wallToward(LevelPlot plot, double x, double y, double z, int dx, int dz) {
        for (int step = 0; step <= WALL_SCAN; step++) {
            if (solid(plot, BlockPos.containing(x + dx * step, y, z + dz * step))) return true;
        }
        return false;
    }

    /**
     * Solid <b>world</b> blocks within {@link #TERRAIN_COVER_SCAN} above the head — a tunnel ceiling, a bridge,
     * an overhang. Reads the host level, where carriage blocks never appear (they live in Sable's plot), so the
     * train's own roof can't count here. Unloaded chunks read as air.
     */
    public static boolean underTerrain(Entity e) {
        Level level = e.level();
        BlockPos.MutableBlockPos pos = BlockPos.containing(e.getX(), e.getEyeY(), e.getZ()).mutable();
        for (int dy = 1; dy <= TERRAIN_COVER_SCAN; dy++) {
            pos.move(0, 1, 0);
            BlockState s = level.getBlockState(pos);
            if (!s.isAir() && s.blocksMotion()) return true;
        }
        return false;
    }

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
