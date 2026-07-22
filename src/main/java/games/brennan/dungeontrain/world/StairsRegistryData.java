package games.brennan.dungeontrain.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Per-world cross-chunk registry of pillar-side stairs placements + short
 * pillars (height &lt; 3) — needed because vanilla worldgen feature placement
 * only sees ±1 chunk during the FEATURES decoration step. Two chunks more
 * than that apart can't read each other's pillar terrain, which lets the
 * "stairs at most one per {@code MIN_STAIRS_SPACING} window" rule produce
 * close pairs at chunk seams.
 *
 * <p>This SavedData lives on the overworld at
 * {@code <world>/data/dungeontrain_stairs_registry.dat}. Worldgen consults
 * it via {@link #tryReserveStairs} which atomically checks the
 * {@code MIN_STAIRS_SPACING} gap rule against ALL prior stairs AND ALL
 * recorded short pillars, and on success records the new stairs with its
 * alternated side. Short pillars are recorded via {@link #recordShortPillar}
 * so a stairs candidate in a later chunk sees the short pillar even if
 * it's outside its read window.</p>
 *
 * <p>Concurrent worldgen safety: feature placement runs on parallel worker
 * threads. All public methods that mutate are {@code synchronized} so
 * reserve+place is atomic and reads see a consistent snapshot.</p>
 *
 * <p>Side alternation: side of new stairs = !side of nearest prior stairs.
 * "Nearest" is determined via {@link NavigableMap#floorKey} (left
 * neighbour) with {@link NavigableMap#ceilingKey} fallback when nothing
 * is to the left (the very first stairs placed in a fresh world).
 * Alternation across chunk seams is now exact because all chunks see the
 * same registry.</p>
 */
public final class StairsRegistryData extends SavedData {

    public static final String NAME = "dungeontrain_stairs_registry";
    private static final String TAG_STAIRS = "stairs";
    private static final String TAG_SHORT = "shortPillars";
    private static final String TAG_X = "x";
    private static final String TAG_SIDE = "side";

    /** worldX -&gt; flipped side. {@code true} = -Z (flipped), {@code false} = +Z. */
    private final NavigableMap<Integer, Boolean> stairs = new TreeMap<>();
    /** worldX of pillars with {@code height &lt; SHORT_PILLAR_THRESHOLD}. */
    private final NavigableSet<Integer> shortPillars = new TreeSet<>();

    private StairsRegistryData() {}

    public static StairsRegistryData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                StairsRegistryData::new,
                (tag, registries) -> load(tag)
            ),
            NAME
        );
    }

    /**
     * Whether any recorded stairs lies within {@code radius} blocks of {@code worldX} along X.
     * Used as a compat/no-double-placement guard by the deterministic anchor-grid placement in
     * {@code TrackGenerator}: in fresh worlds anchor-chosen positions are always ≥ 60 apart so
     * this never blocks; in pre-anchor worlds it defers to the racing-era entries persisted in
     * the save, so frontier chunks never place stairs next to legacy ones.
     */
    public synchronized boolean hasStairsWithin(int worldX, int radius) {
        Integer prev = stairs.floorKey(worldX);
        if (prev != null && worldX - prev < radius) return true;
        Integer next = stairs.ceilingKey(worldX);
        return next != null && next - worldX < radius;
    }

    /**
     * Record placed stairs at {@code worldX} with the given side ({@code true} = -Z). Unlike the
     * pre-determinism {@code tryReserveStairs}, this is a pure record — NOT an arbiter. Placement
     * decisions are made chunk-locally from the seed-phased anchor grid (order-independent by
     * construction); the registry persists what WAS placed for the compat guard above and any
     * runtime queries. Idempotent for the same X.
     */
    public synchronized void recordStairs(int worldX, boolean side) {
        Boolean prev = stairs.put(worldX, side);
        if (prev == null || prev != side) setDirty();
    }

    /**
     * Record a short pillar (height &lt; SHORT_PILLAR_THRESHOLD) at
     * {@code worldX} so future stairs reservations avoid it. Idempotent —
     * re-recording the same X is a no-op.
     */
    public synchronized void recordShortPillar(int worldX) {
        if (shortPillars.add(worldX)) setDirty();
    }

    private static StairsRegistryData load(CompoundTag tag) {
        StairsRegistryData data = new StairsRegistryData();
        if (tag.contains(TAG_STAIRS)) {
            ListTag list = tag.getList(TAG_STAIRS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                data.stairs.put(e.getInt(TAG_X), e.getBoolean(TAG_SIDE));
            }
        }
        if (tag.contains(TAG_SHORT)) {
            int[] arr = tag.getIntArray(TAG_SHORT);
            for (int x : arr) data.shortPillars.add(x);
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag stairsList = new ListTag();
        for (Map.Entry<Integer, Boolean> e : stairs.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(TAG_X, e.getKey());
            entry.putBoolean(TAG_SIDE, e.getValue());
            stairsList.add(entry);
        }
        tag.put(TAG_STAIRS, stairsList);

        int[] shortArr = new int[shortPillars.size()];
        int i = 0;
        for (int x : shortPillars) shortArr[i++] = x;
        tag.putIntArray(TAG_SHORT, shortArr);

        return tag;
    }
}
