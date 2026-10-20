package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.HashMap;
import java.util.Map;

/**
 * A dimension's terrain density translated onto the train's track level and read per column — the sampler
 * behind {@link NetherCoreGeometry} (the real Nether's router) and {@link OthersideGeometry} (Deeper Darker's
 * Otherside). Columns are trilinearly interpolated between the source's own noise-cell corners exactly the
 * way its {@code NoiseChunk} does, so the band's terrain joins up with itself as the source dimension would.
 *
 * <p>"Space" coordinates are the source dimension's Y; {@code spaceCenterY} lands on {@code bedY}. An
 * instance owns a corner-column memo and is therefore <b>not thread-safe</b>: open one per chunk / per
 * placement attempt and let it go. The memo is what keeps full-resolution sampling affordable — the four
 * cell corners a column interpolates between are shared by every column in that cell, so without it each
 * column re-walks the whole density tree. Callers that walk a whole column must {@link #column} once and
 * reuse it rather than calling a per-block lookup, which would re-do the four corner lookups for every Y.</p>
 */
public final class CellDensityGeometry {

    /** Returned by the density accessors for a Y outside the sampled cell rows. */
    public static final double NO_DENSITY = Double.NaN;

    private final DensityFunction density;
    private final int cellW;
    private final int cellH;
    private final int spaceCenterY;
    private final int bedY;
    private final int yLo;         // lowest world Y the band can occupy
    private final int yHi;         // highest world Y the band can occupy
    private final int cellRowBase; // cell-row index of the first sampled space-Y boundary
    private final int rows;        // number of cell-Y boundaries per corner column
    private final Map<Long, double[]> cornerColumns = new HashMap<>();

    /**
     * @param density      the source dimension's terrain density ({@code finalDensity})
     * @param cellW        the source's noise-cell width (interpolation grid, world-anchored)
     * @param cellH        the source's noise-cell height
     * @param spaceCenterY source-space Y mapped onto the track bed
     * @param spaceYMin    lowest source-space Y sampled
     * @param spaceYMax    highest source-space Y sampled
     * @param bedY         the track bed's world Y
     * @param worldMinY    lowest world Y the band may write
     * @param worldMaxY    highest world Y the band may write
     */
    public CellDensityGeometry(DensityFunction density, int cellW, int cellH, int spaceCenterY,
                               int spaceYMin, int spaceYMax, int bedY, int worldMinY, int worldMaxY) {
        this.density = density;
        this.cellW = cellW;
        this.cellH = cellH;
        this.spaceCenterY = spaceCenterY;
        this.bedY = bedY;
        this.yLo = Math.max(worldMinY, bedY + (spaceYMin - spaceCenterY));
        this.yHi = Math.min(worldMaxY, bedY + (spaceYMax - spaceCenterY));
        int spaceYLo = spaceCenterY + (yLo - bedY);
        this.cellRowBase = Math.floorDiv(spaceYLo, cellH);
        this.rows = Math.floorDiv(spaceCenterY + (yHi - bedY), cellH) - cellRowBase + 2;
    }

    /** Lowest world Y the band can occupy. */
    public int minY() {
        return yLo;
    }

    /** Highest world Y the band can occupy. */
    public int maxY() {
        return yHi;
    }

    /** The track bed's world Y. */
    public int bedY() {
        return bedY;
    }

    /** True when the geometry has any sampled rows at all (a degenerate world could clamp them away). */
    public boolean isUsable() {
        return yLo <= yHi && rows >= 2;
    }

    /** Source-space Y for a band world Y. */
    public int spaceY(int worldY) {
        return spaceCenterY + (worldY - bedY);
    }

    /** Band world Y for a source-space Y — the inverse of {@link #spaceY}. */
    public int worldY(int spaceY) {
        return bedY + (spaceY - spaceCenterY);
    }

    /** True when {@code worldY} falls inside the sampled cell rows — outside it the density is undefined. */
    public boolean isSampled(int worldY) {
        if (worldY < yLo || worldY > yHi) return false;
        int r = Math.floorDiv(spaceY(worldY), cellH) - cellRowBase;
        return r >= 0 && r + 1 < rows;
    }

    /**
     * A single band column's view of the density, with the four shared noise-cell corner profiles hoisted
     * out. Walk a column through one of these.
     */
    public Column column(int sampleX, int worldZ) {
        return new Column(sampleX, worldZ);
    }

    /** @see #column */
    public final class Column {

        private final int worldZ;
        private final double fx;
        private final double fz;
        private final double[] c00;
        private final double[] c10;
        private final double[] c01;
        private final double[] c11;

        private Column(int sampleX, int worldZ) {
            this.worldZ = worldZ;
            int x0 = Math.floorDiv(sampleX, cellW) * cellW;
            int x1 = x0 + cellW;
            this.fx = (double) (sampleX - x0) / cellW;
            int z0 = Math.floorDiv(worldZ, cellW) * cellW;
            int z1 = z0 + cellW;
            this.fz = (double) (worldZ - z0) / cellW;
            this.c00 = cornerColumn(x0, z0);
            this.c10 = cornerColumn(x1, z0);
            this.c01 = cornerColumn(x0, z1);
            this.c11 = cornerColumn(x1, z1);
        }

        /**
         * Terrain density at this column's {@code worldY}, or {@link #NO_DENSITY} when the Y falls outside
         * the sampled cell rows (the caller should leave such blocks alone rather than treat them as air).
         */
        public double densityAt(int worldY) {
            int sy = spaceCenterY + (worldY - bedY);
            int r = Math.floorDiv(sy, cellH) - cellRowBase;
            if (r < 0 || r + 1 >= rows) return NO_DENSITY;
            double fy = (double) (sy - (cellRowBase + r) * cellH) / cellH;
            double bot = bilerp(c00[r], c10[r], c01[r], c11[r], fx, fz);
            double top = bilerp(c00[r + 1], c10[r + 1], c01[r + 1], c11[r + 1], fx, fz);
            return bot + (top - bot) * fy;
        }

        /** True when the terrain is solid at this world Y. */
        public boolean isSolid(int worldY) {
            double d = densityAt(worldY);
            return d > 0.0;   // NaN compares false — an unsampled Y is not solid
        }

        /**
         * World Y of the topmost solid block in this column, or {@link Integer#MIN_VALUE} when the column
         * is open the whole way down.
         */
        public int topSolidY() {
            for (int y = yHi; y >= yLo; y--) {
                if (isSolid(y)) return y;
            }
            return Integer.MIN_VALUE;
        }

        /**
         * World Y of the highest solid block at or below {@code startY} whose immediate neighbour above is
         * open — a floor something can stand on — or {@link Integer#MIN_VALUE} if there is none down to
         * {@link #minY()}.
         */
        public int floorBelow(int startY) {
            int from = Math.min(startY, yHi);
            boolean openAbove = from >= yHi || !isSolid(from + 1);
            for (int y = from; y >= yLo; y--) {
                boolean solid = isSolid(y);
                if (solid && openAbove) return y;
                openAbove = !solid;
            }
            return Integer.MIN_VALUE;
        }

        /** The Z this column was opened at — handy for callers threading columns around. */
        public int worldZ() {
            return worldZ;
        }
    }

    /**
     * Density column at a single noise-cell corner, sampled at each cell-Y boundary spanning the band.
     * Memoised per geometry instance (keyed by the packed corner XZ) — a pure memo of a pure
     * {@code compute}, so it is byte-identical to re-walking the density tree, removing only duplicate
     * evaluations. Correct because {@code cellRowBase}, {@code rows} and {@code cellH} are constant for the
     * life of the instance.
     */
    private double[] cornerColumn(int cornerX, int cornerZ) {
        long key = (((long) cornerX) << 32) ^ (cornerZ & 0xFFFFFFFFL);
        double[] col = cornerColumns.get(key);
        if (col != null) return col;
        col = new double[rows];
        for (int r = 0; r < rows; r++) {
            int by = (cellRowBase + r) * cellH;
            col[r] = density.compute(new DensityFunction.SinglePointContext(cornerX, by, cornerZ));
        }
        cornerColumns.put(key, col);
        return col;
    }

    private static double bilerp(double d00, double d10, double d01, double d11, double fx, double fz) {
        double a = d00 + (d10 - d00) * fx;
        double b = d01 + (d11 - d01) * fx;
        return a + (b - a) * fz;
    }
}
