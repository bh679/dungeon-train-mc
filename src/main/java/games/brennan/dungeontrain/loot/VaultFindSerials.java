package games.brennan.dungeontrain.loot;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Numbers the relay has given vault finds that no stack carries yet, by claim id, on the overworld save
 * ({@code <world>/data/dungeontrain_vault_find_serials.dat}). Usually empty again within a second of
 * the answer; kept on disk so a find still out of reach (in a chest, its puller offline) gets its
 * number when it turns up in an inventory, even after a restart.
 */
public final class VaultFindSerials extends SavedData {

    public static final String NAME = "dungeontrain_vault_find_serials";
    /** Oldest numbers go past this, so finds that never turn up can't grow the save unbounded. */
    static final int MAX_PENDING = 1024;

    private static final String TAG_PENDING = "pending";
    private static final String TAG_CLAIM = "claim";
    private static final String TAG_SERIAL = "serial";

    private final LinkedHashMap<UUID, Integer> pending;

    private VaultFindSerials(LinkedHashMap<UUID, Integer> pending) {
        this.pending = pending;
    }

    public static VaultFindSerials get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(() -> new VaultFindSerials(new LinkedHashMap<>()), (tag, registries) -> load(tag)),
            NAME);
    }

    public void put(UUID claim, int serial) {
        pending.put(claim, serial);
        while (pending.size() > MAX_PENDING) pending.remove(pending.keySet().iterator().next());
        setDirty();
    }

    @Nullable
    public Integer peek(UUID claim) {
        return pending.get(claim);
    }

    public void remove(UUID claim) {
        if (pending.remove(claim) != null) setDirty();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** Package-private so the NBT round-trip can be tested without a live world save. */
    static VaultFindSerials load(CompoundTag tag) {
        LinkedHashMap<UUID, Integer> loaded = new LinkedHashMap<>();
        ListTag list = tag.getList(TAG_PENDING, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(TAG_CLAIM)) continue;     // a corrupt entry is skipped, not fatal
            int serial = entry.getInt(TAG_SERIAL);
            if (serial > 0) loaded.put(entry.getUUID(TAG_CLAIM), serial);
        }
        return new VaultFindSerials(loaded);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Integer> e : pending.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_CLAIM, e.getKey());
            entry.putInt(TAG_SERIAL, e.getValue());
            list.add(entry);
        }
        tag.put(TAG_PENDING, list);
        return tag;
    }
}
