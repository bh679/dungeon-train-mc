package games.brennan.dungeontrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bites huge chunks out of a Lost City building and heaps the missing mass as rubble beneath.
 *
 * <p>Per placement it rolls {@code bites_min..bites_max} ellipsoids, each centred on the building's outer
 * shell (a random side, pulled a little inward so the bite carves deep) at a height in the upper part of
 * the piece, with a horizontal radius of {@code radius_min..radius_max} times the building's narrower side and a
 * taller vertical radius. Every solid block inside an ellipsoid is removed, with a noisy edge so the wound
 * is ragged. The pad layer is never touched.</p>
 *
 * <p>The removed blocks are counted per column, smeared over their neighbours, and turned into piles:
 * each column gets {@code pile_scale × √mass} rubble blocks (at most {@code pile_max}) stacked upward from
 * the first solid block below the bite — a floor inside the building, the pad outside — never overwriting
 * anything kept. So the debris lands on the ground under the hole, spilling over the surrounding floors
 * and the pavement.</p>
 *
 * <p>Runs in {@link #finalizeProcessing}, which alone sees the whole piece. Registered as
 * {@code dungeontrain:lost_city_bite}.</p>
 */
public final class LostCityBiteProcessor extends StructureProcessor {

    public static final MapCodec<LostCityBiteProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(1, 8).optionalFieldOf("bites_min", 1).forGetter(p -> p.bitesMin),
            Codec.intRange(1, 8).optionalFieldOf("bites_max", 2).forGetter(p -> p.bitesMax),
            Codec.floatRange(0.05F, 1.0F).optionalFieldOf("radius_min", 0.3F).forGetter(p -> p.radiusMin),
            Codec.floatRange(0.05F, 1.0F).optionalFieldOf("radius_max", 0.5F).forGetter(p -> p.radiusMax),
            BuiltInRegistries.BLOCK.byNameCodec().listOf().fieldOf("rubble").forGetter(p -> p.rubble),
            Codec.floatRange(0.0F, 4.0F).optionalFieldOf("pile_scale", 0.7F).forGetter(p -> p.pileScale),
            Codec.intRange(0, 32).optionalFieldOf("pile_max", 9).forGetter(p -> p.pileMax)
    ).apply(i, LostCityBiteProcessor::new));

    public static final StructureProcessorType<LostCityBiteProcessor> TYPE = () -> CODEC;

    private static final int SALT = 0xB17E;
    /** Bite centres sit between these fractions of the piece's height. */
    private static final double CENTRE_Y_MIN = 0.35, CENTRE_Y_MAX = 0.9;
    /** The bite is taller than it is wide. */
    private static final double VERTICAL_STRETCH = 1.4;
    /** How far a bite centre is pulled in from the shell, as a fraction of its radius. */
    private static final double INSET = 0.35;
    /** Debris from one column spreads this far into its neighbours. */
    private static final int SPREAD = 3;

    private final int bitesMin, bitesMax;
    private final float radiusMin, radiusMax;
    private final List<Block> rubble;
    private final float pileScale;
    private final int pileMax;

    public LostCityBiteProcessor(int bitesMin, int bitesMax, float radiusMin, float radiusMax, List<Block> rubble,
                                 float pileScale, int pileMax) {
        if (rubble.isEmpty()) throw new IllegalArgumentException("lost_city_bite needs at least one rubble block");
        this.bitesMin = Math.min(bitesMin, bitesMax);
        this.bitesMax = Math.max(bitesMin, bitesMax);
        this.radiusMin = Math.min(radiusMin, radiusMax);
        this.radiusMax = Math.max(radiusMin, radiusMax);
        this.rubble = List.copyOf(rubble);
        this.pileScale = pileScale;
        this.pileMax = pileMax;
    }

    /** One bite: centre and radii in piece-local coordinates. */
    public record Bite(double cx, double cy, double cz, double r, double ry) {
        boolean contains(int x, int y, int z, double edge) {
            double dx = (x - cx) / r, dy = (y - cy) / ry, dz = (z - cz) / r;
            return dx * dx + dy * dy + dz * dz < edge;
        }
    }

    private static double roll(long seed, int a, int b) {
        return LostCityStructures.hash01(seed, a, b);
    }

    private static long seed(BlockPos origin) {
        return origin.asLong() * 0x9E3779B97F4A7C15L + SALT;
    }

    /**
     * The bites for a piece placed at {@code origin} whose building (pad excluded) spans local
     * {@code [minX,maxX] × [minZ,maxZ]} and stands {@code height} blocks tall.
     */
    public List<Bite> bites(BlockPos origin, int minX, int maxX, int minZ, int maxZ, int height) {
        long s = seed(origin);
        int count = bitesMin + (int) (roll(s, 0, 1) * (bitesMax - bitesMin + 1));
        int narrow = Math.max(1, Math.min(maxX - minX, maxZ - minZ));
        List<Bite> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double r = Math.max(2.0, (radiusMin + (radiusMax - radiusMin) * roll(s, i, 2)) * narrow);
            double along = 0.1 + 0.8 * roll(s, i, 3);
            double cy = (CENTRE_Y_MIN + (CENTRE_Y_MAX - CENTRE_Y_MIN) * roll(s, i, 4)) * height;
            int side = (int) (roll(s, i, 5) * 4);
            double cx, cz, in = r * INSET;
            switch (side) {
                case 0 -> { cx = minX + in; cz = minZ + along * (maxZ - minZ); }
                case 1 -> { cx = maxX - in; cz = minZ + along * (maxZ - minZ); }
                case 2 -> { cx = minX + along * (maxX - minX); cz = minZ + in; }
                default -> { cx = minX + along * (maxX - minX); cz = maxZ - in; }
            }
            out.add(new Bite(cx, cy, cz, r, r * VERTICAL_STRETCH));
        }
        return out;
    }

    private BlockState rubbleAt(long s, int x, int y, int z) {
        int i = (int) (roll(s + y, x * 131 + z, y * 7 + x) * rubble.size());
        return rubble.get(Math.min(i, rubble.size() - 1)).defaultBlockState();
    }

    /**
     * Applies the bites to {@code processed} (world positions) for a piece placed at {@code origin}: the
     * kept blocks plus the rubble piles. Pure — used by the processor and its tests.
     */
    public List<StructureTemplate.StructureBlockInfo> bite(BlockPos origin, List<StructureTemplate.StructureBlockInfo> processed) {
        if (processed.isEmpty()) return processed;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, top = 0;
        int bMinX = Integer.MAX_VALUE, bMaxX = Integer.MIN_VALUE, bMinZ = Integer.MAX_VALUE, bMaxZ = Integer.MIN_VALUE;
        for (StructureTemplate.StructureBlockInfo info : processed) {
            int x = info.pos().getX() - origin.getX(), y = info.pos().getY() - origin.getY(), z = info.pos().getZ() - origin.getZ();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            top = Math.max(top, y);
            if (y >= 2 && !info.state().isAir()) {   // the building proper, not the pad and its plant cover
                bMinX = Math.min(bMinX, x); bMaxX = Math.max(bMaxX, x); bMinZ = Math.min(bMinZ, z); bMaxZ = Math.max(bMaxZ, z);
            }
        }
        if (bMinX > bMaxX || top < 4) return processed;
        int height = top + 1, w = maxX - minX + 1, d = maxZ - minZ + 1;
        List<Bite> bites = bites(origin, bMinX, bMaxX, bMinZ, bMaxZ, height);
        long s = seed(origin);
        // occupancy: index of the kept solid block per cell, -1 for air / nothing
        int[] cell = new int[w * height * d];
        Arrays.fill(cell, -1);
        boolean[] removed = new boolean[processed.size()];
        int[] mass = new int[w * d];
        for (int i = 0; i < processed.size(); i++) {
            StructureTemplate.StructureBlockInfo info = processed.get(i);
            int x = info.pos().getX() - origin.getX() - minX, y = info.pos().getY() - origin.getY(), z = info.pos().getZ() - origin.getZ() - minZ;
            if (info.state().isAir()) continue;
            boolean gone = false;
            if (y > 0) {
                for (Bite b : bites) {
                    if (b.contains(x + minX, y, z + minZ, 0.75 + 0.5 * roll(s, x * 977 + z, y))) { gone = true; break; }
                }
            }
            if (gone) {
                removed[i] = true;
                mass[x * d + z]++;
            } else {
                cell[(x * height + y) * d + z] = i;
            }
        }
        // smear the debris over neighbouring columns
        int[] spread = new int[w * d];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int m = mass[x * d + z];
                if (m == 0) continue;
                for (int dx = -SPREAD; dx <= SPREAD; dx++) {
                    for (int dz = -SPREAD; dz <= SPREAD; dz++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                        int dist = Math.abs(dx) + Math.abs(dz);
                        if (dist > SPREAD) continue;
                        spread[nx * d + nz] += m * (SPREAD + 1 - dist);
                    }
                }
            }
        }
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed.size());
        for (int i = 0; i < processed.size(); i++) if (!removed[i]) out.add(processed.get(i));
        int lowest = height - 1;
        for (Bite b : bites) lowest = Math.min(lowest, (int) Math.floor(b.cy - b.ry));
        lowest = Math.max(1, lowest);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int sp = spread[x * d + z];
                if (sp == 0) continue;
                int h = Math.min(pileMax, (int) Math.round(pileScale * Math.sqrt(sp / (double) (SPREAD + 1))));
                if (h <= 0) continue;
                // the floor: the highest kept solid block at or below the lowest bite reaching this column
                int floor = -1;
                for (int y = lowest; y >= 0; y--) {
                    if (cell[(x * height + y) * d + z] >= 0) { floor = y; break; }
                }
                if (floor < 0) continue;
                for (int k = 1; k <= h; k++) {
                    int y = floor + k;
                    if (y >= height || cell[(x * height + y) * d + z] >= 0) break;
                    BlockPos at = new BlockPos(origin.getX() + minX + x, origin.getY() + y, origin.getZ() + minZ + z);
                    out.add(new StructureTemplate.StructureBlockInfo(at, rubbleAt(s, x, y, z), null));
                }
            }
        }
        return out;
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset,
                                                                         BlockPos pivot,
                                                                         List<StructureTemplate.StructureBlockInfo> originals,
                                                                         List<StructureTemplate.StructureBlockInfo> processed,
                                                                         StructurePlaceSettings settings) {
        return bite(offset, processed);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
