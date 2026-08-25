package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderStructureStamp;
import games.brennan.dungeontrain.builder.structure.BuilderStructureCells;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Keeps <em>solid</em> structures following the build, while the refresh control asks them to.
 *
 * <p>When the structures are ghosts, following your edits costs nothing to arrange: the client sweep
 * re-resolves them every five ticks and draws whatever it gets. Solid is not that. Solid scenery is
 * real blocks in the world, and only the server can change them, so something has to notice that the
 * open build moved and stand the surrounds up again. This is that something.</p>
 *
 * <h2>Polled, not evented</h2>
 *
 * <p>{@code BuilderProtectionEvents} already sees {@code BlockEvent} for placements and breaks, and a
 * dirty flag off those would be cheaper than a sweep. It would also be wrong: a fill, a
 * {@code /setblock} or a WorldEdit brush changes the build without any of them firing, and scenery
 * that silently stops following for exactly the operations that change the most is worse than
 * scenery that costs a few hundred block reads a second. The volume walked here is the open build —
 * a carriage is around 450 positions, a track plot fewer.</p>
 *
 * <h2>Throttled rather than debounced</h2>
 *
 * <p>A re-stamp is only expensive in what it <em>writes</em>: {@link BuilderStructureStamp} skips
 * every cell already holding the right block, so a pass after one block moved is a walk of reads and
 * a handful of writes. So this runs at most once a second and does not wait for the build to settle
 * — waiting would make the common case, laying blocks one at a time, the case that never updates.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BuilderStructureRestampTicker {

    /**
     * How often the open build is checked, and so the fastest the scenery can follow it.
     *
     * <p>A second: long enough that a builder laying blocks steadily is not driving a world write on
     * every one of them, short enough that the surrounds are not visibly lagging the thing they are
     * copies of.</p>
     */
    private static final int INTERVAL_TICKS = 20;

    private static int tickCounter = 0;

    /**
     * What the open build looked like at the last check.
     *
     * <p>A hash rather than the blocks: this only has to answer "did anything move?", and keeping a
     * copy of the build to compare against would be the captured-state mistake the structure system
     * was built to avoid. A collision costs one skipped update out of a sequence of them.</p>
     */
    private static long lastFingerprint = 0L;
    private static boolean primed = false;

    private BuilderStructureRestampTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (++tickCounter < INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        BoundingBox build = BuilderStructureStamp.trackedOpenBuild(level);
        if (build == null) {
            // Nothing is following the build — solid scenery turned off, the setting changed, or what
            // is open is not a stored template. Forget the fingerprint so the next build that does
            // qualify is stamped on its first change rather than compared against another one's.
            primed = false;
            return;
        }

        long fingerprint = fingerprint(level, build);
        if (primed && fingerprint == lastFingerprint) {
            return;
        }
        boolean changed = primed;
        lastFingerprint = fingerprint;
        primed = true;
        if (!changed) {
            return;   // first sight of this build; it is already standing as it should be
        }

        // The cells cached for this template were resolved from the build as it was.
        BuilderStructureCells.clear();
        BuilderStructureStamp.apply(level);
    }

    /** Order-independent digest of every non-air block in the volume, and where it is. */
    private static long fingerprint(ServerLevel level, BoundingBox box) {
        long hash = 0L;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockState state = level.getBlockState(probe.set(x, y, z));
                    if (state.isAir()) {
                        continue;
                    }
                    // Mixed rather than summed: a block moving from one cell to another would leave
                    // a plain sum of the two unchanged.
                    hash += mix(probe.asLong() * 31L + state.hashCode());
                }
            }
        }
        return hash;
    }

    private static long mix(long value) {
        long v = value;
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        return v;
    }
}
