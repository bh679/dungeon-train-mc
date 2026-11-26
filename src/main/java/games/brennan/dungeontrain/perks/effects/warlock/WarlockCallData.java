package games.brennan.dungeontrain.perks.effects.warlock;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Teleport Player's world-wide memory of PlayerMobs (on the overworld): which have died, so a player
 * who was away drops them from their list, and which were called away while unloaded, so the copy left
 * in its chunk vanishes when that chunk loads ({@link MetPlayerMobs#onJoin}). Each set keeps its newest
 * {@link #KEEP} ids.
 */
public final class WarlockCallData extends SavedData {

    private static final String NAME = "dungeontrain_warlock_calls";
    static final int KEEP = 1024;

    private final Set<UUID> dead = new LinkedHashSet<>();
    private final Set<UUID> calledAway = new LinkedHashSet<>();

    public static WarlockCallData get(ServerLevel anyLevel) {
        return anyLevel.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(WarlockCallData::new, (tag, registries) -> load(tag)), NAME);
    }

    boolean isDead(UUID id) {
        return dead.contains(id);
    }

    void markDead(UUID id) {
        if (add(dead, id)) setDirty();
    }

    void markCalledAway(UUID id) {
        if (add(calledAway, id)) setDirty();
    }

    /** True once, for the left-behind copy of a mob that was called away. */
    boolean takeCalledAway(UUID id) {
        if (!calledAway.remove(id)) return false;
        setDirty();
        return true;
    }

    private static boolean add(Set<UUID> set, UUID id) {
        if (!set.add(id)) return false;
        while (set.size() > KEEP) set.remove(set.iterator().next());
        return true;
    }

    private static WarlockCallData load(CompoundTag tag) {
        WarlockCallData data = new WarlockCallData();
        read(tag.getList("dead", Tag.TAG_INT_ARRAY), data.dead);
        read(tag.getList("calledAway", Tag.TAG_INT_ARRAY), data.calledAway);
        return data;
    }

    private static void read(ListTag list, Set<UUID> into) {
        for (Tag t : list) into.add(NbtUtils.loadUUID(t));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("dead", write(dead));
        tag.put("calledAway", write(calledAway));
        return tag;
    }

    private static ListTag write(Set<UUID> set) {
        ListTag list = new ListTag();
        for (UUID id : set) list.add(NbtUtils.createUUID(id));
        return list;
    }
}
