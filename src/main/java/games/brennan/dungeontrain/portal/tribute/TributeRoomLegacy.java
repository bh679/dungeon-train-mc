package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Map;

/**
 * Ids a saved tribute room may still carry from before a rename, rewritten as the room loads so
 * the blocks and items in it keep resolving. A room is saved under the player's own data directory
 * and outlives every build, so an old id in one would otherwise come up as nothing: the TV's
 * cassette slot went empty when the Home Cassette became the Tribute Cassette, and an empty slot
 * is a TV on whatever channel it finds.
 */
public final class TributeRoomLegacy {

    /** Old id → current id, for every string anywhere in a room snapshot. */
    static final Map<String, String> IDS = Map.of(
        "dungeontrain:home_cassette", "dungeontrain:tribute_cassette");

    private TributeRoomLegacy() {}

    /** {@code tag} with every legacy id replaced, as a new tree; the given one is left alone. */
    public static CompoundTag migrate(CompoundTag tag) {
        return (CompoundTag) migrateTag(tag);
    }

    private static Tag migrateTag(Tag tag) {
        if (tag instanceof StringTag s) {
            String replacement = IDS.get(s.getAsString());
            return replacement == null ? s : StringTag.valueOf(replacement);
        }
        if (tag instanceof CompoundTag c) {
            CompoundTag out = new CompoundTag();
            for (String key : c.getAllKeys()) out.put(key, migrateTag(c.get(key)));
            return out;
        }
        if (tag instanceof ListTag l) {
            ListTag out = new ListTag();
            for (Tag t : l) out.add(migrateTag(t));
            return out;
        }
        return tag.copy();
    }
}
