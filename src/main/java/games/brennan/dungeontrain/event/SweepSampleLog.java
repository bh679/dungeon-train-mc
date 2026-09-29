package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code [sweep.sample]} — what the footprint sweep's wasted lookups actually are.
 *
 * <p>A wasted lookup is a cell {@code TrainTickEvents.sweepFootprint} paid a
 * {@code CarriageDeck.blockAt} for and found the carriage had nothing there: a solid world block
 * inside a carriage's {@code worldAABB} but in its open interior. {@code [sweep.perf]} only counts
 * them; this names them, per carriage group, so a climbing {@code lookups/tick} can be attributed
 * from a log rather than by walking the world.</p>
 *
 * <p>Built for the 2026-09-29 ride where lookups climbed 0 → ~1,190/tick: the cause was a player's
 * build widening one group's AABB 8 blocks past the carved corridor, which drew corridor-side terrain
 * into the box. The group's AABB size is printed next to the template {@link CarriageDims} (both as
 * x×y×z) precisely so that case reads at a glance — an AABB wider than the template is a widened
 * carriage.</p>
 *
 * <p>Emitted on the {@code [sweep.perf]} window (2 s, DEBUG), silent when nothing was sampled.
 * Server-thread only, so plain static state is safe; one immutable {@link Sample} is kept per group
 * per window (the first seen), and only the per-group count changes after that.</p>
 */
final class SweepSampleLog {

    /** Groups listed per line — the worst offenders are what a reader needs. */
    static final int TOP_N = 3;

    /** First wasted cell seen for a carriage group this window, plus that group's AABB at the time. */
    record Sample(int pIdx, int groupSize, String blockId, BlockPos pos, BlockPos rel,
                  double sizeX, double sizeY, double sizeZ) {}

    /** A group's sample and how many wasted cells it produced this window. */
    record Tally(Sample sample, long cells) {}

    private static final Map<Integer, Sample> FIRST = new HashMap<>();
    private static final Map<Integer, Long> COUNTS = new HashMap<>();

    private SweepSampleLog() {}

    /** Record one wasted lookup at {@code pos} (a world block, {@code state}) inside group {@code pIdx}. */
    static void record(int pIdx, int groupSize, BlockPos pos, BlockState state, AABBdc box) {
        COUNTS.merge(pIdx, 1L, Long::sum);
        if (FIRST.containsKey(pIdx)) return;
        BlockPos rel = new BlockPos(pos.getX() - Mth.floor(box.minX()),
            pos.getY() - Mth.floor(box.minY()), pos.getZ() - Mth.floor(box.minZ()));
        FIRST.put(pIdx, new Sample(pIdx, groupSize,
            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), pos.immutable(), rel,
            box.maxX() - box.minX(), box.maxY() - box.minY(), box.maxZ() - box.minZ()));
    }

    /** Emit the window's line (if anything was sampled) and reset. */
    static void emit(Logger logger, CarriageDims dims) {
        if (!COUNTS.isEmpty()) {
            List<Tally> tallies = new ArrayList<>(COUNTS.size());
            COUNTS.forEach((pIdx, n) -> tallies.add(new Tally(FIRST.get(pIdx), n)));
            long total = tallies.stream().mapToLong(Tally::cells).sum();
            String top = top(tallies, TOP_N).stream()
                .map(t -> format(t, dims))
                .collect(Collectors.joining(", "));
            logger.debug("[sweep.sample] groups={} cells/window={} top=[{}]", tallies.size(), total, top);
        }
        reset();
    }

    /** Discard the window — called with every {@code [sweep.perf]} reset so the two stay aligned. */
    static void reset() {
        FIRST.clear();
        COUNTS.clear();
    }

    /** The {@code n} tallies with the most cells, most first; ties broken by pIdx. Pure — unit-tested. */
    static List<Tally> top(Collection<Tally> tallies, int n) {
        return tallies.stream()
            .sorted(Comparator.comparingLong(Tally::cells).reversed()
                .thenComparingInt(t -> t.sample().pIdx()))
            .limit(Math.max(0, n))
            .toList();
    }

    private static String format(Tally t, CarriageDims dims) {
        Sample s = t.sample();
        return String.format("pIdx=%d group=%d cells=%d aabb=%.1fx%.1fx%.1f dims=%dx%dx%d first=%s@%s rel=(%d,%d,%d)",
            s.pIdx(), s.groupSize(), t.cells(), s.sizeX(), s.sizeY(), s.sizeZ(),
            dims.length() * s.groupSize(), dims.height(), dims.width(),
            s.blockId(), s.pos().toShortString(), s.rel().getX(), s.rel().getY(), s.rel().getZ());
    }
}
