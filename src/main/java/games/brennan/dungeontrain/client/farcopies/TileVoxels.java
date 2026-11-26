package games.brennan.dungeontrain.client.farcopies;

/**
 * One room tile as a grid of colours: {@code 0} is empty, anything else is {@code 0xFFRRGGBB}
 * (the alpha byte is what marks a cell solid, so black stays black).
 *
 * <p>Immutable once built; {@link Builder} fills it. Indexed {@code x + sizeX * (z + sizeZ * y)}.</p>
 */
public final class TileVoxels {

    public static final int EMPTY = 0;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] cells;

    private TileVoxels(int sizeX, int sizeY, int sizeZ, int[] cells) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }

    /** The colour at a cell, or {@link #EMPTY}; out of range reads as empty. */
    public int at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return EMPTY;
        return cells[x + sizeX * (z + sizeZ * y)];
    }

    public boolean solid(int x, int y, int z) {
        return at(x, y, z) != EMPTY;
    }

    public int solidCount() {
        int n = 0;
        for (int c : cells) if (c != EMPTY) n++;
        return n;
    }

    /**
     * This grid with every {@code factor}-sided cube folded into one cell: solid when at least half
     * of the cube's in-range cells are, coloured by their average. A factor of 1 returns this grid.
     */
    public TileVoxels downsample(int factor) {
        if (factor <= 1) return this;
        int nx = ceilDiv(sizeX, factor);
        int ny = ceilDiv(sizeY, factor);
        int nz = ceilDiv(sizeZ, factor);
        Builder out = new Builder(nx, ny, nz);
        for (int cy = 0; cy < ny; cy++) {
            for (int cz = 0; cz < nz; cz++) {
                for (int cx = 0; cx < nx; cx++) {
                    out.set(cx, cy, cz, foldCube(cx * factor, cy * factor, cz * factor, factor));
                }
            }
        }
        return out.build();
    }

    private int foldCube(int x0, int y0, int z0, int factor) {
        int total = 0;
        int solid = 0;
        long r = 0;
        long g = 0;
        long b = 0;
        for (int y = y0; y < Math.min(y0 + factor, sizeY); y++) {
            for (int z = z0; z < Math.min(z0 + factor, sizeZ); z++) {
                for (int x = x0; x < Math.min(x0 + factor, sizeX); x++) {
                    total++;
                    int c = at(x, y, z);
                    if (c == EMPTY) continue;
                    solid++;
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
            }
        }
        if (solid == 0 || solid * 2 < total) return EMPTY;
        return 0xFF000000 | (int) (r / solid) << 16 | (int) (g / solid) << 8 | (int) (b / solid);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** Fills a grid cell by cell. */
    public static final class Builder {
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int[] cells;

        public Builder(int sizeX, int sizeY, int sizeZ) {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("tile size " + sizeX + "x" + sizeY + "x" + sizeZ);
            }
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.cells = new int[sizeX * sizeY * sizeZ];
        }

        /** Set a cell to {@code 0xRRGGBB} (made solid), or {@link #EMPTY}. */
        public Builder setRgb(int x, int y, int z, int rgb) {
            return set(x, y, z, 0xFF000000 | (rgb & 0xFFFFFF));
        }

        public Builder clear(int x, int y, int z) {
            return set(x, y, z, EMPTY);
        }

        private Builder set(int x, int y, int z, int argb) {
            cells[x + sizeX * (z + sizeZ * y)] = argb;
            return this;
        }

        public TileVoxels build() {
            return new TileVoxels(sizeX, sizeY, sizeZ, cells.clone());
        }
    }
}
