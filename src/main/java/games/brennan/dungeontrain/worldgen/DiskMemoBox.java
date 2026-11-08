package games.brennan.dungeontrain.worldgen;

import java.util.Arrays;

/**
 * One bit per block in a box around a decorating chunk: the chunk plus {@link #MARGIN} blocks on each side,
 * full build height. Holds {@link DiskTestMemo}'s failed-test positions. Positions outside the box are never
 * remembered ({@link #contains} answers false, {@link #add} ignores them), so a disk reaching past the margin
 * only loses the shortcut there.
 *
 * <p>Reused per thread: {@link #reset} clears only the words written since the last reset, so a disk
 * feature that remembers little pays little.</p>
 */
public final class DiskMemoBox {

    /** Blocks past the chunk on each side: twice the radius WWOO's widest disks reach. */
    public static final int MARGIN = 16;
    static final int SIDE = 16 + 2 * MARGIN;

    private long[] bits = new long[0];
    private int[] dirty = new int[64];
    private int dirtyCount;
    private int minX;
    private int minZ;
    private int minY;
    private int height;

    /** Empty the box and move it to the chunk at block origin ({@code chunkX}, {@code chunkZ}). */
    public void reset(int chunkX, int chunkZ, int minY, int height) {
        int words = (SIDE * SIDE * height + 63) >>> 6;
        if (bits.length != words) {
            bits = new long[words];
        } else {
            for (int i = 0; i < dirtyCount; i++) bits[dirty[i]] = 0L;
        }
        dirtyCount = 0;
        this.minX = chunkX - MARGIN;
        this.minZ = chunkZ - MARGIN;
        this.minY = minY;
        this.height = height;
    }

    public boolean contains(int x, int y, int z) {
        int i = index(x, y, z);
        return i >= 0 && (bits[i >>> 6] & (1L << i)) != 0;
    }

    public void add(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0) return;
        int w = i >>> 6;
        long word = bits[w];
        if (word == 0L) markDirty(w);
        bits[w] = word | (1L << i);
    }

    public void remove(int x, int y, int z) {
        int i = index(x, y, z);
        if (i >= 0) bits[i >>> 6] &= ~(1L << i);
    }

    private void markDirty(int word) {
        if (dirtyCount == dirty.length) dirty = Arrays.copyOf(dirty, dirtyCount * 2);
        dirty[dirtyCount++] = word;
    }

    /** Bit index of a position, or -1 outside the box. */
    private int index(int x, int y, int z) {
        int dx = x - minX;
        int dz = z - minZ;
        int dy = y - minY;
        if (dx < 0 || dx >= SIDE || dz < 0 || dz >= SIDE || dy < 0 || dy >= height) return -1;
        return (dy * SIDE + dz) * SIDE + dx;
    }
}
