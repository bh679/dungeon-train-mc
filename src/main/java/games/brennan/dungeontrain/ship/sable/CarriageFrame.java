package games.brennan.dungeontrain.ship.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.UnaryOperator;

/**
 * The arithmetic for asking a carriage, rather than the world, what is under a mob riding it.
 *
 * <p>A mob on a moving carriage lives in world space, but the blocks it stands on live in the
 * carriage's plot (x ≈ 20,480,000). Vanilla AI that probes "the block next to me" reads the world
 * there and finds the open air above the track. These helpers map a world point into the plot and
 * back. The pose transforms come in as functions, so the tests need no Sable runtime; the mixins
 * pass {@code subLevel.logicalPose()::transformPositionInverse} and {@code ::transformPosition}.</p>
 */
public final class CarriageFrame {

    /**
     * Lift applied to a feet height before flooring it to a block. A mob standing on a deck has its
     * feet exactly on the block top, and the plot transform can land a hair under it (125.9999),
     * which would name the deck block itself instead of the air above it. Sable's own
     * {@code getInBlockState} lifts by 0.001; this is a little more for the round trip.
     */
    static final double FEET_EPSILON = 0.01;

    private CarriageFrame() {}

    /** The plot block that the world point ({@code x}, {@code feetY}, {@code z}) lies in. */
    public static BlockPos localBlockAt(double x, double feetY, double z, UnaryOperator<Vec3> worldToLocal) {
        return BlockPos.containing(worldToLocal.apply(new Vec3(x, feetY + FEET_EPSILON, z)));
    }

    /** The world position of the plot point ({@code localX}, {@code localY}, {@code localZ}). */
    public static Vec3 worldPointOf(double localX, double localY, double localZ, UnaryOperator<Vec3> localToWorld) {
        return localToWorld.apply(new Vec3(localX, localY, localZ));
    }
}
