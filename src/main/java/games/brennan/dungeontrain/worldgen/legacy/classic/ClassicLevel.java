package games.brennan.dungeontrain.worldgen.legacy.classic;

/**
 * One generated Classic level: {@link #WIDTH} × {@link #LENGTH} columns of {@link #HEIGHT} blocks
 * ({@link ClassicBlocks} ids, index {@code (x·LENGTH + z)·HEIGHT + y}). Immutable once built — the array is
 * never written after {@link ClassicTerrain#generate} returns, so every worldgen worker may read it.
 */
public final class ClassicLevel {

    /** Classic 0.30's default level size: 256 × 256, 64 high. */
    public static final int WIDTH = 256;
    public static final int LENGTH = 256;
    public static final int HEIGHT = 64;
    /** Classic's water level (half the height): water fills level y {@code 0 .. WATER_LEVEL - 1}. */
    public static final int WATER_LEVEL = HEIGHT / 2;

    private final byte[] blocks;

    ClassicLevel(byte[] blocks) {
        this.blocks = blocks;
    }

    public static int index(int x, int y, int z) {
        return (x * LENGTH + z) * HEIGHT + y;
    }

    public byte get(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    /**
     * The 16 × 16 column block of the chunk at level-local chunk {@code (chunkX, chunkZ)} ({@code 0..15}),
     * in the chunk-writer layout {@code (x·16 + z)·HEIGHT + y}. Fresh array per call.
     */
    public byte[] chunk(int chunkX, int chunkZ) {
        byte[] out = new byte[16 * 16 * HEIGHT];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                System.arraycopy(blocks, index(chunkX * 16 + x, 0, chunkZ * 16 + z), out, (x * 16 + z) * HEIGHT, HEIGHT);
            }
        }
        return out;
    }
}
