package games.brennan.dungeontrain.tunnel;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-dimension record of which template group each tunnel was built from, at the chunk edges its
 * runs cross — the memory {@link TunnelRunGroups} reads so a tunnel generated one chunk at a time
 * stays one group from entrance to exit.
 *
 * <p>Keyed by chunk X alone: tunnels run along the X-axis track line and a column's qualification
 * never depends on chunk Z, so every chunk-Z row of the corridor must agree on one answer. Each
 * key holds the group token of the tunnel nearest that chunk's west edge and of the one nearest its
 * east edge, each with how many columns it stops short of the edge (see {@link TunnelRunGroups}).</p>
 *
 * <p>Saved at {@code <dim>/data/dungeontrain_tunnel_groups.dat} so a restart mid-tunnel does not
 * put a seam in it. Worldgen writes from parallel worker threads, so every access is
 * {@code synchronized} — see {@link TunnelRunGroups#resolve}, which holds this monitor across a
 * whole chunk's look-up-then-record.</p>
 */
public final class TunnelGroupData extends SavedData implements TunnelRunGroups.EdgeBook {

    public static final String NAME = "dungeontrain_tunnel_groups";
    private static final String TAG_EDGES = "edges";
    private static final String TAG_X = "x";
    private static final String TAG_WEST = "w";
    private static final String TAG_EAST = "e";
    private static final String TAG_WEST_GAP = "wg";
    private static final String TAG_EAST_GAP = "eg";

    private record Edges(TunnelRunGroups.Edge west, TunnelRunGroups.Edge east) {}

    private final Map<Integer, Edges> byChunkX = new HashMap<>();

    private TunnelGroupData() {}

    public static TunnelGroupData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(TunnelGroupData::new, (tag, registries) -> load(tag)),
            NAME
        );
    }

    @Override
    public synchronized TunnelRunGroups.Edge westOf(int chunkX) {
        Edges e = byChunkX.get(chunkX);
        return e == null ? null : e.west();
    }

    @Override
    public synchronized TunnelRunGroups.Edge eastOf(int chunkX) {
        Edges e = byChunkX.get(chunkX);
        return e == null ? null : e.east();
    }

    /** First answer wins: an edge already recorded is never overwritten. */
    @Override
    public synchronized void record(int chunkX, TunnelRunGroups.Edge west, TunnelRunGroups.Edge east) {
        Edges prev = byChunkX.get(chunkX);
        TunnelRunGroups.Edge w = prev != null && prev.west() != null ? prev.west() : west;
        TunnelRunGroups.Edge e = prev != null && prev.east() != null ? prev.east() : east;
        if (w == null && e == null) return;
        Edges next = new Edges(w, e);
        if (next.equals(prev)) return;
        byChunkX.put(chunkX, next);
        setDirty();
    }

    static TunnelGroupData load(CompoundTag tag) {
        TunnelGroupData data = new TunnelGroupData();
        ListTag list = tag.getList(TAG_EDGES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            TunnelRunGroups.Edge w = e.contains(TAG_WEST)
                ? new TunnelRunGroups.Edge(e.getString(TAG_WEST), e.getInt(TAG_WEST_GAP)) : null;
            TunnelRunGroups.Edge east = e.contains(TAG_EAST)
                ? new TunnelRunGroups.Edge(e.getString(TAG_EAST), e.getInt(TAG_EAST_GAP)) : null;
            data.byChunkX.put(e.getInt(TAG_X), new Edges(w, east));
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Integer, Edges> en : byChunkX.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putInt(TAG_X, en.getKey());
            TunnelRunGroups.Edge w = en.getValue().west();
            TunnelRunGroups.Edge east = en.getValue().east();
            if (w != null) {
                e.putString(TAG_WEST, w.token());
                e.putInt(TAG_WEST_GAP, w.gap());
            }
            if (east != null) {
                e.putString(TAG_EAST, east.token());
                e.putInt(TAG_EAST_GAP, east.gap());
            }
            list.add(e);
        }
        tag.put(TAG_EDGES, list);
        return tag;
    }
}
