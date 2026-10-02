package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded, thread-safe memo of a value that depends only on a block column {@code (x, z)}.
 *
 * <p>Backs {@code EndIslandDensityFunctionMixin}: vanilla's {@code end_islands} erosion is pure 2D yet
 * evaluated per quart by every End biome source (1 024 identical calls for a 256-tall chunk) and per noise
 * cell corner by the terrain interpolator (~825 calls for ~25 columns), at 625 simplex evaluations each.
 * Profiled at ~70 % of an End-band sample (JFR, 2026-10-01). Past {@code cap} entries the memo is dropped
 * and refilled; correctness never depends on a hit.</p>
 */
public final class ColumnMemo {

    /** Entries kept before the memo is dropped — many chunks' worth of columns. */
    public static final int DEFAULT_CAP = 1 << 16;

    private final int cap;
    private final ConcurrentHashMap<Long, Double> memo = new ConcurrentHashMap<>();

    public ColumnMemo() {
        this(DEFAULT_CAP);
    }

    public ColumnMemo(int cap) {
        this.cap = Math.max(1, cap);
    }

    /** The remembered value for column {@code (x, z)}, or {@code null}. */
    public Double get(int x, int z) {
        return memo.get(ChunkPos.asLong(x, z));
    }

    /** Remember {@code value} for column {@code (x, z)}; drops everything first when the memo is full. */
    public void put(int x, int z, double value) {
        if (memo.size() >= cap) memo.clear();
        memo.put(ChunkPos.asLong(x, z), value);
    }

    public int size() {
        return memo.size();
    }
}
