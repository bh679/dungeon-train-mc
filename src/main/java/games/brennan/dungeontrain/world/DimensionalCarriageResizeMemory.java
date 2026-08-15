package games.brennan.dungeontrain.world;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.DimensionalCarriageResize;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a dimensional-carriage shrink took away, kept so that growing back puts it there again.
 *
 * <p>Shrinking a room used to be one-way. The block layer clipped the template to the new box
 * ({@code PortalCarriageBuilder.clipTo}) and the variant sidecar dropped every cell that fell out of
 * bounds on its next parse, so a stepper tapped one block too far cost the author that row for good —
 * stepping straight back up returned the built-in shell. Each shrink files the row it removed here
 * instead, and the grow that undoes it takes the row back out.</p>
 *
 * <p><b>Keyed by size, not by history.</b> {@link DimensionalCarriageResize} makes grow and shrink exact
 * inverses: the step between {@code s} and {@code s + 1} always moves the same face. So a slab is
 * filed under the smaller of the two sizes it sits between, and the matching grow finds it by that
 * number alone — no ordering to replay, nothing to reconcile after a restart, and a room resized
 * along two axes cannot confuse one axis's slabs for another's.</p>
 *
 * <p>Per world, at {@code <world>/data/dungeontrain_dimensional_carriage_resize.dat}, because the sizes it is
 * keyed against are this world's: {@code DimensionalCarriageLayout.minWidth} follows the world's carriage
 * dims, so the same room in a world with wider carriages counts its steps from a different floor.</p>
 *
 * <p>Records are dropped when the room is saved, cleared, or deleted — see
 * {@link #forget}. After a save the new size is the authored one, and a stale slab would put back
 * blocks the author had deliberately stepped away from.</p>
 */
public final class DimensionalCarriageResizeMemory extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAME = "dungeontrain_dimensional_carriage_resize";

    private static final String TAG_SLABS = "slabs";
    private static final String TAG_ROOM = "room";
    private static final String TAG_AXIS = "axis";
    private static final String TAG_SIZE = "size";
    private static final String TAG_BLOCKS = "blocks";
    private static final String TAG_VARIANTS = "variants";
    private static final String TAG_CONTENTS = "contents";

    /**
     * One cropped row.
     *
     * @param blocks      the row as a 1-thick {@code StructureTemplate}, saved with
     *                    {@code StructureTemplate#save} — the same NBT any other template round-trips
     *                    through, so block entities (a chest and its stacks) come back with it
     * @param variantsJson the variant sidecar cells that stood in the row, in the sidecar's own JSON
     *                    text; empty when there were none
     * @param contentsJson the container-contents pools and prefab links at those cells, in the
     *                    contents store's own JSON text; empty when there were none
     */
    public record Slab(CompoundTag blocks, String variantsJson, String contentsJson) {

        public Slab {
            if (blocks == null) throw new IllegalArgumentException("null blocks");
            if (variantsJson == null) variantsJson = "";
            if (contentsJson == null) contentsJson = "";
        }
    }

    /** Keyed by {@code room}, then by {@code axis|size}. Nested so {@link #forget} is one removal. */
    private final Map<String, Map<String, Slab>> slabs = new LinkedHashMap<>();

    /** Package-private rather than private so the round-trip is testable without a live level. */
    DimensionalCarriageResizeMemory() {}

    public static DimensionalCarriageResizeMemory get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                DimensionalCarriageResizeMemory::new,
                (tag, registries) -> load(tag)
            ),
            NAME
        );
    }

    private static String key(DimensionalCarriageResize.Axis axis, int size) {
        return axis.name() + "|" + size;
    }

    /** File the row a shrink from {@code size + 1} to {@code size} removed. */
    public synchronized void put(String room, DimensionalCarriageResize.Axis axis, int size, Slab slab) {
        if (room == null || axis == null || slab == null) return;
        slabs.computeIfAbsent(room, r -> new LinkedHashMap<>()).put(key(axis, size), slab);
        setDirty();
    }

    /**
     * Take back the row filed for a grow from {@code size} to {@code size + 1}, or null if this
     * room has never been shrunk past that point in this world.
     *
     * <p>Removing rather than reading: the row is about to be put back into the world, and leaving
     * the record behind would let a later grow stamp a stale copy over whatever the author built in
     * that space in the meantime.</p>
     */
    public synchronized Slab take(String room, DimensionalCarriageResize.Axis axis, int size) {
        Map<String, Slab> forRoom = slabs.get(room);
        if (forRoom == null) return null;
        Slab found = forRoom.remove(key(axis, size));
        if (found == null) return null;
        if (forRoom.isEmpty()) slabs.remove(room);
        setDirty();
        return found;
    }

    /** Drop every record for {@code room}. Returns how many there were. */
    public synchronized int forget(String room) {
        Map<String, Slab> forRoom = slabs.remove(room);
        if (forRoom == null || forRoom.isEmpty()) return 0;
        setDirty();
        LOGGER.debug("[DungeonTrain] Dimensional carriage resize memory: dropped {} slab(s) for '{}'",
            forRoom.size(), room);
        return forRoom.size();
    }

    /** How many rows are currently remembered for {@code room}. */
    public synchronized int countFor(String room) {
        Map<String, Slab> forRoom = slabs.get(room);
        return forRoom == null ? 0 : forRoom.size();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Map<String, Slab>> room : slabs.entrySet()) {
            for (Map.Entry<String, Slab> e : room.getValue().entrySet()) {
                String[] parts = e.getKey().split("\\|", 2);
                if (parts.length != 2) continue;
                CompoundTag entry = new CompoundTag();
                entry.putString(TAG_ROOM, room.getKey());
                entry.putString(TAG_AXIS, parts[0]);
                entry.putInt(TAG_SIZE, Integer.parseInt(parts[1]));
                entry.put(TAG_BLOCKS, e.getValue().blocks());
                entry.putString(TAG_VARIANTS, e.getValue().variantsJson());
                entry.putString(TAG_CONTENTS, e.getValue().contentsJson());
                list.add(entry);
            }
        }
        tag.put(TAG_SLABS, list);
        return tag;
    }

    static DimensionalCarriageResizeMemory load(CompoundTag tag) {
        DimensionalCarriageResizeMemory data = new DimensionalCarriageResizeMemory();
        ListTag list = tag.getList(TAG_SLABS, Tag.TAG_COMPOUND);
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String room = entry.getString(TAG_ROOM);
            String axisName = entry.getString(TAG_AXIS);
            if (room.isEmpty() || axisName.isEmpty()) continue;
            DimensionalCarriageResize.Axis axis;
            try {
                axis = DimensionalCarriageResize.Axis.valueOf(axisName);
            } catch (IllegalArgumentException e) {
                // A record written by a build that named its axes differently. Dropping it costs
                // one restorable row; refusing to load would cost the whole file.
                skipped.add(room + "/" + axisName);
                continue;
            }
            data.slabs.computeIfAbsent(room, r -> new LinkedHashMap<>()).put(
                key(axis, entry.getInt(TAG_SIZE)),
                new Slab(entry.getCompound(TAG_BLOCKS),
                    entry.getString(TAG_VARIANTS),
                    entry.getString(TAG_CONTENTS)));
        }
        if (!skipped.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Dimensional carriage resize memory: dropped {} record(s) with an "
                + "unknown axis: {}", skipped.size(), skipped);
        }
        return data;
    }
}
