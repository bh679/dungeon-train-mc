package games.brennan.dungeontrain.train;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(0, out.getList("entities", Tag.TAG_COMPOUND).size(),
                "a blob with no ents list still produces the empty list vanilla writes");
    }

    @Test
    @DisplayName("a blob's decor becomes the template's, positioned in the build's own frame")
    void entitiesComeAcross() {
        CompoundTag snapshot = snapshot(4, 4, 4, cell(0, 0, 0, "minecraft:stone", null));
        snapshot.put("ents", ents(ent("minecraft:item_frame", 1.5, 2.25, 3.5)));

        ListTag entities = CarriageSnapshotTemplate.toTemplateTag(snapshot)
                .getList("entities", Tag.TAG_COMPOUND);

        assertEquals(1, entities.size());
        CompoundTag entity = entities.getCompound(0);
        assertEquals("minecraft:item_frame", entity.getCompound("nbt").getString("id"));
        ListTag pos = entity.getList("pos", Tag.TAG_DOUBLE);
        assertEquals(1.5, pos.getDouble(0));
        assertEquals(2.25, pos.getDouble(1));
        assertEquals(3.5, pos.getDouble(2));
        // Floored, not rounded: blockPos is the block the entity stands IN.
        assertEquals(List3.of(1, 2, 3), List3.of(entity.getList("blockPos", Tag.TAG_INT)));
    }

    @Test
    @DisplayName("an entity with no type is skipped rather than written as one nothing can spawn")
    void skipsUntypedEntities() {
        CompoundTag snapshot = snapshot(4, 4, 4);
        CompoundTag typeless = ent("minecraft:item_frame", 1.0, 1.0, 1.0);
        typeless.getCompound("n").remove("id");
        snapshot.put("ents", ents(typeless, ent("minecraft:painting", 2.0, 2.0, 2.0)));

        ListTag entities = CarriageSnapshotTemplate.toTemplateTag(snapshot)
                .getList("entities", Tag.TAG_COMPOUND);

        assertEquals(1, entities.size(), "the good one still comes across");
        assertEquals("minecraft:painting", entities.getCompound(0).getCompound("nbt").getString("id"));
    }

    @Test
    @DisplayName("a mob standing in the plot is left out, exactly as a local save leaves it out")
    void nonDecorIsLeftOut() {
        // TemplateDecor.keepOnlyDecor strips these as a template is written, so a downloaded build
        // that kept them would hold something the author's own file never did — and no stamp path
        // puts one back, so it would never appear either way.
        CompoundTag snapshot = snapshot(4, 4, 4);
        snapshot.put("ents", ents(
                ent("minecraft:parrot", 1.0, 1.0, 1.0),
                ent("minecraft:armor_stand", 2.0, 1.0, 2.0),
                ent("minecraft:glow_item_frame", 3.0, 1.0, 3.0)));

        ListTag entities = CarriageSnapshotTemplate.toTemplateTag(snapshot)
                .getList("entities", Tag.TAG_COMPOUND);

        assertEquals(1, entities.size(), "only the decor survives");
        assertEquals("minecraft:glow_item_frame",
                entities.getCompound(0).getCompound("nbt").getString("id"));
    }

    // ---- helpers ----

    private static ListTag ents(CompoundTag... entries) {
        ListTag list = new ListTag();
        for (CompoundTag entry : entries) list.add(entry);
        return list;
    }

    /** The shape {@code CarriageEntitySnapshot.encodeEntity} produces, trimmed to what matters here. */
    private static CompoundTag ent(String id, double x, double y, double z) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", id);
        CompoundTag entry = new CompoundTag();
        entry.putString("id", id);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        entry.put("p", pos);
        entry.put("n", nbt);
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
