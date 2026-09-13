package games.brennan.dungeontrain.ship.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clips collision boxes that protrude above a block's unit cube (fence and wall posts are 1.5
 * tall) when the block above sits flush on top and fully covers their footprint.
 *
 * <p>Sable resolves entity-vs-sub-level collision with SAT minimum-translation pushes rather than
 * vanilla's swept per-axis clip. Vanilla tolerates an entity box that already overlaps a block box
 * (it never clips), so standing on a trapdoor laid over a fence works on static ground even though
 * the post pokes 0.3125 up into the feet. On a train the same overlap yields a sideways push, which
 * counts as a horizontal collision, which {@code tryStepUp} answers by lifting the entity onto the
 * post top — and gravity drops it straight back. Eight substeps a tick of that is the jitter.</p>
 *
 * <p>Cutting the post at {@code y=1.0} whenever a covering block rests on it makes the geometry
 * Sable sees match the geometry vanilla effectively enforces. Posts with nothing on top, or with a
 * block above whose bottom face does not cover the post (a chain, say), keep their full shape.</p>
 *
 * <p>Results are cached by shape identity: vanilla hands out one {@link VoxelShape} instance per
 * block state (unless {@code hasDynamicShape()}), so the {@link Shapes} joins run once
 * per (lower state, upper state) pair instead of per collision substep. The cache is shared by the
 * client and integrated-server threads, hence the concurrent map, and bounded so an unforeseen
 * churn of distinct instances cannot grow it without limit.</p>
 */
public final class ProtrudingShapeClip {

    /** Anything at or below the unit cube's top needs no clipping. */
    private static final double CUBE_TOP = 1.0;
    /** How close to the shared face the upper block's shape must start to count as resting on it. */
    private static final double FLUSH_EPSILON = 1.0E-4;
    /** Thickness of the footprint probe slab used to test coverage by the upper shape. */
    private static final double FOOTPRINT_PROBE_HEIGHT = 1.0E-3;
    private static final int CACHE_CAP = 1024;

    private static final Map<ShapePair, VoxelShape> CACHE = new ConcurrentHashMap<>();

    private ProtrudingShapeClip() {
    }

    /**
     * Returns {@code shape} with its above-the-cube boxes clipped when the block above {@code pos}
     * rests flush on top of them; otherwise the very same instance.
     */
    public static VoxelShape clip(final BlockState state, final BlockGetter getter, final BlockPos pos, final VoxelShape shape) {
        if (shape == null || shape.isEmpty() || shape.max(Direction.Axis.Y) <= CUBE_TOP) {
            return shape;
        }
        final BlockPos abovePos = pos.above();
        final BlockState above = getter.getBlockState(abovePos);
        if (above.isAir()) {
            return shape;
        }
        final VoxelShape aboveShape = above.getCollisionShape(getter, abovePos);
        if (aboveShape.isEmpty() || aboveShape.min(Direction.Axis.Y) > FLUSH_EPSILON) {
            return shape;
        }
        if (state.getBlock().hasDynamicShape() || above.getBlock().hasDynamicShape()) {
            return clipAgainst(shape, aboveShape);
        }
        final ShapePair key = new ShapePair(shape, aboveShape);
        final VoxelShape cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        final VoxelShape clipped = clipAgainst(shape, aboveShape);
        if (CACHE.size() < CACHE_CAP) {
            CACHE.put(key, clipped);
        }
        return clipped;
    }

    /**
     * Pure form of {@link #clip}: every box of {@code shape} rising above {@code y=1.0} whose
     * footprint is fully covered by {@code aboveShape} (in the upper block's local space) is cut
     * down to the unit cube. Returns the same instance when nothing needed clipping.
     */
    public static VoxelShape clipAgainst(final VoxelShape shape, final VoxelShape aboveShape) {
        final List<AABB> boxes = shape.toAabbs();
        final List<VoxelShape> kept = new ArrayList<>(boxes.size());
        boolean changed = false;
        for (final AABB box : boxes) {
            if (box.maxY <= CUBE_TOP || !footprintCovered(box, aboveShape)) {
                kept.add(Shapes.create(box));
                continue;
            }
            changed = true;
            if (box.minY < CUBE_TOP) {
                kept.add(Shapes.create(new AABB(box.minX, box.minY, box.minZ, box.maxX, CUBE_TOP, box.maxZ)));
            }
        }
        if (!changed) {
            return shape;
        }
        if (kept.isEmpty()) {
            return Shapes.empty();
        }
        return kept.stream().reduce(Shapes.empty(), Shapes::or).optimize();
    }

    /** True when {@code aboveShape} leaves no part of {@code box}'s x/z footprint uncovered at its floor. */
    private static boolean footprintCovered(final AABB box, final VoxelShape aboveShape) {
        final VoxelShape footprint = Shapes.box(box.minX, 0.0, box.minZ, box.maxX, FOOTPRINT_PROBE_HEIGHT, box.maxZ);
        return !Shapes.joinIsNotEmpty(footprint, aboveShape, BooleanOp.ONLY_FIRST);
    }

    /** Identity-keyed pair; shape instances are per-state singletons for non-dynamic blocks. */
    private record ShapePair(VoxelShape lower, VoxelShape upper) {
        @Override
        public boolean equals(final Object o) {
            return o instanceof ShapePair other && other.lower == lower && other.upper == upper;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(lower) + System.identityHashCode(upper);
        }
    }
}
