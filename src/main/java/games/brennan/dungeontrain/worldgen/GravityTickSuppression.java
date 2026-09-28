package games.brennan.dungeontrain.worldgen;

/**
 * Marks the current thread as stamping blocks that should <b>hang until disturbed</b>, the way vanilla
 * worldgen sand sits over a cave: a gravity block ({@link net.minecraft.world.level.block.Fallable}) that
 * is never ticked at generation, and falls only once a real neighbour update reaches it in play.
 *
 * <p><b>Why a tick filter.</b> {@code WorldGenRegion.setBlock} writes straight into the {@code ProtoChunk}
 * and never calls {@code onPlace}, so placement alone schedules nothing. The tick comes from
 * {@code StructureTemplate.placeInWorld}'s final shape pass: {@code Block.updateFromNeighbourShapes} →
 * {@code FallingBlock.updateShape} (likewise {@code BrushableBlock}, {@code PointedDripstoneBlock}) →
 * {@code scheduleTick}, which lands in the chunk's {@code ProtoChunkTicks}, is saved with it, and fires on
 * load over void. {@code WorldGenTickAccessGravityMixin} drops exactly those ticks while this guard is held;
 * every other tick (fluids, leaves, redstone) and the shape pass itself are untouched. The blocks stay real
 * gravity blocks — nothing is replaced (contrast {@link FallingBlockAnchor}).</p>
 *
 * <p><b>Safety.</b> A thread-local depth counter, mirroring
 * {@link games.brennan.dungeontrain.train.CarriageStampGuard}: each worldgen worker keeps its own count, and
 * callers go through {@link #run} so the counter is always released in a {@code finally}.</p>
 */
public final class GravityTickSuppression {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private GravityTickSuppression() {}

    /** True while this thread is inside a {@link #run} body. */
    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }

    /** Run {@code body} with gravity-block tick scheduling suppressed for its whole duration. */
    public static void run(Runnable body) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            body.run();
        } finally {
            depth[0]--;
        }
    }
}
