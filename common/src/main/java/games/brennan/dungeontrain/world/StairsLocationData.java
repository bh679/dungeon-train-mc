package games.brennan.dungeontrain.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Per-world spatial index of the stair structures worldgen stamps along the
 * track corridor — bridge-pillar side stairs ({@link Kind#PILLAR_STAIRS}) and
 * tunnel stairwells ({@link Kind#TUNNEL_STAIRS}, recorded as the surface
 * entrance pavilion plus its descending shaft). Drives the
 * {@code used_pillar_stairs} / {@code used_tunnel_stairs} advancements:
 * {@code StairsUsageEvents} queries the boxes near each player and fires the
 * matching marker when one contains them.
 *
 * <p>This is separate from {@link StairsRegistryData} on purpose — that one is
 * a worldgen spacing/alternation aid keyed by {@code worldX}+side only (no Y,
 * no kind), which can't tell up-stairs from down-stairs. This one records the
 * exact placed footprint at the single site where it's computed.</p>
 *
 * <p>Stored <em>per dimension</em> (recorded against the level being generated,
 * queried against the ticking level) at
 * {@code <dim>/data/dungeontrain_stairs_locations.dat}, so stairs at the same X
 * in different dimensions never cross-fire. (Contrast {@link StairsRegistryData},
 * which is deliberately overworld-global because it only coordinates spacing.)
 * Boxes are keyed by their {@code minX} in a {@link NavigableMap} so the
 * per-tick scan can prune to a small X window around the player (placements are
 * &gt;= 100 blocks apart and at most 5 wide, so a box containing the player
 * always has its {@code minX} within a few blocks of the player).</p>
 *
 * <p>Concurrent worldgen safety: feature placement runs on parallel worker
 * threads, so {@link #record} is {@code synchronized}; reads snapshot under the
 * same lock.</p>
 *
 * <p>Known limitation: only chunks generated after this ships are indexed.
 * Stairs in pre-existing chunks aren't retro-recorded — acceptable since the
 * train continually generates fresh corridor ahead of itself in normal play.</p>
 */
public final class StairsLocationData extends SavedData {

    public static final String NAME = "dungeontrain_stairs_locations";
    private static final String TAG_BOXES = "boxes";
    private static final String TAG_MIN_X = "minX";
    private static final String TAG_MIN_Y = "minY";
    private static final String TAG_MIN_Z = "minZ";
    private static final String TAG_MAX_X = "maxX";
    private static final String TAG_MAX_Y = "maxY";
    private static final String TAG_MAX_Z = "maxZ";
    private static final String TAG_KIND = "kind";

    /** Which stair structure a recorded box belongs to. */
    public enum Kind {
        PILLAR_STAIRS,
        TUNNEL_STAIRS
    }

    /**
     * An axis-aligned world box (inclusive block bounds) tagged with the stair
     * structure it belongs to. {@link #contains} is padded slightly so a player
     * walking the steps reliably reads as inside.
     */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Kind kind) {
        public boolean contains(double x, double y, double z, double pad) {
            return x >= minX - pad && x <= maxX + 1 + pad
                && y >= minY - pad && y <= maxY + 1 + pad
                && z >= minZ - pad && z <= maxZ + 1 + pad;
        }
    }

    /** minX -> boxes whose left edge sits at that X. */
    private final NavigableMap<Integer, List<Box>> byMinX = new TreeMap<>();

    private StairsLocationData() {}

    public static StairsLocationData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                StairsLocationData::new,
                (tag, registries) -> load(tag),
                null
            ),
            NAME
        );
    }

    /** Record a placed stair box. Idempotent on exact-duplicate boxes. */
    public synchronized void record(Box box) {
        List<Box> bucket = byMinX.computeIfAbsent(box.minX(), k -> new ArrayList<>(1));
        if (!bucket.contains(box)) {
            bucket.add(box);
            setDirty();
        }
    }

    /**
     * Snapshot of every recorded box whose {@code minX} lies within
     * {@code window} blocks of {@code worldX}. The returned list is a fresh
     * copy, safe to iterate off-lock.
     */
    public synchronized List<Box> near(int worldX, int window) {
        List<Box> out = new ArrayList<>();
        for (List<Box> bucket : byMinX.subMap(worldX - window, true, worldX + window, true).values()) {
            out.addAll(bucket);
        }
        return out;
    }

    static StairsLocationData load(CompoundTag tag) {
        StairsLocationData data = new StairsLocationData();
        if (tag.contains(TAG_BOXES)) {
            ListTag list = tag.getList(TAG_BOXES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                Kind kind = kindFromName(e.getString(TAG_KIND));
                if (kind == null) continue;
                Box box = new Box(
                    e.getInt(TAG_MIN_X), e.getInt(TAG_MIN_Y), e.getInt(TAG_MIN_Z),
                    e.getInt(TAG_MAX_X), e.getInt(TAG_MAX_Y), e.getInt(TAG_MAX_Z),
                    kind
                );
                data.byMinX.computeIfAbsent(box.minX(), k -> new ArrayList<>(1)).add(box);
            }
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (List<Box> bucket : byMinX.values()) {
            for (Box box : bucket) {
                CompoundTag e = new CompoundTag();
                e.putInt(TAG_MIN_X, box.minX());
                e.putInt(TAG_MIN_Y, box.minY());
                e.putInt(TAG_MIN_Z, box.minZ());
                e.putInt(TAG_MAX_X, box.maxX());
                e.putInt(TAG_MAX_Y, box.maxY());
                e.putInt(TAG_MAX_Z, box.maxZ());
                e.putString(TAG_KIND, box.kind().name());
                list.add(e);
            }
        }
        tag.put(TAG_BOXES, list);
        return tag;
    }

    private static Kind kindFromName(String name) {
        for (Kind k : Kind.values()) {
            if (k.name().equals(name)) return k;
        }
        return null;
    }
}
