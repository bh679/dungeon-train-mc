package games.brennan.dungeontrain.train;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CarriageBlockSnapshotTest {

    @Test
    void encodeThenDecodeRoundTripsTheSnapshotNbt() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("v", 1);
        root.putInt("l", 9);
        root.putInt("h", 7);
        root.putInt("w", 7);
        ListTag cells = new ListTag();
        CompoundTag cell = new CompoundTag();
        cell.put("p", new IntArrayTag(new int[]{1, 2, 3}));
        cell.putString("marker", "chest");
        cells.add(cell);
        root.put("cells", cells);

        String b64 = CarriageBlockSnapshot.encode(root);
        assertNotNull(b64);
        assertFalse(b64.isEmpty());

        CompoundTag back = CarriageBlockSnapshot.decode(b64);
        assertEquals(9, back.getInt("l"));
        assertEquals(7, back.getInt("h"));
        ListTag backCells = back.getList("cells", Tag.TAG_COMPOUND);
        assertEquals(1, backCells.size());
        assertArrayEquals(new int[]{1, 2, 3}, backCells.getCompound(0).getIntArray("p"));
        assertEquals("chest", backCells.getCompound(0).getString("marker"));
    }
}
