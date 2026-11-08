package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.mixin.CombiningPredicateAccessor;
import games.brennan.dungeontrain.mixin.NotPredicateAccessor;
import games.brennan.dungeontrain.mixin.StateTestingPredicateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicateType;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Skips the disk feature's repeated block tests. WWOO paints its cliffs and shores with 128 overlapping
 * disks per chunk ({@code wythers:terrain/local/stone_cliffs_sheer} and friends), so most positions get the
 * same target test 10-25 times on blocks that haven't changed; that was ~half the worldgen CPU in the WWOO
 * stretch.
 *
 * <p>While one placed disk feature runs ({@code PlacedFeatureDiskMemoMixin}), every position whose target
 * test failed is remembered and answered "no" again without reading blocks ({@code DiskFeatureTestMemoMixin}).
 * A test only reads blocks at the predicate's offsets, so it can only change when one of those blocks is
 * written: every write in the region forgets the positions that read it ({@code WorldGenRegionDiskMemoMixin}).
 * Only passes are acted on and they're never remembered, so the random stream and the placed blocks are
 * exactly vanilla's. Predicates other than vanilla's block-reading ones get no memo.</p>
 *
 * <p>{@link Mode#VERIFY} re-runs the real test on every remembered answer and counts disagreements
 * ({@code /dungeontrain debug disk-memo verify}). It uses the real answer, so the world stays vanilla.</p>
 */
public final class DiskTestMemo {

    public enum Mode { ON, OFF, VERIFY }

    public static volatile Mode mode = Mode.ON;

    private static final LongAdder TESTS = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();
    private static final LongAdder UNSUPPORTED = new LongAdder();

    private static final ThreadLocal<DiskTestMemo> PER_THREAD = ThreadLocal.withInitial(DiskTestMemo::new);
    /** Offsets each predicate reads, or {@link #UNSUPPORTED_OFFSETS} when it may read anything else. */
    private static final Map<BlockPredicate, Vec3i[]> OFFSETS = new ConcurrentHashMap<>();
    private static final Vec3i[] UNSUPPORTED_OFFSETS = new Vec3i[0];

    private final DiskMemoBox failed = new DiskMemoBox();
    private WorldGenLevel level;
    private BlockPredicate target;
    private Vec3i[] offsets;
    private long tests;
    private long hits;

    private DiskTestMemo() {}

    /**
     * A placed feature starts at {@code origin} (its chunk's corner). A disk ({@code target} non-null) gets a
     * fresh memo; anything else ends the previous one, so no memo outlives the feature that made it.
     */
    public static void begin(WorldGenLevel level, BlockPredicate target, BlockPos origin) {
        DiskTestMemo m = PER_THREAD.get();
        m.flush();
        if (target == null || mode == Mode.OFF) return;
        Vec3i[] offsets = OFFSETS.computeIfAbsent(target, DiskTestMemo::offsetsOf);
        if (offsets == UNSUPPORTED_OFFSETS) {
            UNSUPPORTED.increment();
            return;
        }
        int chunkX = origin.getX() & ~15;
        int chunkZ = origin.getZ() & ~15;
        m.failed.reset(chunkX, chunkZ, level.getMinBuildHeight(), level.getHeight());
        m.level = level;
        m.target = target;
        m.offsets = offsets;
    }

    /** The placed feature finished. */
    public static void end() {
        PER_THREAD.get().flush();
    }

    /** {@code target.test(level, pos)}, answered from the memo when it already failed there and nothing it reads changed. */
    public static boolean test(BlockPredicate target, WorldGenLevel level, BlockPos pos) {
        DiskTestMemo m = PER_THREAD.get();
        if (m.target != target || m.level != level) return target.test(level, pos);
        m.tests++;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (m.failed.contains(x, y, z)) {
            m.hits++;
            if (mode == Mode.VERIFY && target.test(level, pos)) {
                MISMATCHES.increment();
                return true;
            }
            return false;
        }
        boolean pass = target.test(level, pos);
        if (!pass) m.failed.add(x, y, z);
        return pass;
    }

    /** The block at {@code pos} is about to be written: forget every remembered test that reads it. */
    public static void onWrite(WorldGenLevel level, BlockPos pos) {
        DiskTestMemo m = PER_THREAD.get();
        if (m.level != level) return;
        for (Vec3i o : m.offsets) {
            m.failed.remove(pos.getX() - o.getX(), pos.getY() - o.getY(), pos.getZ() - o.getZ());
        }
    }

    /** One-line counters since the last call, for {@code /dungeontrain debug disk-memo status}. */
    public static String drainCounters() {
        long tests = TESTS.sumThenReset();
        long hits = HITS.sumThenReset();
        return "tests=" + tests + " remembered=" + hits
                + (tests > 0 ? " (" + (100 * hits / tests) + "%)" : "")
                + " mismatches=" + MISMATCHES.sumThenReset() + " unsupportedDisks=" + UNSUPPORTED.sumThenReset();
    }

    private void flush() {
        if (target == null) return;
        TESTS.add(tests);
        HITS.add(hits);
        tests = 0;
        hits = 0;
        level = null;
        target = null;
        offsets = null;
    }

    private static Vec3i[] offsetsOf(BlockPredicate predicate) {
        List<Vec3i> out = new ArrayList<>();
        return collect(predicate, out) ? out.toArray(Vec3i[]::new) : UNSUPPORTED_OFFSETS;
    }

    /**
     * Adds the offsets {@code p} reads to {@code out}; false when it may read more than block states at fixed
     * offsets. {@link StateTestingPredicate}'s {@code test} is final: it reads one block at its offset.
     */
    static boolean collect(BlockPredicate p, List<Vec3i> out) {
        if (p instanceof StateTestingPredicate s) {
            Vec3i o = ((StateTestingPredicateAccessor) s).dungeontrain$offset();
            if (!out.contains(o)) out.add(o);
            return true;
        }
        BlockPredicateType<?> type = p.type();
        if ((type == BlockPredicateType.ALL_OF || type == BlockPredicateType.ANY_OF) && p instanceof CombiningPredicateAccessor c) {
            for (BlockPredicate child : c.dungeontrain$predicates()) {
                if (!collect(child, out)) return false;
            }
            return true;
        }
        if (type == BlockPredicateType.NOT && p instanceof NotPredicateAccessor n) return collect(n.dungeontrain$predicate(), out);
        return type == BlockPredicateType.TRUE;
    }
}
