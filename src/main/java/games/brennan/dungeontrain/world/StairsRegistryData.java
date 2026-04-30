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
     * Atomic reserve + side assignment. Checks that no prior stairs AND no
     * recorded short pillar lies within {@code minSpacing} X of
     * {@code worldX}. If clear, records {@code worldX} with an alternated
     * side and returns it; otherwise returns {@code null} and the caller
     * skips placement.
     */
    public synchronized Boolean tryReserveStairs(int worldX, int minSpacing) {
        Integer prevStairsX = stairs.floorKey(worldX);
        if (prevStairsX != null && worldX - prevStairsX < minSpacing) return null;
        Integer nextStairsX = stairs.ceilingKey(worldX);
        if (nextStairsX != null && nextStairsX - worldX < minSpacing) return null;

        Integer prevShortX = shortPillars.floor(worldX);
        if (prevShortX != null && worldX - prevShortX < minSpacing) return null;
        Integer nextShortX = shortPillars.ceiling(worldX);
        if (nextShortX != null && nextShortX - worldX < minSpacing) return null;

        // Side alternation — pick side opposite the closest prior stairs.
        // Falls back to ceiling, then to false (= +Z) for the very first
        // stairs in a fresh world.
        boolean newSide;
        if (prevStairsX != null) newSide = !stairs.get(prevStairsX);
        else if (nextStairsX != null) newSide = !stairs.get(nextStairsX);
        else newSide = false;

        stairs.put(worldX, newSide);
        setDirty();
        return newSide;
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
