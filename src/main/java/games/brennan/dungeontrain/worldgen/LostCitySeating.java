package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.mixin.JigsawStructureAccessor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import java.util.Arrays;
import java.util.List;

/**
 * How a Lost City building sits in the ground: where its pad lands, and how the terrain is drawn up to it.
 *
 * <p><b>Seating.</b> Vanilla projects a jigsaw start onto the heightmap at one point, so on a slope half
 * the footprint floats and half is buried by whatever the terrain does. {@link #seat} instead samples the
 * sea-floor height ({@code OCEAN_FLOOR_WG} — the seabed in water, the ground elsewhere) on a coarse grid
 * across the whole footprint and drops the pad to the {@link #COVERED_FRACTION} percentile: roughly the
 * lowest 70% of the footprint is then at or under the natural ground, and 30% hangs. Sampling every
 * {@link #SAMPLE_STEP} blocks keeps it to a few dozen column evaluations per start.</p>
 *
 * <p><b>Skirt.</b> The buildings generate with terrain adaptation off ({@code StructureTerrainAdaptationMixin}),
 * because vanilla's beard also <em>carves</em> above the pad and would level every hill inside the box.
 * {@code BeardifierMixin} adds back the half we want: {@link #fillContribution} is vanilla's beard formula
 * restricted to below ground level, so terrain rises toward the pad within {@link #KERNEL_RADIUS} blocks
 * outside the box and fills under it, and nothing above the pad is ever removed. Hills still climb over the
 * building ({@link LostCityGroundProcessor}).</p>
 */
public final class LostCitySeating {

    private LostCitySeating() {}

    /** Share of the footprint that ends up at or under the natural ground. */
    public static final double COVERED_FRACTION = 0.70;

    /** Grid step of the footprint height samples, in blocks. */
    public static final int SAMPLE_STEP = 8;

    /** Vanilla's beard kernel reaches this far from the box. */
    public static final int KERNEL_RADIUS = 12;

    /** Vanilla scales a thin beard's contribution by this. */
    static final double BEARD_SCALE = 0.8;

    /** Whether {@code structure} is a jigsaw whose start pool is the Big Lost City mod's. */
    public static boolean isLostCity(Structure structure) {
        if (!(structure instanceof JigsawStructure jigsaw)) return false;
        Holder<StructureTemplatePool> pool = ((JigsawStructureAccessor) (Object) jigsaw).dungeontrain$startPool();
        ResourceKey<StructureTemplatePool> key = pool.unwrapKey().orElse(null);
        return key != null && LostCityStructures.isLostCityPool(key.location());
    }

    /**
     * Move every piece of {@code start} so its pad sits at the footprint's covered-fraction floor height.
     * Leaves a start without pieces or without a sample untouched.
     */
    public static void seat(StructureStart start, ChunkGenerator generator, LevelHeightAccessor height, RandomState random) {
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.isEmpty()) return;
        BoundingBox box = footprint(pieces);
        int[] floors = sampleFloors(box, (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random));
        if (floors.length == 0) return;
        int padY = padHeight(floors);
        int dy = padY - box.minY();
        if (dy == 0) return;
        for (StructurePiece piece : pieces) piece.move(0, dy, 0);
    }

    /** A column's first free height, as {@code ChunkGenerator#getBaseHeight} answers it. */
    @FunctionalInterface
    public interface FloorSampler {
        int firstFree(int x, int z);
    }

    /** The union of the pieces' boxes. */
    static BoundingBox footprint(List<StructurePiece> pieces) {
        BoundingBox box = new BoundingBox(pieces.get(0).getBoundingBox().getCenter());
        for (StructurePiece piece : pieces) box.encapsulate(piece.getBoundingBox());
        return box;
    }

    /** First-free heights on a {@link #SAMPLE_STEP} grid over the box's footprint, corners included. */
    static int[] sampleFloors(BoundingBox box, FloorSampler sampler) {
        int nx = Math.max(2, (box.getXSpan() + SAMPLE_STEP - 1) / SAMPLE_STEP + 1);
        int nz = Math.max(2, (box.getZSpan() + SAMPLE_STEP - 1) / SAMPLE_STEP + 1);
        int[] out = new int[nx * nz];
        int k = 0;
        for (int i = 0; i < nx; i++) {
            int x = box.minX() + Math.min(box.getXSpan() - 1, i * SAMPLE_STEP);
            for (int j = 0; j < nz; j++) {
                int z = box.minZ() + Math.min(box.getZSpan() - 1, j * SAMPLE_STEP);
                out[k++] = sampler.firstFree(x, z);
            }
        }
        return out;
    }

    /**
     * The pad height for these first-free heights: the surface block ({@code firstFree - 1}) of the sample at
     * the {@code 1 - COVERED_FRACTION} quantile, so {@link #COVERED_FRACTION} of the samples lie at or above it.
     */
    static int padHeight(int[] firstFree) {
        int[] sorted = firstFree.clone();
        Arrays.sort(sorted);
        int index = (int) Math.floor((1.0 - COVERED_FRACTION) * (sorted.length - 1) + 1e-9);
        return sorted[index] - 1;
    }

    /**
     * Vanilla's thin-beard contribution for a point {@code (dx, dy, dz)} from a rigid piece — {@code dx}/{@code dz}
     * the distance outside the box (0 inside), {@code dy} the height above its ground level — kept only below
     * ground ({@code dy < 0}): positive density that fills under the pad and slopes the ground up to it.
     * Zero at or above ground level and outside the kernel.
     */
    public static double fillContribution(int dx, int dy, int dz) {
        if (dy >= 0) return 0.0;
        if (!inKernel(dx) || !inKernel(dy) || !inKernel(dz)) return 0.0;
        double d0 = dy + 0.5;
        double lenSq = Mth.lengthSquared(dx, d0, dz);
        double d2 = -d0 * Mth.fastInvSqrt(lenSq / 2.0) / 2.0;
        double kernel = Math.exp(-lenSq / 16.0);
        return d2 * kernel * BEARD_SCALE;
    }

    private static boolean inKernel(int d) {
        return d + KERNEL_RADIUS >= 0 && d + KERNEL_RADIUS < 2 * KERNEL_RADIUS;
    }
}
