package games.brennan.dungeontrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes a Lost City building taller or shorter by repeating or removing its floors, roof intact.
 *
 * <p>The mod's towers are built of a floor that repeats every few blocks. Per placement this finds that
 * repeat — the longest run of layers where layer {@code y} matches layer {@code y + p} for some period
 * {@code p} in {@code period_min..period_max}, with at least {@code min_similarity} of their full-block
 * footprint in common ({@link #findBand}; the mod's towers are two slab layers then five sparse column
 * layers per floor, and the sparse layers only agree at about half) — and rolls a floor count between {@code min_floors} and {@code max_floors}
 * (zero excluded). A positive count copies the band that many more times and lifts everything above it;
 * a negative count removes that many floors and lowers everything above. A building with no repeat, or
 * too few floors to lose, is left as it is. The pad is never touched, and a stretch is trimmed so the roof
 * stays under the build limit.</p>
 *
 * <p>Runs in {@link #finalizeProcessing}. Entities are not moved, so an item frame above the band may
 * end up off its wall and pop; the templates carry very few. Registered as
 * {@code dungeontrain:lost_city_stretch}.</p>
 */
public final class LostCityStretchProcessor extends StructureProcessor {

    public static final MapCodec<LostCityStretchProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(-16, 16).fieldOf("min_floors").forGetter(p -> p.minFloors),
            Codec.intRange(-16, 16).fieldOf("max_floors").forGetter(p -> p.maxFloors),
            Codec.intRange(2, 32).optionalFieldOf("period_min", 4).forGetter(p -> p.periodMin),
            Codec.intRange(2, 32).optionalFieldOf("period_max", 16).forGetter(p -> p.periodMax),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("min_similarity", 0.45F).forGetter(p -> p.minSimilarity)
    ).apply(i, LostCityStretchProcessor::new));

    public static final StructureProcessorType<LostCityStretchProcessor> TYPE = () -> CODEC;

    private static final int SALT = 0x57E7;
    /** The roof must stay below this world height. */
    private static final int MAX_TOP_Y = 316;
    /** Layers this close to the pad or the roof are never part of the band. */
    private static final int MARGIN = 2;
    /** A layer joins a band only with at least this share of the largest layer's mass. */
    private static final double SUBSTANTIAL = 0.03;

    private final int minFloors, maxFloors, periodMin, periodMax;
    private final float minSimilarity;

    public LostCityStretchProcessor(int minFloors, int maxFloors, int periodMin, int periodMax, float minSimilarity) {
        this.minFloors = Math.min(minFloors, maxFloors);
        this.maxFloors = Math.max(minFloors, maxFloors);
        this.periodMin = Math.min(periodMin, periodMax);
        this.periodMax = Math.max(periodMin, periodMax);
        this.minSimilarity = minSimilarity;
    }

    /** The repeating floor band: layers {@code [start, start + period × count)}. */
    public record Band(int start, int period, int count) {}

    private static long seed(BlockPos origin) {
        return origin.asLong() * 0x9E3779B97F4A7C15L + SALT;
    }

    /** The floor delta rolled for a placement: never zero (returns 0 only when the range allows nothing else). */
    public int floors(BlockPos origin) {
        List<Integer> options = new ArrayList<>();
        for (int f = minFloors; f <= maxFloors; f++) if (f != 0) options.add(f);
        if (options.isEmpty()) return 0;
        return options.get((int) (LostCityStructures.hash01(seed(origin), 0, 1) * options.size()));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Whether a block counts for a layer's footprint: a full cube that is not foliage. Moss on a wall still
     * counts as wall, while vines, carpets, plants, panes and leaves — which differ floor to floor — do not.
     */
    static boolean isMass(BlockState state) {
        if (state.isAir() || state.getBlock() instanceof LeavesBlock) return false;
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Jaccard similarity of two layers' footprints. */
    static double similarity(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int common = 0;
        for (Long k : a) if (b.contains(k)) common++;
        int union = a.size() + b.size() - common;
        return union == 0 ? 1.0 : (double) common / union;
    }

    /**
     * The longest repeating band in {@code layers} (index = local Y, each the set of {@code (x,z)} keys the
     * layer's mass occupies), or {@code null} if no period repeats at least once at the required similarity.
     * A run counts only through substantial layers — at least {@link #SUBSTANTIAL} of the largest layer's mass —
     * so a chimney or an aerial, identical for a few layers, never passes for a floor.
     */
    @Nullable
    public Band findBand(List<Set<Long>> layers) {
        int height = layers.size();
        int largest = 0;
        for (Set<Long> layer : layers) largest = Math.max(largest, layer.size());
        if (largest == 0) return null;
        double floor = Math.max(1.0, SUBSTANTIAL * largest);
        Band best = null;
        for (int p = periodMin; p <= periodMax; p++) {
            int run = 0;
            for (int y = MARGIN; y + p < height - MARGIN; y++) {
                boolean match = layers.get(y).size() >= floor && similarity(layers.get(y), layers.get(y + p)) >= minSimilarity;
                run = match ? run + 1 : 0;
                if (run >= p) {
                    int count = run / p + 1;                       // periods in [y - run + 1, y + p]
                    int start = y - run + 1;
                    if (best == null || count * p > best.count * best.period) best = new Band(start, p, count);
                }
            }
        }
        return best;
    }

    /**
     * Applies the stretch to {@code processed} (world positions) for a piece placed at {@code origin}.
     * Pure — used by the processor and its tests.
     */
    public List<StructureTemplate.StructureBlockInfo> stretch(BlockPos origin, List<StructureTemplate.StructureBlockInfo> processed) {
        int top = 0;
        for (StructureTemplate.StructureBlockInfo info : processed) top = Math.max(top, info.pos().getY() - origin.getY());
        int height = top + 1;
        if (height < 2 * MARGIN + 2 * periodMin) return processed;
        List<Set<Long>> layers = new ArrayList<>(height);
        for (int y = 0; y < height; y++) layers.add(new HashSet<>());
        for (StructureTemplate.StructureBlockInfo info : processed) {
            if (!isMass(info.state())) continue;
            layers.get(info.pos().getY() - origin.getY()).add(key(info.pos().getX(), info.pos().getZ()));
        }
        Band band = findBand(layers);
        if (band == null) return processed;
        int delta = floors(origin);
        if (delta < 0) delta = Math.max(delta, -(band.count() - 1));            // keep one floor of the band
        if (delta > 0) delta = Math.min(delta, Math.max(0, (MAX_TOP_Y - origin.getY() - height) / band.period()));
        if (delta == 0) return processed;
        int p = band.period();
        int bandEnd = band.start() + p;                                           // one period: [start, bandEnd)
        int shift = delta * p;
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed.size() + Math.max(0, shift) * layers.get(band.start()).size());
        for (StructureTemplate.StructureBlockInfo info : processed) {
            int y = info.pos().getY() - origin.getY();
            if (delta > 0) {
                if (y < bandEnd) {
                    out.add(info);
                    if (y >= band.start()) {
                        for (int k = 1; k <= delta; k++) out.add(moved(info, k * p));
                    }
                } else {
                    out.add(moved(info, shift));
                }
            } else {
                int cut = band.start() - shift;                                   // [start, cut) is removed
                if (y < band.start()) out.add(info);
                else if (y >= cut) out.add(moved(info, shift));
            }
        }
        return out;
    }

    private static StructureTemplate.StructureBlockInfo moved(StructureTemplate.StructureBlockInfo info, int dy) {
        return new StructureTemplate.StructureBlockInfo(info.pos().above(dy), info.state(), info.nbt() == null ? null : info.nbt().copy());
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset,
                                                                         BlockPos pivot,
                                                                         List<StructureTemplate.StructureBlockInfo> originals,
                                                                         List<StructureTemplate.StructureBlockInfo> processed,
                                                                         StructurePlaceSettings settings) {
        return stretch(offset, processed);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
