package games.brennan.dungeontrain.nap;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The Amber Gems each Respawn Obelisk in one level holds, 0..{@value #MAX}, keyed by the obelisk's
 * bottom block ({@link RespawnObelisks#bottom}). An obelisk offers its nap only when full
 * ({@link NapBedEvents#onObeliskUse}). Absent means none, so only obelisks a template stamped with
 * gems, or that a player has fed, take an entry; it is dropped when the obelisk is broken or blows up.
 *
 * <p>An obelisk has no block state to keep the count in, which is why it lives here.</p>
 */
public final class RespawnObeliskGems extends SavedData {

    public static final String NAME = "dungeontrain_obelisk_gems";
    /** Gems a full obelisk holds. */
    public static final int MAX = 6;
    private static final String TAG_POSITIONS = "positions";
    private static final String TAG_GEMS = "gems";

    private final Long2IntOpenHashMap gems;

    RespawnObeliskGems() {
        this(new Long2IntOpenHashMap());
    }

    private RespawnObeliskGems(Long2IntOpenHashMap gems) {
        this.gems = gems;
    }

    public static RespawnObeliskGems get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RespawnObeliskGems::new, (tag, registries) -> load(tag)), NAME);
    }

    static RespawnObeliskGems load(CompoundTag tag) {
        long[] positions = tag.getLongArray(TAG_POSITIONS);
        int[] counts = tag.getIntArray(TAG_GEMS);
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        for (int i = 0; i < Math.min(positions.length, counts.length); i++) {
            int n = clamp(counts[i]);
            if (n > 0) map.put(positions[i], n);
        }
        return new RespawnObeliskGems(map);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] positions = new long[gems.size()];
        int[] counts = new int[gems.size()];
        int i = 0;
        for (Long2IntOpenHashMap.Entry e : gems.long2IntEntrySet()) {
            positions[i] = e.getLongKey();
            counts[i] = e.getIntValue();
            i++;
        }
        tag.putLongArray(TAG_POSITIONS, positions);
        tag.putIntArray(TAG_GEMS, counts);
        return tag;
    }

    /** Gems the obelisk whose bottom block is {@code bottom} holds. */
    public int get(BlockPos bottom) {
        return gems.getOrDefault(bottom.asLong(), 0);
    }

    /** Sets the count, clamped to 0..{@value #MAX}; 0 drops the entry. */
    public void set(BlockPos bottom, int count) {
        int n = clamp(count);
        long key = bottom.asLong();
        int before = gems.getOrDefault(key, 0);
        if (before == n) return;
        if (n == 0) gems.remove(key);
        else gems.put(key, n);
        setDirty();
    }

    /** Puts up to {@code offered} gems in, never past {@value #MAX}; returns how many went in. */
    public int add(BlockPos bottom, int offered) {
        int taken = accepts(get(bottom), offered);
        if (taken > 0) set(bottom, get(bottom) + taken);
        return taken;
    }

    /** Forgets the obelisk whose bottom block is {@code bottom}. */
    public void forget(BlockPos bottom) {
        if (gems.containsKey(bottom.asLong())) {
            gems.remove(bottom.asLong());
            setDirty();
        }
    }

    /** Gems an obelisk holding {@code held} takes from {@code offered}. Pure, for tests. */
    static int accepts(int held, int offered) {
        return Math.max(0, Math.min(offered, MAX - clamp(held)));
    }

    private static int clamp(int count) {
        return Math.max(0, Math.min(MAX, count));
    }
}
