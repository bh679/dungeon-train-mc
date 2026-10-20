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
 *       eye height. World blocks and the train's own carriages both count, so standing beside the train
 *       with it upwind is sheltered.</li>
 * </ol>
 * The ray-march runs against a {@link Solidity} probe so it can be tested without a level.
 */
public final class GroundExposure {

    static final double FOOT_PROBE = 0.3;

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
        return classify(probe, e.getX(), e.getY(), e.getZ(), Math.max(0.2, e.getEyeHeight()), dirX, dirZ);
    }

    /**
     * The lee test alone: {@code IN_LEE} when {@code probe} is solid within {@link TrainExposure#LEE_SCAN}
     * blocks against the wind (unit {@code dirX,dirZ} is the direction it blows toward) at foot or eye
     * height, else {@code EXPOSED}.
     */
    public static TrainExposure.Result classify(Solidity probe, double x, double y, double z, double eye,
                                                double dirX, double dirZ) {
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1e-6) return TrainExposure.Result.EXPOSED;
        double ux = -dirX / len;
        double uz = -dirZ / len;
        for (int step = 1; step <= TrainExposure.LEE_SCAN; step++) {
            double px = x + ux * step;
            double pz = z + uz * step;
            if (probe.solid(px, y + FOOT_PROBE, pz)) return TrainExposure.Result.IN_LEE;
            if (probe.solid(px, y + eye, pz)) return TrainExposure.Result.IN_LEE;
        }
        return TrainExposure.Result.EXPOSED;
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
