package games.brennan.dungeontrain.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pets waiting for an owner who was offline when their carriage went away, on the overworld save
 * ({@code <world>/data/dungeontrain_pending_pets.dat}).
 *
 * <p>A carriage deleted for good takes its despawn snapshot with it, and every server start rebuilds
 * the train before anyone has joined — so a pet frozen in a snapshot often has no owner online to be
 * handed to. It waits here, entity NBT and all, and {@code PendingPetsEvents} respawns it beside the
 * owner once they are back in the world.</p>
 */
public final class PendingPets extends SavedData {

    public static final String NAME = "dungeontrain_pending_pets";
    /** Per owner, so a world full of abandoned pets can't grow the save unbounded. */
    static final int MAX_PER_OWNER = 32;

    private static final String TAG_OWNERS = "owners";
    private static final String TAG_OWNER = "owner";
    private static final String TAG_PETS = "pets";

    private final Map<UUID, List<CompoundTag>> byOwner;

    private PendingPets(Map<UUID, List<CompoundTag>> byOwner) {
        this.byOwner = byOwner;
    }

    public static PendingPets get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(() -> new PendingPets(new HashMap<>()), (tag, registries) -> load(tag)),
            NAME);
    }

    /** Hold {@code pet} until {@code owner} is back; the oldest goes past the per-owner cap. */
    public void add(UUID owner, CompoundTag pet) {
        List<CompoundTag> pets = byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
        pets.add(pet.copy());
        while (pets.size() > MAX_PER_OWNER) pets.remove(0);
        setDirty();
    }

    public boolean has(UUID owner) {
        return byOwner.containsKey(owner);
    }

    /** Remove and return every pet waiting for {@code owner}; empty when none. */
    public List<CompoundTag> take(UUID owner) {
        List<CompoundTag> pets = byOwner.remove(owner);
        if (pets == null) return List.of();
        setDirty();
        return pets;
    }

    /** Package-private so the NBT round-trip can be tested without a live world save. */
    static PendingPets load(CompoundTag tag) {
        Map<UUID, List<CompoundTag>> loaded = new HashMap<>();
        ListTag owners = tag.getList(TAG_OWNERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < owners.size(); i++) {
            CompoundTag entry = owners.getCompound(i);
            if (!entry.hasUUID(TAG_OWNER)) continue; // a corrupt entry is skipped, not fatal
            ListTag pets = entry.getList(TAG_PETS, Tag.TAG_COMPOUND);
            List<CompoundTag> list = new ArrayList<>(pets.size());
            for (int j = 0; j < pets.size(); j++) list.add(pets.getCompound(j));
            if (!list.isEmpty()) loaded.put(entry.getUUID(TAG_OWNER), list);
        }
        return new PendingPets(loaded);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag owners = new ListTag();
        byOwner.forEach((owner, pets) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_OWNER, owner);
            ListTag list = new ListTag();
            list.addAll(pets);
            entry.put(TAG_PETS, list);
            owners.add(entry);
        });
        tag.put(TAG_OWNERS, owners);
        return tag;
    }
}
