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
 * Reshapes a Lost City building by repeating or removing a band of it: taller, shorter, wider, narrower,
 * with taller or lower ceilings, a podium or a setback.
 *
 * <p>The mod's towers are built of parts that repeat: a floor every few layers up, a window bay every few
 * blocks along. Per placement this finds the repeat along {@code axis} — the longest run of slices where
 * slice {@code i} matches slice {@code i + p} for some period {@code p} in {@code period_min..period_max},
 * with at least {@code min_similarity} of their full-block footprint in common ({@link #findBand}; the
 * towers' floors are two slab layers then five sparse column layers, and the sparse layers only agree at
 * about half) — and then does one of two things:</p>
 * <ul>
 *   <li><b>Repeats</b> ({@code floor_layers} = 0): rolls a count between {@code min_floors} and
 *   {@code max_floors} (zero excluded); positive copies the band that many more times and shifts everything
 *   beyond it outward, negative removes that many repeats and shifts everything beyond inward. With
 *   {@code min_layer}/{@code max_layer} only blocks in that template-height range move, so a positive
 *   stretch of the lower layers makes a <b>podium</b> and a negative one of the upper layers a <b>setback</b>.</li>
 *   <li><b>Ceilings</b> ({@code floor_layers} ≠ 0, {@code y} only): every floor gains that many copies of
 *   its sparsest layer, or loses that many of its sparsest layers, so the same tower stands with higher or
 *   lower rooms.</li>
 * </ul>
 * <p>A building with no repeat, or too little to lose, is left as it is. Along {@code y} the pad is never
 * touched and the roof stays under the build limit; along {@code x} or {@code z} the pad is stretched with
 * the building, so its plaza fits.</p>
 *
 * <p>The band is found on the template's own blocks (the {@code originals}), never on the processed list,
 * so every processor in the list and {@link LostCityFootprint} — which resizes the piece's bounding box at
 * structure-start time to the same {@link #plan} — agree on it. Runs in {@link #finalizeProcessing}.
 * Entities are not moved. Registered as {@code dungeontrain:lost_city_stretch}.</p>
 */
public final class LostCityStretchProcessor extends StructureProcessor {

    private static final int NO_LIMIT = 4096;

    public static final MapCodec<LostCityStretchProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(-16, 16).fieldOf("min_floors").forGetter(p -> p.minFloors),
            Codec.intRange(-16, 16).fieldOf("max_floors").forGetter(p -> p.maxFloors),
            Codec.intRange(2, 32).optionalFieldOf("period_min", 4).forGetter(p -> p.periodMin),
            Codec.intRange(2, 32).optionalFieldOf("period_max", 16).forGetter(p -> p.periodMax),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("min_similarity", 0.45F).forGetter(p -> p.minSimilarity),
            Direction.Axis.CODEC.optionalFieldOf("axis", Direction.Axis.Y).forGetter(p -> p.axis),
            Codec.intRange(-8, 8).optionalFieldOf("floor_layers", 0).forGetter(p -> p.floorLayers),
            Codec.intRange(0, NO_LIMIT).optionalFieldOf("min_layer", 0).forGetter(p -> p.minLayer),
            Codec.intRange(0, NO_LIMIT).optionalFieldOf("max_layer", NO_LIMIT).forGetter(p -> p.maxLayer)
    ).apply(i, LostCityStretchProcessor::new));

    public static final StructureProcessorType<LostCityStretchProcessor> TYPE = () -> CODEC;

    private static final int SALT = 0x57E7;
    /** The roof must stay below this world height. */
    private static final int MAX_TOP_Y = 316;
    /** Slices this close to either end are never part of the band. */
    private static final int MARGIN = 2;
    /** A slice joins a band only with at least this share of the largest slice's mass. */
    private static final double SUBSTANTIAL = 0.03;
    /** A floor keeps at least this many layers when its ceiling is lowered. */
    private static final int MIN_FLOOR = 3;

    private final int minFloors, maxFloors, periodMin, periodMax;
    private final float minSimilarity;
    private final Direction.Axis axis;
    private final int floorLayers, minLayer, maxLayer;

    public LostCityStretchProcessor(int minFloors, int maxFloors, int periodMin, int periodMax, float minSimilarity,
                                    Direction.Axis axis) {
        this(minFloors, maxFloors, periodMin, periodMax, minSimilarity, axis, 0, 0, NO_LIMIT);
    }

    public LostCityStretchProcessor(int minFloors, int maxFloors, int periodMin, int periodMax, float minSimilarity,
                                    Direction.Axis axis, int floorLayers, int minLayer, int maxLayer) {
        this.minFloors = Math.min(minFloors, maxFloors);
        this.maxFloors = Math.max(minFloors, maxFloors);
        this.periodMin = Math.min(periodMin, periodMax);
        this.periodMax = Math.max(periodMin, periodMax);
        this.minSimilarity = minSimilarity;
        this.axis = axis;
        this.floorLayers = floorLayers;
        this.minLayer = Math.min(minLayer, maxLayer);
        this.maxLayer = Math.max(minLayer, maxLayer);
    }

    public Direction.Axis axis() {
        return axis;
    }

    private boolean limited() {
        return minLayer > 0 || maxLayer < NO_LIMIT;
    }

    /** The repeating band: slices {@code [start, start + period × count)}. */
    public record Band(int start, int period, int count) {}

    /**
     * What a placement does: the band, the (clamped, non-zero) number of repeats added or removed, or the
     * layers each floor gains or loses.
     */
    public record Plan(Band band, int delta, int floorLayers, boolean limited) {
        /** Blocks the building's extent grows (positive) or shrinks (negative) along the axis. */
        public int growth() {
            if (floorLayers != 0) return floorLayers * band.count();
            if (delta < 0 && limited) return 0;                     // a setback keeps the full base
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
     * Smaller periods are tried first, so a tie goes to one floor rather than two.
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
        for (int p = periodMin; p <= periodMax; p++) {
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
        int room = axis == Direction.Axis.Y ? Math.max(0, MAX_TOP_Y - origin.getY() - slices.size()) : Integer.MAX_VALUE;
        if (floorLayers != 0) {
            if (axis != Direction.Axis.Y) return null;
            int n = Math.max(floorLayers, MIN_FLOOR - band.period());              // keep MIN_FLOOR layers a floor
            if (n > 0) n = Math.min(n, room / band.count());
            return n == 0 ? null : new Plan(band, 0, n, false);
        }
        int delta = floors(origin);
        if (delta < 0) delta = Math.max(delta, -(band.count() - 1));            // keep one repeat of the band
        if (delta > 0) delta = Math.min(delta, room / band.period());
        return delta == 0 ? null : new Plan(band, delta, 0, limited());
    }

    /** The offset within a period of its sparsest slice — the layer a ceiling change duplicates or drops. */
    int sparsestOffset(Band band, List<Set<Long>> slices) {
        int best = 0;
        for (int o = 1; o < band.period(); o++) {
            if (slices.get(band.start() + o).size() < slices.get(band.start() + best).size()) best = o;
        }
        return best;
    }

    /**
     * Applies {@code plan} to {@code processed} (world positions), given the template-local {@code originals}
     * and the placement's {@code settings} (rotation and mirror) and {@code origin}. Pure — used by the
     * processor and its tests.
     */
    public List<StructureTemplate.StructureBlockInfo> apply(Plan plan, BlockPos origin, StructurePlaceSettings settings,
                                                             List<StructureTemplate.StructureBlockInfo> originals,
                                                             List<StructureTemplate.StructureBlockInfo> processed) {
        Map<Long, Long> localOf = new HashMap<>(originals.size() * 2);
        for (StructureTemplate.StructureBlockInfo info : originals) {
            BlockPos world = StructureTemplate.calculateRelativePosition(settings, info.pos()).offset(origin);
            localOf.put(world.asLong(), info.pos().asLong());
        }
        Vec3i unit = StructureTemplate.transform(BlockPos.ZERO.relative(axis, 1), settings.getMirror(),
                settings.getRotation(), BlockPos.ZERO);                          // one block along the axis, in world terms
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed.size() + processed.size() / 4);
        if (plan.floorLayers() != 0) {
            applyCeilings(plan, unit, localOf, originals, processed, out);
        } else {
            applyRepeats(plan, unit, localOf, processed, out);
        }
        return out;
    }

    private void applyRepeats(Plan plan, Vec3i unit, Map<Long, Long> localOf,
                              List<StructureTemplate.StructureBlockInfo> processed, List<StructureTemplate.StructureBlockInfo> out) {
        int p = plan.band().period();
        int delta = plan.delta();
        int bandStart = plan.band().start();
        int bandEnd = bandStart + p;                                              // one repeat: [start, end)
        for (StructureTemplate.StructureBlockInfo info : processed) {
            Long local = localOf.get(info.pos().asLong());
            if (local == null) {   // a block another processor added (footing, rubble): keep where it is
                out.add(info);
                continue;
            }
            int layer = BlockPos.getY(local);
            if (layer < minLayer || layer > maxLayer) {                           // outside the podium / setback range
                out.add(info);
                continue;
            }
            int at = along(BlockPos.of(local));
            if (delta > 0) {
                if (at < bandEnd) {
                    out.add(info);
                    if (at >= bandStart) {
                        for (int k = 1; k <= delta; k++) out.add(moved(info, unit, k * p));
                    }
                } else {
                    out.add(moved(info, unit, delta * p));
                }
            } else {
                int cut = bandStart - delta * p;                                  // [start, cut) is removed
                if (at < bandStart) out.add(info);
                else if (at >= cut) out.add(moved(info, unit, delta * p));
            }
        }
    }

    private void applyCeilings(Plan plan, Vec3i unit, Map<Long, Long> localOf, List<StructureTemplate.StructureBlockInfo> originals,
                               List<StructureTemplate.StructureBlockInfo> processed, List<StructureTemplate.StructureBlockInfo> out) {
        Band band = plan.band();
        int p = band.period(), n = plan.floorLayers(), start = band.start();
        int regionEnd = start + band.count() * p;
        int m = sparsestOffset(band, slices(originals));
        // the offsets a lowered ceiling drops: the sparsest layer and its neighbours
        int dropFrom = n < 0 ? Math.max(0, Math.min(m, p + n)) : 0;
        int dropTo = n < 0 ? dropFrom - n : 0;                                    // [dropFrom, dropTo)
        for (StructureTemplate.StructureBlockInfo info : processed) {
            Long local = localOf.get(info.pos().asLong());
            if (local == null) {
                out.add(info);
                continue;
            }
            int at = along(BlockPos.of(local));
            if (at < start) {
                out.add(info);
            } else if (at >= regionEnd) {
                out.add(moved(info, unit, band.count() * n));
            } else {
                int f = (at - start) / p, o = (at - start) % p;
                if (n > 0) {
                    int to = start + f * (p + n) + o + (o > m ? n : 0);
                    out.add(moved(info, unit, to - at));
                    if (o == m) for (int k = 1; k <= n; k++) out.add(moved(info, unit, to - at + k));
                } else {
                    if (o >= dropFrom && o < dropTo) continue;
                    int to = start + f * (p + n) + o - (o >= dropTo ? -n : 0);
                    out.add(moved(info, unit, to - at));
                }
            }
        }
    }

    private static StructureTemplate.StructureBlockInfo moved(StructureTemplate.StructureBlockInfo info, Vec3i unit, int by) {
        if (by == 0) return info;
        BlockPos pos = info.pos().offset(unit.getX() * by, unit.getY() * by, unit.getZ() * by);
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
