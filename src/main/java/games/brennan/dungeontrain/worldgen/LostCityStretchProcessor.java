package games.brennan.dungeontrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes a Lost City building taller, shorter, wider or narrower by repeating or removing a band of it.
 *
 * <p>The mod's towers are built of parts that repeat: a floor every few layers up, a window bay every few
 * blocks along. Per placement this finds the repeat along {@code axis} — the longest run of slices where
 * slice {@code i} matches slice {@code i + p} for some period {@code p} in {@code period_min..period_max},
 * with at least {@code min_similarity} of their full-block footprint in common ({@link #findBand}; the
 * towers' floors are two slab layers then five sparse column layers, and the sparse layers only agree at
 * about half) — and rolls a count between {@code min_floors} and {@code max_floors} (zero excluded). A
 * positive count copies the band that many more times and shifts everything beyond it outward; a negative
 * count removes that many repeats and shifts everything beyond inward. A building with no repeat, or too
 * few repeats to lose, is left as it is. Along {@code y} the pad is never touched and the roof stays under
 * the build limit; along {@code x} or {@code z} the pad is stretched with the building, so its plaza fits.</p>
 *
 * <p>The band is found on the template's own blocks (the {@code originals}), never on the processed list,
 * so every processor in the list and {@link LostCityFootprint} — which resizes the piece's bounding box at
 * structure-start time to the same {@link #plan} — agree on it. Runs in {@link #finalizeProcessing}.
 * Entities are not moved. Registered as {@code dungeontrain:lost_city_stretch}.</p>
 */
public final class LostCityStretchProcessor extends StructureProcessor {

    public static final MapCodec<LostCityStretchProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(-16, 16).fieldOf("min_floors").forGetter(p -> p.minFloors),
            Codec.intRange(-16, 16).fieldOf("max_floors").forGetter(p -> p.maxFloors),
            Codec.intRange(2, 32).optionalFieldOf("period_min", 4).forGetter(p -> p.periodMin),
            Codec.intRange(2, 32).optionalFieldOf("period_max", 16).forGetter(p -> p.periodMax),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("min_similarity", 0.45F).forGetter(p -> p.minSimilarity),
            Direction.Axis.CODEC.optionalFieldOf("axis", Direction.Axis.Y).forGetter(p -> p.axis)
    ).apply(i, LostCityStretchProcessor::new));

    public static final StructureProcessorType<LostCityStretchProcessor> TYPE = () -> CODEC;

    private static final int SALT = 0x57E7;
    /** The roof must stay below this world height. */
    private static final int MAX_TOP_Y = 316;
    /** Slices this close to either end are never part of the band. */
    private static final int MARGIN = 2;
    /** A slice joins a band only with at least this share of the largest slice's mass. */
    private static final double SUBSTANTIAL = 0.03;

    private final int minFloors, maxFloors, periodMin, periodMax;
    private final float minSimilarity;
    private final Direction.Axis axis;

    public LostCityStretchProcessor(int minFloors, int maxFloors, int periodMin, int periodMax, float minSimilarity,
                                    Direction.Axis axis) {
        this.minFloors = Math.min(minFloors, maxFloors);
        this.maxFloors = Math.max(minFloors, maxFloors);
        this.periodMin = Math.min(periodMin, periodMax);
        this.periodMax = Math.max(periodMin, periodMax);
        this.minSimilarity = minSimilarity;
        this.axis = axis;
    }

    public Direction.Axis axis() {
        return axis;
    }

    /** The repeating band: slices {@code [start, start + period × count)}. */
    public record Band(int start, int period, int count) {}

    /** What a placement does: the band and the (clamped, non-zero) number of repeats added or removed. */
    public record Plan(Band band, int delta) {
        /** Blocks the building grows (positive) or shrinks (negative) along the axis. */
        public int growth() {
            return delta * band.period();
        }
    }

    private static long seed(BlockPos origin) {
        return (origin.asLong() ^ SALT) * 0x9E3779B97F4A7C15L + SALT;
    }

    /** The repeat delta rolled for a placement: never zero (0 only when the range allows nothing else). */
    public int floors(BlockPos origin) {
        List<Integer> options = new ArrayList<>();
        for (int f = minFloors; f <= maxFloors; f++) if (f != 0) options.add(f);
        if (options.isEmpty()) return 0;
        return options.get((int) (LostCityStructures.hash01(seed(origin), axis.ordinal(), 1) * options.size()));
    }

    /**
     * Whether a block counts for a slice's footprint: a full cube that is not foliage, off the pad. Moss on a
     * wall still counts as wall, while vines, carpets, plants, panes and leaves — which differ bay to bay —
     * do not, and the plaza's identical rows never pass for bays.
     */
    static boolean isMass(BlockPos local, BlockState state) {
        if (local.getY() == 0 || state.isAir() || state.getBlock() instanceof LeavesBlock) return false;
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private int along(Vec3i pos) {
        return axis.choose(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The two coordinates across the axis, packed. */
    private long across(BlockPos pos) {
        return switch (axis) {
            case X -> ((long) pos.getY() << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
            case Y -> ((long) pos.getX() << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
            case Z -> ((long) pos.getX() << 32) ^ (pos.getY() & 0xFFFFFFFFL);
        };
    }

    /** Jaccard similarity of two slices' footprints. */
    static double similarity(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int common = 0;
        for (Long k : a) if (b.contains(k)) common++;
        int union = a.size() + b.size() - common;
        return union == 0 ? 1.0 : (double) common / union;
    }

    /**
     * The longest repeating band in {@code slices} (index = template-local coordinate along the axis, each
     * the set of packed across-coordinates the slice's mass occupies), or {@code null} if no period repeats
     * at least once at the required similarity. A run counts only through substantial slices — at least
     * {@link #SUBSTANTIAL} of the largest slice's mass — so a chimney or an aerial never passes for a floor.
     */
    @Nullable
    public Band findBand(List<Set<Long>> slices) {
        int length = slices.size();
        int largest = 0;
        for (Set<Long> slice : slices) largest = Math.max(largest, slice.size());
        if (largest == 0) return null;
        double floor = Math.max(1.0, SUBSTANTIAL * largest);
        Band best = null;
        int bestRun = 0;
        for (int p = periodMin; p <= periodMax; p++) {           // smaller periods first: a tie goes to one floor, not two
            int run = 0;
            for (int i = MARGIN; i + p < length - MARGIN; i++) {
                boolean match = slices.get(i).size() >= floor && similarity(slices.get(i), slices.get(i + p)) >= minSimilarity;
                run = match ? run + 1 : 0;
                if (run >= p && run > bestRun) {
                    bestRun = run;
                    best = new Band(i - run + 1, p, run / p + 1);   // periods in [i - run + 1, i + p]
                }
            }
        }
        return best;
    }

    /** The template-local slices of {@code originals} (the template's own, unrotated blocks). */
    List<Set<Long>> slices(List<StructureTemplate.StructureBlockInfo> originals) {
        int length = 0;
        for (StructureTemplate.StructureBlockInfo info : originals) length = Math.max(length, along(info.pos()) + 1);
        List<Set<Long>> slices = new ArrayList<>(length);
        for (int i = 0; i < length; i++) slices.add(new HashSet<>());
        for (StructureTemplate.StructureBlockInfo info : originals) {
            if (isMass(info.pos(), info.state())) slices.get(along(info.pos())).add(across(info.pos()));
        }
        return slices;
    }

    /**
     * What this processor does to the template placed at {@code origin}: {@code null} when nothing (no
     * repeat found, or nothing to add or remove). Shared by placement and {@link LostCityFootprint}.
     */
    @Nullable
    public Plan plan(BlockPos origin, List<StructureTemplate.StructureBlockInfo> originals) {
        List<Set<Long>> slices = slices(originals);
        if (slices.size() < 2 * MARGIN + 2 * periodMin) return null;
        Band band = findBand(slices);
        if (band == null) return null;
        int delta = floors(origin);
        if (delta < 0) delta = Math.max(delta, -(band.count() - 1));            // keep one repeat of the band
        if (delta > 0 && axis == Direction.Axis.Y) {
            delta = Math.min(delta, Math.max(0, (MAX_TOP_Y - origin.getY() - slices.size()) / band.period()));
        }
        return delta == 0 ? null : new Plan(band, delta);
    }

    /**
     * Applies {@code plan} to {@code processed} (world positions), given the template-local {@code originals}
     * and the placement's {@code settings} (rotation and mirror) and {@code origin}. Pure — used by the
     * processor and its tests.
     */
    public List<StructureTemplate.StructureBlockInfo> apply(Plan plan, BlockPos origin, StructurePlaceSettings settings,
                                                             List<StructureTemplate.StructureBlockInfo> originals,
                                                             List<StructureTemplate.StructureBlockInfo> processed) {
        Map<Long, Integer> localAlong = new HashMap<>(originals.size() * 2);
        for (StructureTemplate.StructureBlockInfo info : originals) {
            BlockPos world = StructureTemplate.calculateRelativePosition(settings, info.pos()).offset(origin);
            localAlong.put(world.asLong(), along(info.pos()));
        }
        int p = plan.band().period();
        int delta = plan.delta();
        int bandStart = plan.band().start();
        int bandEnd = bandStart + p;                                              // one repeat: [start, end)
        Vec3i step = StructureTemplate.transform(BlockPos.ZERO.relative(axis, p), settings.getMirror(),
                settings.getRotation(), BlockPos.ZERO);                          // one period, in world terms
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed.size() + (delta > 0 ? processed.size() / 4 : 0));
        for (StructureTemplate.StructureBlockInfo info : processed) {
            Integer at = localAlong.get(info.pos().asLong());
            if (at == null) {   // a block another processor added (footing, rubble): keep where it is
                out.add(info);
                continue;
            }
            if (delta > 0) {
                if (at < bandEnd) {
                    out.add(info);
                    if (at >= bandStart) {
                        for (int k = 1; k <= delta; k++) out.add(moved(info, step, k));
                    }
                } else {
                    out.add(moved(info, step, delta));
                }
            } else {
                int cut = bandStart - delta * p;                                  // [start, cut) is removed
                if (at < bandStart) out.add(info);
                else if (at >= cut) out.add(moved(info, step, delta));
            }
        }
        return out;
    }

    private static StructureTemplate.StructureBlockInfo moved(StructureTemplate.StructureBlockInfo info, Vec3i step, int k) {
        BlockPos pos = info.pos().offset(step.getX() * k, step.getY() * k, step.getZ() * k);
        return new StructureTemplate.StructureBlockInfo(pos, info.state(), info.nbt() == null ? null : info.nbt().copy());
    }

    /** A settings object for an unrotated, unmirrored placement — the tests' and {@link LostCityFootprint}'s. */
    public static StructurePlaceSettings plain() {
        return new StructurePlaceSettings().setRotation(Rotation.NONE).setMirror(Mirror.NONE);
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset,
                                                                         BlockPos pivot,
                                                                         List<StructureTemplate.StructureBlockInfo> originals,
                                                                         List<StructureTemplate.StructureBlockInfo> processed,
                                                                         StructurePlaceSettings settings) {
        Plan plan = plan(offset, originals);
        return plan == null ? processed : apply(plan, offset, settings, originals, processed);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
