package games.brennan.dungeontrain.wind;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.ship.CarriageDeck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Is an entity out in the wind <b>on the ground</b> (not over a carriage)? The counterpart of
 * {@link TrainExposure} for the world's own blocks, so stepping off the train doesn't switch the wind off:
 * <ol>
 *   <li><b>Not in a tunnel</b>: no solid terrain within {@link TrainExposure#TERRAIN_COVER_SCAN} blocks
 *       overhead ({@link TrainExposure#underTerrain}).</li>
 *   <li><b>Not in a lee</b>: nothing solid within {@link TrainExposure#LEE_SCAN} blocks upwind at foot or
 *       eye height, anywhere in the wind's path to the body. One block is enough. World blocks and the train's own carriages both count, so standing beside the train
 *       with it upwind is sheltered.</li>
 * </ol>
 * The ray-march runs against a {@link Solidity} probe so it can be tested without a level.
 */
public final class GroundExposure {

    static final double FOOT_PROBE = 0.3;
    /** How far upwind a ray runs: half a block past {@link TrainExposure#LEE_SCAN} from the body's centre. */
    private static final double LEE_REACH = TrainExposure.LEE_SCAN + 0.5;
    /**
     * The side rays sit this far inside the body's edge, so a wall you stand flush beside (running with the
     * wind) isn't read as a block in front of you.
     */
    private static final double EDGE_INSET = 0.05;

    private GroundExposure() {}

    /** Whether the block containing world point {@code (x, y, z)} blocks the wind. */
    @FunctionalInterface
    public interface Solidity {
        boolean solid(double x, double y, double z);
    }

    /** {@link TrainExposure.Result} for an entity standing on the ground, with world blocks and carriages as shelter. */
    public static TrainExposure.Result classify(Entity e, double dirX, double dirZ) {
        if (TrainExposure.underTerrain(e)) return TrainExposure.Result.IN_TUNNEL;
        Level level = e.level();
        Solidity probe = (x, y, z) -> solidWorld(level, x, y, z) || solidCarriage(level, x, y, z);
        return classify(probe, e.getX(), e.getY(), e.getZ(), Math.max(0.2, e.getEyeHeight()),
                e.getBbWidth() / 2.0, dirX, dirZ);
    }

    /**
     * The lee test alone: {@code IN_LEE} when {@code probe} has a solid block within
     * {@link TrainExposure#LEE_SCAN} blocks against the wind (unit {@code dirX,dirZ} is the direction it blows
     * toward), at foot or eye height, in the wind's path to any part of the body, else {@code EXPOSED}.
     *
     * <p>One block is enough: three rays run upwind across the body (its centre and both sides, {@code halfWidth}
     * either way of it) at each height, and each ray checks every block it passes through, so a lone block
     * is found whatever the wind's angle. The block you stand in is not counted.</p>
     */
    public static TrainExposure.Result classify(Solidity probe, double x, double y, double z, double eye,
                                                double halfWidth, double dirX, double dirZ) {
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1e-6) return TrainExposure.Result.EXPOSED;
        double ux = -dirX / len;
        double uz = -dirZ / len;
        double side = Math.max(0.0, halfWidth - EDGE_INSET);
        double[] heights = {y + FOOT_PROBE, y + eye};
        for (double offset : new double[] {0.0, -side, side}) {
            double ox = x - uz * offset;
            double oz = z + ux * offset;
            for (double h : heights) {
                if (rayHits(probe, ox, h, oz, ux, uz)) return TrainExposure.Result.IN_LEE;
            }
        }
        return TrainExposure.Result.EXPOSED;
    }

    /**
     * Walks the blocks a horizontal ray from {@code (ox, oz)} along unit {@code (ux, uz)} passes through, short of
     * {@link #LEE_REACH}, skipping the one it starts in: true at the first solid one.
     */
    private static boolean rayHits(Solidity probe, double ox, double y, double oz, double ux, double uz) {
        int cx = (int) Math.floor(ox);
        int cz = (int) Math.floor(oz);
        int stepX = ux > 0 ? 1 : -1;
        int stepZ = uz > 0 ? 1 : -1;
        double deltaX = Math.abs(ux) < 1e-9 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(ux);
        double deltaZ = Math.abs(uz) < 1e-9 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(uz);
        double nextX = Math.abs(ux) < 1e-9 ? Double.POSITIVE_INFINITY
                : (stepX > 0 ? cx + 1 - ox : ox - cx) * deltaX;
        double nextZ = Math.abs(uz) < 1e-9 ? Double.POSITIVE_INFINITY
                : (stepZ > 0 ? cz + 1 - oz : oz - cz) * deltaZ;
        while (true) {
            double t;
            if (nextX < nextZ) {
                t = nextX;
                cx += stepX;
                nextX += deltaX;
            } else {
                t = nextZ;
                cz += stepZ;
                nextZ += deltaZ;
            }
            if (t >= LEE_REACH) return false;
            if (probe.solid(cx + 0.5, y, cz + 0.5)) return true;
        }
    }

    private static boolean solidWorld(Level level, double x, double y, double z) {
        BlockState s = level.getBlockState(BlockPos.containing(x, y, z));
        return !s.isAir() && s.blocksMotion();
    }

    /** A carriage block at this world point: carriage blocks live in Sable's plot, so read them there. */
    private static boolean solidCarriage(Level level, double x, double y, double z) {
        BoundingBox3d box = new BoundingBox3d(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, box)) {
            if (sub.isRemoved()) continue;
            LevelPlot plot = sub.getPlot();
            if (plot == null) continue;
            Vector3d local = sub.logicalPose().transformPositionInverse(new Vector3d(x, y, z));
            BlockState s = CarriageDeck.blockInPlot(plot, BlockPos.containing(local.x, local.y, local.z));
            if (!s.isAir() && s.blocksMotion()) return true;
        }
        return false;
    }
}
