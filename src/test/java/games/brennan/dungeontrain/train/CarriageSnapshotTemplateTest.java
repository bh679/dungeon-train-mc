package games.brennan.dungeontrain.train;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reshaping that lets a build come back down: a relay snapshot blob into template NBT.
 *
 * <p>Worth pinning because nothing downstream would notice it going wrong quietly. A dropped cell is
 * a hole in somebody's build; a palette index off by one is a build made of the wrong blocks; and
 * both would load, save and render without complaint.</p>
 */
final class CarriageSnapshotTemplateTest {

    @Test
    @DisplayName("cells become blocks, and repeated states share one palette entry")
    void reshapesCellsAndDedupesPalette() {
        CompoundTag snapshot = snapshot(3, 2, 1,
                cell(0, 0, 0, "minecraft:stone", null),
                cell(1, 0, 0, "minecraft:stone", null),
                cell(2, 1, 0, "minecraft:oak_planks", null));

        CompoundTag out = CarriageSnapshotTemplate.toTemplateTag(snapshot);

        assertEquals(2, out.getList("palette", Tag.TAG_COMPOUND).size(),
                "two distinct block states, however many cells use them");
        ListTag blocks = out.getList("blocks", Tag.TAG_COMPOUND);
        assertEquals(3, blocks.size());
        assertEquals(blocks.getCompound(0).getInt("state"), blocks.getCompound(1).getInt("state"),
                "the two stone cells point at the same palette entry");
        assertTrue(blocks.getCompound(2).getInt("state") != blocks.getCompound(0).getInt("state"));
        assertEquals(List3.of(2, 1, 0), List3.of(blocks.getCompound(2).getList("pos", Tag.TAG_INT)));
        assertEquals(List3.of(3, 2, 1), List3.of(out.getList("size", Tag.TAG_INT)),
                "the volume is the one the snapshot declared");
    }

    @Test
    @DisplayName("a block entity's contents ride along as the block's nbt")
    void keepsBlockEntities() {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("LootTable", "dungeontrain:carriage");

        CompoundTag out = CarriageSnapshotTemplate.toTemplateTag(
                snapshot(1, 1, 1, cell(0, 0, 0, "minecraft:chest", chest)));

        CompoundTag block = out.getList("blocks", Tag.TAG_COMPOUND).getCompound(0);
        assertTrue(block.contains("nbt", Tag.TAG_COMPOUND));
        assertEquals("dungeontrain:carriage", block.getCompound("nbt").getString("LootTable"));
    }

    @Test
    @DisplayName("a malformed or out-of-bounds cell is skipped, never fatal")
    void skipsBadCells() {
        CompoundTag noPos = new CompoundTag();
        noPos.put("s", blockState("minecraft:stone"));
        CompoundTag noState = new CompoundTag();
        noState.put("p", new IntArrayTag(new int[]{0, 0, 0}));

        CompoundTag out = CarriageSnapshotTemplate.toTemplateTag(snapshot(2, 2, 2,
                noPos, noState,
                cell(9, 0, 0, "minecraft:stone", null),    // outside the declared volume
                cell(0, 1, 1, "minecraft:stone", null)));  // the only good one

        assertEquals(1, out.getList("blocks", Tag.TAG_COMPOUND).size(),
                "one bad cell costs one block, never the whole build");
    }

    @Test
    @DisplayName("air is absent, exactly as it is from a template captured in a builder world")
    void airIsAbsent() {
        // A snapshot stores only non-air cells and a builder save captures against AIR, so an empty
        // volume is empty in both — this pins that the conversion adds nothing to fill it in.
        CompoundTag out = CarriageSnapshotTemplate.toTemplateTag(snapshot(4, 4, 4));
        assertEquals(0, out.getList("blocks", Tag.TAG_COMPOUND).size());
        assertEquals(0, out.getList("palette", Tag.TAG_COMPOUND).size());
        assertTrue(out.contains("entities", Tag.TAG_LIST), "shaped like a tag vanilla saves");
        assertFalse(out.getList("entities", Tag.TAG_COMPOUND).size() > 0,
                "entities are dropped: no local template has ever carried one");
    }

    // ---- the way back up: template NBT into a snapshot ----

    @Test
    @DisplayName("a template round-trips through a snapshot unchanged")
    void roundTripsBackToASnapshot() {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("LootTable", "dungeontrain:carriage");
        CompoundTag original = snapshot(3, 2, 1,
                cell(0, 0, 0, "minecraft:stone", null),
                cell(1, 0, 0, "minecraft:stone", null),
                cell(2, 1, 0, "minecraft:chest", chest));

        CompoundTag back = CarriageSnapshotTemplate.fromTemplateTag(
                CarriageSnapshotTemplate.toTemplateTag(original));

        // The whole point: a build that went down and came back up is the same build. Compared as one
        // value, so a lost cell, a swapped position or a dropped block entity all fail here.
        assertEquals(original, back);
    }

    @Test
    @DisplayName("the reshaping produces the same tag a capture would have")
    void matchesACapture() {
        CompoundTag out = CarriageSnapshotTemplate.fromTemplateTag(
                CarriageSnapshotTemplate.toTemplateTag(snapshot(2, 3, 4,
                        cell(1, 1, 1, "minecraft:stone", null))));

        assertEquals(CarriageBlockSnapshot.FORMAT_VERSION, out.getInt("v"));
        assertEquals(List3.of(2, 3, 4), List3.of(out.getInt("l"), out.getInt("h"), out.getInt("w")));
        assertFalse(out.contains("ents"),
                "captureLevel writes no ents key, and a re-upload must be indistinguishable from it");
    }

    @Test
    @DisplayName("a block entry outside the volume or off the palette is skipped, never fatal")
    void skipsBadBlockEntries() {
        CompoundTag template = CarriageSnapshotTemplate.toTemplateTag(
                snapshot(2, 2, 2, cell(0, 0, 0, "minecraft:stone", null)));
        ListTag blocks = template.getList("blocks", Tag.TAG_COMPOUND);
        blocks.add(blockEntry(9, 0, 0, 0));   // outside the declared volume
        blocks.add(blockEntry(1, 1, 1, 7));   // palette index that isn't there

        CompoundTag out = CarriageSnapshotTemplate.fromTemplateTag(template);

        assertEquals(1, out.getList("cells", Tag.TAG_COMPOUND).size(),
                "one bad entry costs one block, never the whole build");
    }

    @Test
    @DisplayName("a template with no readable size yields an empty snapshot rather than throwing")
    void toleratesAMissingSize() {
        CompoundTag out = CarriageSnapshotTemplate.fromTemplateTag(new CompoundTag());

        assertEquals(0, out.getInt("l"));
        assertEquals(0, out.getList("cells", Tag.TAG_COMPOUND).size());
    }

    @Test
    @DisplayName("a variant template's first palette is read rather than refused")
    void readsTheFirstOfSeveralPalettes() {
        CompoundTag template = CarriageSnapshotTemplate.toTemplateTag(
                snapshot(1, 1, 1, cell(0, 0, 0, "minecraft:stone", null)));
        ListTag palettes = new ListTag();
        palettes.add(template.getList("palette", Tag.TAG_COMPOUND));
        template.remove("palette");
        template.put("palettes", palettes);

        CompoundTag out = CarriageSnapshotTemplate.fromTemplateTag(template);

        assertEquals(1, out.getList("cells", Tag.TAG_COMPOUND).size());
        assertEquals("minecraft:stone",
                out.getList("cells", Tag.TAG_COMPOUND).getCompound(0).getCompound("s").getString("Name"));
    }

    // ---- helpers ----

    private static CompoundTag blockEntry(int x, int y, int z, int stateIndex) {
        ListTag pos = new ListTag();
        pos.add(net.minecraft.nbt.IntTag.valueOf(x));
        pos.add(net.minecraft.nbt.IntTag.valueOf(y));
        pos.add(net.minecraft.nbt.IntTag.valueOf(z));
        CompoundTag entry = new CompoundTag();
        entry.put("pos", pos);
        entry.putInt("state", stateIndex);
        return entry;
    }


    private static CompoundTag snapshot(int l, int h, int w, CompoundTag... cells) {
        ListTag list = new ListTag();
        for (CompoundTag cell : cells) list.add(cell);
        CompoundTag root = new CompoundTag();
        root.putInt("v", 2);
        root.putInt("l", l);
        root.putInt("h", h);
        root.putInt("w", w);
        root.put("cells", list);
        return root;
    }

    private static CompoundTag cell(int x, int y, int z, String block, CompoundTag blockEntity) {
        CompoundTag cell = new CompoundTag();
        cell.put("p", new IntArrayTag(new int[]{x, y, z}));
        cell.put("s", blockState(block));
        if (blockEntity != null) cell.put("b", blockEntity);
        return cell;
    }

    /** The shape {@code NbtUtils.writeBlockState} produces, which is also a palette entry's shape. */
    private static CompoundTag blockState(String name) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", name);
        return state;
    }

    /** Three ints, so a position or a size can be compared as one value. */
    private record List3(int x, int y, int z) {
        static List3 of(int x, int y, int z) {
            return new List3(x, y, z);
        }

        static List3 of(ListTag tag) {
            return new List3(tag.getInt(0), tag.getInt(1), tag.getInt(2));
        }
    }
}
