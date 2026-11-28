package games.brennan.dungeontrain.perks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The world's side of {@link FirstAppleBoost}, kept in the overworld's data storage: whether a chest holding
 * a first-find apple is open and waiting ({@link Pending}), every container an extra apple was put in (so
 * opening one can clear the rest), and the players who have taken one in this world (a test world never
 * writes the profile flag, so this is what ends the boost there). Server thread only.
 */
public final class FirstAppleState extends SavedData {

    public static final String NAME = "dungeontrain_first_apple";
    private static final String TAG_PENDING = "Pending";
    private static final String TAG_DIM = "Dim";
    private static final String TAG_POS = "Pos";
    private static final String TAG_PIDX = "PIdx";
    private static final String TAG_GROUP = "Group";
    private static final String TAG_OPENER = "Opener";
    private static final String TAG_COUNT = "Count";
    private static final String TAG_MARKED = "Marked";
    private static final String TAG_FOUND = "Found";
    private static final String TAG_CARRIED = "Carried";

    /** A container somewhere in a dimension. */
    public record Spot(ResourceKey<Level> dim, BlockPos pos) {}

    /**
     * The chest opened with an apple in it: where, the carriage it sits in, its group's size, who opened it
     * and how many apples it held then (fewer later = one was taken).
     */
    public record Pending(Spot spot, int pIdx, int groupSize, UUID opener, int applesAtOpen) {}

    private Pending pending;
    private final Set<Spot> marked = new LinkedHashSet<>();
    private final Set<UUID> found = new HashSet<>();
    /** Profile flags read this session (not saved): keeps the perk file off the generation path. */
    private final java.util.Map<UUID, Boolean> profileFound = new java.util.HashMap<>();
    /** Extras owed by groups that had nowhere to put them, for the next group made. */
    private int carried;
    /** What {@link FirstAppleBoost#owed} said last check (not saved: a restart counts as a fresh start). */
    private FirstAppleBoost.Owed lastOwed;
    /** Groups the fill has been through this session (not saved). */
    private final Set<String> backfilled = new HashSet<>();

    private FirstAppleState() {}

    public static FirstAppleState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(FirstAppleState::new, (tag, registries) -> load(tag)), NAME);
    }

    public Pending pending() {
        return pending;
    }

    public void setPending(Pending next) {
        pending = next;
        setDirty();
    }

    public Set<Spot> marked() {
        return Set.copyOf(marked);
    }

    public void addMarked(Spot spot) {
        if (marked.add(spot)) setDirty();
    }

    public void removeMarked(Spot spot) {
        if (marked.remove(spot)) setDirty();
    }

    public int carried() {
        return carried;
    }

    public void setCarried(int next) {
        if (next == carried) return;
        carried = Math.max(0, next);
        setDirty();
    }

    FirstAppleBoost.Owed lastOwed() {
        return lastOwed;
    }

    void setLastOwed(FirstAppleBoost.Owed owed) {
        lastOwed = owed;
    }

    /** True the first time {@code group} is asked about this session. */
    boolean markBackfilled(String group) {
        return backfilled.add(group);
    }

    public boolean foundHere(UUID player) {
        return found.contains(player);
    }

    public void addFound(UUID player) {
        profileFound.put(player, true);
        if (found.add(player)) setDirty();
    }

    /** The profile's flag for {@code player}, read once per session through {@code read}. */
    boolean profileFound(UUID player, java.util.function.Predicate<UUID> read) {
        return profileFound.computeIfAbsent(player, read::test);
    }

    static FirstAppleState load(CompoundTag tag) {
        FirstAppleState data = new FirstAppleState();
        if (tag.contains(TAG_PENDING, Tag.TAG_COMPOUND)) {
            CompoundTag p = tag.getCompound(TAG_PENDING);
            Spot spot = readSpot(p);
            if (spot != null && p.hasUUID(TAG_OPENER)) {
                data.pending = new Pending(spot, p.getInt(TAG_PIDX), p.getInt(TAG_GROUP),
                    p.getUUID(TAG_OPENER), p.getInt(TAG_COUNT));
            }
        }
        ListTag list = tag.getList(TAG_MARKED, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Spot spot = readSpot(list.getCompound(i));
            if (spot != null) data.marked.add(spot);
        }
        ListTag foundList = tag.getList(TAG_FOUND, Tag.TAG_INT_ARRAY);
        for (Tag t : foundList) data.found.add(NbtUtils.loadUUID(t));
        data.carried = Math.max(0, tag.getInt(TAG_CARRIED));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (pending != null) {
            CompoundTag p = writeSpot(pending.spot());
            p.putInt(TAG_PIDX, pending.pIdx());
            p.putInt(TAG_GROUP, pending.groupSize());
            p.putUUID(TAG_OPENER, pending.opener());
            p.putInt(TAG_COUNT, pending.applesAtOpen());
            tag.put(TAG_PENDING, p);
        }
        ListTag list = new ListTag();
        for (Spot spot : marked) list.add(writeSpot(spot));
        tag.put(TAG_MARKED, list);
        ListTag foundList = new ListTag();
        for (UUID id : found) foundList.add(NbtUtils.createUUID(id));
        tag.put(TAG_FOUND, foundList);
        tag.putInt(TAG_CARRIED, carried);
        return tag;
    }

    private static Spot readSpot(CompoundTag tag) {
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString(TAG_DIM));
        if (dim == null || !tag.contains(TAG_POS, Tag.TAG_LONG)) return null;
        return new Spot(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(tag.getLong(TAG_POS)));
    }

    private static CompoundTag writeSpot(Spot spot) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIM, spot.dim().location().toString());
        tag.putLong(TAG_POS, spot.pos().asLong());
        return tag;
    }
}
