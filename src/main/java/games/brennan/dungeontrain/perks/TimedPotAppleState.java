package games.brennan.dungeontrain.perks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world's side of {@link TimedPotApple}, kept in the overworld's data storage so it lasts one life and
 * survives a relog: per player, the train time towards the next apple, the apples owed, how many were given,
 * and every pot they have looked at. Server thread only.
 */
public final class TimedPotAppleState extends SavedData {

    public static final String NAME = "dungeontrain_timed_pot_apple";
    private static final String TAG_PLAYERS = "Players";
    private static final String TAG_ID = "Id";
    private static final String TAG_TICKS = "Ticks";
    private static final String TAG_OWED = "Owed";
    private static final String TAG_GIVEN = "Given";
    private static final String TAG_SEEN = "Seen";
    private static final String TAG_DIM = "Dim";
    private static final String TAG_POS = "Pos";

    /** One player's progress. */
    static final class Entry {
        long ticks;
        int owed;
        int given;
        final Set<FirstAppleState.Spot> seen = new LinkedHashSet<>();
    }

    private final Map<UUID, Entry> players = new HashMap<>();

    private TimedPotAppleState() {}

    public static TimedPotAppleState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(TimedPotAppleState::new, (tag, registries) -> load(tag)), NAME);
    }

    Entry entry(UUID player) {
        return players.computeIfAbsent(player, id -> new Entry());
    }

    /** Store {@code progress} for {@code player}; true when it owes one more apple than before. */
    boolean setProgress(UUID player, TimedPotApple.Progress progress) {
        Entry e = entry(player);
        boolean more = progress.owed() > e.owed;
        e.ticks = progress.ticks();
        e.owed = progress.owed();
        setDirty();
        return more;
    }

    void markSeen(UUID player, FirstAppleState.Spot spot) {
        if (entry(player).seen.add(spot)) setDirty();
    }

    void delivered(UUID player) {
        Entry e = entry(player);
        e.owed = Math.max(0, e.owed - 1);
        e.given++;
        setDirty();
    }

    static TimedPotAppleState load(CompoundTag tag) {
        TimedPotAppleState data = new TimedPotAppleState();
        ListTag list = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag p = list.getCompound(i);
            if (!p.hasUUID(TAG_ID)) continue;
            Entry e = data.entry(p.getUUID(TAG_ID));
            e.ticks = Math.max(0, p.getLong(TAG_TICKS));
            e.owed = Math.max(0, Math.min(TimedPotApple.MAX_OWED, p.getInt(TAG_OWED)));
            e.given = Math.max(0, p.getInt(TAG_GIVEN));
            ListTag seen = p.getList(TAG_SEEN, Tag.TAG_COMPOUND);
            for (int j = 0; j < seen.size(); j++) {
                CompoundTag s = seen.getCompound(j);
                ResourceLocation dim = ResourceLocation.tryParse(s.getString(TAG_DIM));
                if (dim == null || !s.contains(TAG_POS, Tag.TAG_LONG)) continue;
                e.seen.add(new FirstAppleState.Spot(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(s.getLong(TAG_POS))));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> me : players.entrySet()) {
            Entry e = me.getValue();
            CompoundTag p = new CompoundTag();
            p.putUUID(TAG_ID, me.getKey());
            p.putLong(TAG_TICKS, e.ticks);
            p.putInt(TAG_OWED, e.owed);
            p.putInt(TAG_GIVEN, e.given);
            ListTag seen = new ListTag();
            for (FirstAppleState.Spot spot : e.seen) {
                CompoundTag s = new CompoundTag();
                s.putString(TAG_DIM, spot.dim().location().toString());
                s.putLong(TAG_POS, spot.pos().asLong());
                seen.add(s);
            }
            p.put(TAG_SEEN, seen);
            list.add(p);
        }
        tag.put(TAG_PLAYERS, list);
        return tag;
    }
}
