package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A room saved before the rename keeps its cassette. */
class TributeRoomLegacyTest {

    @Test
    @DisplayName("The old cassette id becomes the new one wherever it sits in the snapshot")
    void cassetteIdMigrates() {
        CompoundTag item = new CompoundTag();
        item.putString("id", "dungeontrain:home_cassette");
        item.putInt("count", 1);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag be = new CompoundTag();
        be.put("Items", items);
        be.putString("id", "vista:tv");
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("be", be);
        snapshot.putString("name", "dungeontrain:home_cassette");

        CompoundTag out = TributeRoomLegacy.migrate(snapshot);

        assertEquals("dungeontrain:tribute_cassette", out.getString("name"));
        assertEquals("dungeontrain:tribute_cassette", out.getCompound("be").getList("Items", 10).getCompound(0).getString("id"));
        assertEquals("vista:tv", out.getCompound("be").getString("id"));
        assertEquals(1, out.getCompound("be").getList("Items", 10).getCompound(0).getInt("count"));
        // The input is untouched.
        assertEquals("dungeontrain:home_cassette", snapshot.getString("name"));
    }

    @Test
    @DisplayName("A snapshot with nothing old comes back equal")
    void cleanSnapshotUnchanged() {
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("s", StringTag.valueOf("dungeontrain:tribute_cassette"));
        assertEquals(snapshot, TributeRoomLegacy.migrate(snapshot));
    }
}
