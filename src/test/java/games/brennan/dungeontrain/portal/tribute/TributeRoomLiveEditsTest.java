package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomLiveEditsTest {

    private static CompoundTag cell(int x, int y, int z, String block) {
        CompoundTag cell = new CompoundTag();
        cell.put("p", new IntArrayTag(new int[]{x, y, z}));
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        cell.put("s", state);
        return cell;
    }

    private static CompoundTag base() {
        CompoundTag base = new CompoundTag();
        base.putInt("v", 2);
        base.putInt("l", 9);
        base.putInt("h", 7);
        base.putInt("w", 9);
        ListTag cells = new ListTag();
        cells.add(cell(0, 0, 0, "minecraft:stone"));
        cells.add(cell(1, 0, 0, "minecraft:oak_planks"));
        base.put("cells", cells);
        ListTag ents = new ListTag();
        CompoundTag frame = new CompoundTag();
        frame.put("id", StringTag.valueOf("minecraft:item_frame"));
        ents.add(frame);
        base.put("ents", ents);
        base.putString("extra", "kept");
        return base;
    }

    private static String blockAt(CompoundTag snap, int x, int y, int z) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            int[] p = cells.getCompound(i).getIntArray("p");
            if (p[0] == x && p[1] == y && p[2] == z) return cells.getCompound(i).getCompound("s").getString("Name");
        }
        return null;
    }

    @Test
    void placedAndBrokenCellsFoldOntoTheStoredRoom() {
        CompoundTag delta = new CompoundTag();
        ListTag set = new ListTag();
        set.add(cell(2, 1, 3, "minecraft:glowstone"));
        delta.put("set", set);
        ListTag del = new ListTag();
        del.add(new IntArrayTag(new int[]{1, 0, 0}));
        delta.put("del", del);

        CompoundTag out = TributeRoomLiveEdits.fold(base(), delta);

        assertEquals("minecraft:glowstone", blockAt(out, 2, 1, 3));
        assertNull(blockAt(out, 1, 0, 0), "a broken own block leaves air");
        assertEquals("minecraft:stone", blockAt(out, 0, 0, 0), "cells not in the delta are untouched");
    }

    @Test
    void theLiveRoomsEntitiesNeverReplaceTheStoredOnes() {
        CompoundTag delta = new CompoundTag();
        delta.put("set", new ListTag());
        delta.put("del", new ListTag());
        delta.put("ents", new ListTag()); // the live room's (here: none)

        CompoundTag stored = base();
        CompoundTag out = TributeRoomLiveEdits.fold(stored, delta);

        assertEquals(1, out.getList("ents", Tag.TAG_COMPOUND).size());
        assertEquals("kept", out.getString("extra"));
        assertEquals(9, out.getInt("l"));
        assertEquals(stored, base(), "the stored tag is not changed");
    }
}
