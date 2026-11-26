package games.brennan.dungeontrain.client.farcopies;

import java.util.ArrayList;
import java.util.List;

/**
 * One room tile turned into flat-coloured boxes, at the three levels of detail far copies are drawn
 * at.
 *
 * <ul>
 *   <li><b>detail</b>: the tile's solid cells greedy-merged, cells of nearly the same colour merged
 *       together; coarsened (2, then 4, then 8 blocks a cell) until it fits {@code detailCap}.</li>
 *   <li><b>coarse</b>: the same at 4 blocks a cell, for the middle ring.</li>
 *   <li><b>strips</b>: only what runs the tile's whole length or width — floor and ceiling planes,
 *       long walls — so the outer ring can draw one box across many tiles instead of one per tile.</li>
 * </ul>
 */
public final class RoomBoxModel {

    /** Detail-ring box cap per tile before it coarsens. */
    public static final int DEFAULT_DETAIL_CAP = 256;
    /** A detail model this small is used as the coarse one as it is. */
    public static final int COARSE_TARGET = 48;
    /** Cell size of the coarse model, in blocks. */
    public static final int COARSE_CELL = 4;
    /** Share of a line or layer that must be solid for it to count as running the whole tile. */
    static final float STRIP_FILL = 0.75f;
    /** Low bits dropped from each colour channel before cells are compared for merging. */
    private static final int COLOUR_SHIFT = 5;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<FarBox> detail;
    private final List<FarBox> coarse;
    private final Strips strips;

    private RoomBoxModel(int sizeX, int sizeY, int sizeZ, List<FarBox> detail, List<FarBox> coarse,
                         Strips strips) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.detail = List.copyOf(detail);
        this.coarse = List.copyOf(coarse);
        this.strips = strips;
    }

    public static RoomBoxModel of(TileVoxels voxels) {
        return of(voxels, DEFAULT_DETAIL_CAP);
    }

    public static RoomBoxModel of(TileVoxels voxels, int detailCap) {
        List<FarBox> detail = greedy(voxels, 1);
        for (int factor = 2; detail.size() > detailCap && factor <= 8; factor *= 2) {
            detail = greedy(voxels.downsample(factor), factor, voxels);
        }
        // A room already cheap enough keeps its detail in the middle ring too; and a coarsening that
        // folds away a one-block floor (it is never half of a 4-block cube) would leave that ring
        // empty, which is a hole in the floor rather than a cheaper one.
        List<FarBox> coarse = detail;
        if (detail.size() > COARSE_TARGET) {
            List<FarBox> folded = greedy(voxels.downsample(COARSE_CELL), COARSE_CELL, voxels);
            if (!folded.isEmpty() && folded.size() < detail.size()) coarse = folded;
        }
        return new RoomBoxModel(voxels.sizeX(), voxels.sizeY(), voxels.sizeZ(),
            detail, coarse, strips(voxels));
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public List<FarBox> detail() { return detail; }
    public List<FarBox> coarse() { return coarse; }
    public Strips strips() { return strips; }

    public boolean isEmpty() {
        return detail.isEmpty();
    }

    // ---------- greedy merge ----------

    private static List<FarBox> greedy(TileVoxels v, int scale) {
        return greedy(v, scale, v);
    }

    /**
     * Greedy box merge of {@code v}, whose cells are {@code scale} blocks a side, clamped to the
     * full-resolution tile {@code bounds} so a coarsened edge never pokes past the room.
     */
    static List<FarBox> greedy(TileVoxels v, int scale, TileVoxels bounds) {
        boolean[] used = new boolean[v.sizeX() * v.sizeY() * v.sizeZ()];
        List<FarBox> out = new ArrayList<>();
        for (int y = 0; y < v.sizeY(); y++) {
            for (int z = 0; z < v.sizeZ(); z++) {
                for (int x = 0; x < v.sizeX(); x++) {
                    if (used[index(v, x, y, z)] || !v.solid(x, y, z)) continue;
                    int key = colourKey(v.at(x, y, z));
                    int x1 = x + 1;
                    while (free(v, used, x1, y, z, key)) x1++;
                    int z1 = z + 1;
                    while (rowFree(v, used, x, x1, y, z1, key)) z1++;
                    int y1 = y + 1;
                    while (slabFree(v, used, x, x1, y1, z, z1, key)) y1++;
                    out.add(claim(v, used, x, x1, y, y1, z, z1, scale, bounds));
                }
            }
        }
        return out;
    }

    private static FarBox claim(TileVoxels v, boolean[] used, int x0, int x1, int y0, int y1,
                                int z0, int z1, int scale, TileVoxels bounds) {
        long r = 0;
        long g = 0;
        long b = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    used[index(v, x, y, z)] = true;
                    int c = v.at(x, y, z);
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
            }
        }
        int rgb = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        return new FarBox(
            x0 * scale, y0 * scale, z0 * scale,
            Math.min(x1 * scale, bounds.sizeX()), Math.min(y1 * scale, bounds.sizeY()),
            Math.min(z1 * scale, bounds.sizeZ()), rgb);
    }

    private static boolean free(TileVoxels v, boolean[] used, int x, int y, int z, int key) {
        return x < v.sizeX() && y < v.sizeY() && z < v.sizeZ()
            && !used[index(v, x, y, z)] && v.solid(x, y, z) && colourKey(v.at(x, y, z)) == key;
    }

    private static boolean rowFree(TileVoxels v, boolean[] used, int x0, int x1, int y, int z, int key) {
        for (int x = x0; x < x1; x++) if (!free(v, used, x, y, z, key)) return false;
        return true;
    }

    private static boolean slabFree(TileVoxels v, boolean[] used, int x0, int x1, int y, int z0, int z1,
                                    int key) {
        for (int z = z0; z < z1; z++) if (!rowFree(v, used, x0, x1, y, z, key)) return false;
        return true;
    }

    private static int index(TileVoxels v, int x, int y, int z) {
        return x + v.sizeX() * (z + v.sizeZ() * y);
    }

    /** Colour with the low bits of each channel dropped, so near-identical blocks merge. */
    static int colourKey(int argb) {
        int r = ((argb >> 16) & 0xFF) >> COLOUR_SHIFT;
        int g = ((argb >> 8) & 0xFF) >> COLOUR_SHIFT;
        int b = (argb & 0xFF) >> COLOUR_SHIFT;
        return r << 16 | g << 8 | b;
    }

    // ---------- strips ----------

    /**
     * What of a tile runs its whole length or width, for the outer ring.
     *
     * @param planes  layers (Y ranges) solid across the whole tile — floors and ceilings
     * @param xStrips lines running the full X length, by their Y and Z range
     * @param zStrips lines running the full Z width, by their Y and X range
     */
    public record Strips(List<Span> planes, List<Span> xStrips, List<Span> zStrips) {
        public Strips {
            planes = List.copyOf(planes);
            xStrips = List.copyOf(xStrips);
            zStrips = List.copyOf(zStrips);
        }

        public int size() {
            return planes.size() + xStrips.size() + zStrips.size();
        }
    }

    /**
     * A strip's cross-section: Y from {@code y0} to {@code y1}, and on the one other axis that is
     * not stretched, {@code a0} to {@code a1} (unused for a plane). Ends exclusive.
     */
    public record Span(int y0, int y1, int a0, int a1, int rgb) {}

    static Strips strips(TileVoxels v) {
        boolean[] planeLayer = new boolean[v.sizeY()];
        int[] planeColour = new int[v.sizeY()];
        for (int y = 0; y < v.sizeY(); y++) {
            ColourSum sum = new ColourSum();
            for (int z = 0; z < v.sizeZ(); z++) {
                for (int x = 0; x < v.sizeX(); x++) sum.add(v.at(x, y, z));
            }
            planeLayer[y] = sum.fill(v.sizeX() * v.sizeZ()) >= STRIP_FILL;
            planeColour[y] = sum.rgb();
        }
        List<Span> planes = mergeLayers(planeLayer, planeColour);

        // Lines along X, one per (y, z); then along Z, one per (y, x). Layers already drawn as a
        // plane are left out of both, so a floor is not drawn three times over.
        int[][] xLine = new int[v.sizeY()][v.sizeZ()];
        for (int y = 0; y < v.sizeY(); y++) {
            if (planeLayer[y]) continue;
            for (int z = 0; z < v.sizeZ(); z++) {
                ColourSum sum = new ColourSum();
                for (int x = 0; x < v.sizeX(); x++) sum.add(v.at(x, y, z));
                xLine[y][z] = sum.fill(v.sizeX()) >= STRIP_FILL ? sum.argb() : 0;
            }
        }
        int[][] zLine = new int[v.sizeY()][v.sizeX()];
        for (int y = 0; y < v.sizeY(); y++) {
            if (planeLayer[y]) continue;
            for (int x = 0; x < v.sizeX(); x++) {
                ColourSum sum = new ColourSum();
                for (int z = 0; z < v.sizeZ(); z++) sum.add(v.at(x, y, z));
                zLine[y][x] = sum.fill(v.sizeZ()) >= STRIP_FILL ? sum.argb() : 0;
            }
        }
        return new Strips(planes, merge2d(xLine), merge2d(zLine));
    }

    private static List<Span> mergeLayers(boolean[] layer, int[] colour) {
        List<Span> out = new ArrayList<>();
        int y = 0;
        while (y < layer.length) {
            if (!layer[y]) { y++; continue; }
            int key = colourKey(colour[y]);
            int y1 = y + 1;
            while (y1 < layer.length && layer[y1] && colourKey(colour[y1]) == key) y1++;
            out.add(new Span(y, y1, 0, 0, colour[y] & 0xFFFFFF));
            y = y1;
        }
        return out;
    }

    /** Greedy rectangles over a (y, a) grid of colours, {@code 0} empty. */
    private static List<Span> merge2d(int[][] grid) {
        List<Span> out = new ArrayList<>();
        int ny = grid.length;
        if (ny == 0) return out;
        int na = grid[0].length;
        boolean[][] used = new boolean[ny][na];
        for (int y = 0; y < ny; y++) {
            for (int a = 0; a < na; a++) {
                if (used[y][a] || grid[y][a] == 0) continue;
                int key = colourKey(grid[y][a]);
                int a1 = a + 1;
                while (a1 < na && !used[y][a1] && grid[y][a1] != 0 && colourKey(grid[y][a1]) == key) a1++;
                int y1 = y + 1;
                while (y1 < ny && rangeMatches(grid, used, y1, a, a1, key)) y1++;
                for (int yy = y; yy < y1; yy++) {
                    for (int aa = a; aa < a1; aa++) used[yy][aa] = true;
                }
                out.add(new Span(y, y1, a, a1, grid[y][a] & 0xFFFFFF));
            }
        }
        return out;
    }

    private static boolean rangeMatches(int[][] grid, boolean[][] used, int y, int a0, int a1, int key) {
        for (int a = a0; a < a1; a++) {
            if (used[y][a] || grid[y][a] == 0 || colourKey(grid[y][a]) != key) return false;
        }
        return true;
    }

    /** Running average of the solid cells of a line or layer. */
    private static final class ColourSum {
        private long r;
        private long g;
        private long b;
        private int n;

        void add(int argb) {
            if (argb == TileVoxels.EMPTY) return;
            r += (argb >> 16) & 0xFF;
            g += (argb >> 8) & 0xFF;
            b += argb & 0xFF;
            n++;
        }

        float fill(int of) {
            return of <= 0 ? 0.0f : (float) n / of;
        }

        int rgb() {
            if (n == 0) return 0;
            return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }

        int argb() {
            return n == 0 ? 0 : 0xFF000000 | rgb();
        }
    }
}
