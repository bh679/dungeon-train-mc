package games.brennan.dungeontrain.worldgen;

import java.util.BitSet;

/**
 * Which cells of one chunk the End-band sampler wrote during that chunk's own worldgen
 * ({@code EndBandInlineTerrain}), so the void erosion that runs when the chunk goes live
 * ({@code WorldDisintegrationEvents}) leaves exactly those cells alone and erodes everything else the
 * way it always has. Chunk-local {@code (dx, y, dz)}; {@code y} is world Y.
 *
 * <p>Two sentinels: {@link #NONE} (nothing sampled — erode as before) and {@link #ALL} (the record was
 * lost, e.g. the chunk was saved mid-generation and promoted on a later boot — skip the chunk rather than
 * erase its islands). Instances are mutated only by their builder while the sample is being written;
 * afterwards they are read-only.</p>
 */
public final class SampledCells {

    /** No sampled cells: erosion treats the chunk as it always did. */
    public static final SampledCells NONE = new SampledCells(0, 0, false);
    /** Every cell exempt: a record reloaded from disk, whose cell bits were never persisted. */
    public static final SampledCells ALL = new SampledCells(0, 0, true);

    private final int minY;
    private final int height;
    private final boolean all;
    private final BitSet bits;

    private SampledCells(int minY, int height, boolean all) {
        this.minY = minY;
        this.height = Math.max(0, height);
        this.all = all;
        this.bits = new BitSet(this.height * 256);
    }

    /** An empty record for a chunk spanning {@code [minY, minY + height)}. */
    public static SampledCells forChunk(int minY, int height) {
        return new SampledCells(minY, height, false);
    }

    private int index(int dx, int y, int dz) {
        int ly = y - minY;
        if (dx < 0 || dx > 15 || dz < 0 || dz > 15 || ly < 0 || ly >= height) return -1;
        return (ly << 8) | (dz << 4) | dx;
    }

    /** Record that the sampler wrote {@code (dx, y, dz)}. Out-of-range cells are ignored. */
    public void mark(int dx, int y, int dz) {
        int i = index(dx, y, dz);
        if (i >= 0) bits.set(i);
    }

    /** Whether the void erosion must leave {@code (dx, y, dz)} alone. */
    public boolean contains(int dx, int y, int dz) {
        if (all) return true;
        int i = index(dx, y, dz);
        return i >= 0 && bits.get(i);
    }

    /** True when nothing was recorded (and this is not {@link #ALL}). */
    public boolean isEmpty() {
        return !all && bits.isEmpty();
    }

    /** Number of recorded cells; {@code -1} for {@link #ALL}. */
    public int count() {
        return all ? -1 : bits.cardinality();
    }

    /** Whether this is the whole-chunk sentinel. */
    public boolean isAll() {
        return all;
    }

    @Override
    public String toString() {
        return all ? "SampledCells[ALL]" : "SampledCells[" + bits.cardinality() + " cells]";
    }
}
