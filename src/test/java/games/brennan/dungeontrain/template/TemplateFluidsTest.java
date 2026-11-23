package games.brennan.dungeontrain.template;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateFluids} on hand-built tags in the shape {@code StructureTemplate.save} writes: a
 * {@code palette} of {Name, Properties} entries and {@code blocks} of {pos, state}.
 */
class TemplateFluidsTest {

    private static CompoundTag state(String name, String... props) {
        CompoundTag e = new CompoundTag();
        e.putString("Name", name);
        if (props.length > 0) {
            CompoundTag p = new CompoundTag();
            for (int i = 0; i + 1 < props.length; i += 2) p.putString(props[i], props[i + 1]);
            e.put("Properties", p);
        }
        return e;
    }

    private static CompoundTag block(int x, int state) {
        CompoundTag b = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        b.put("pos", pos);
        b.putInt("state", state);
        return b;
    }

    /** A template whose palette is {@code palette} and which has one block per palette index, at x = index. */
    private static CompoundTag template(CompoundTag... palette) {
        CompoundTag tag = new CompoundTag();
        ListTag p = new ListTag();
        ListTag blocks = new ListTag();
        for (int i = 0; i < palette.length; i++) {
            p.add(palette[i]);
            blocks.add(block(i, i));
        }
        tag.put("palette", p);
        tag.put("blocks", blocks);
        return tag;
    }

    private static List<String> namesAtBlocks(CompoundTag tag) {
        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            CompoundTag e = palette.getCompound(b.getInt("state"));
            String level = e.getCompound("Properties").getString("level");
            out.add(b.getList("pos", Tag.TAG_INT).getInt(0) + ":" + e.getString("Name")
                + (level.isEmpty() ? "" : "@" + level));
        }
        return out;
    }

    @Test
    void sourcesAreNotFlowing() {
        assertFalse(TemplateFluids.isFlowing(state("minecraft:water", "level", "0")));
        assertFalse(TemplateFluids.isFlowing(state("minecraft:lava", "level", "0")));
        assertFalse(TemplateFluids.isFlowing(state("minecraft:water")));
    }

    @Test
    void flowingAndFallingAreFlowing() {
        for (int level = 1; level <= 15; level++) {
            assertTrue(TemplateFluids.isFlowing(state("minecraft:water", "level", Integer.toString(level))));
            assertTrue(TemplateFluids.isFlowing(state("minecraft:lava", "level", Integer.toString(level))));
        }
    }

    @Test
    void waterloggedAndWaterPlantsAreNotFlowing() {
        assertFalse(TemplateFluids.isFlowing(state("minecraft:oak_stairs", "waterlogged", "true")));
        assertFalse(TemplateFluids.isFlowing(state("minecraft:kelp", "age", "3")));
        assertFalse(TemplateFluids.isFlowing(state("minecraft:seagrass")));
        assertFalse(TemplateFluids.isFlowing(state("minecraft:bubble_column", "drag", "true")));
        // A non-fluid block that happens to carry a "level" property is not fluid.
        assertFalse(TemplateFluids.isFlowing(state("minecraft:composter", "level", "4")));
    }

    @Test
    void airIgnoredCaptureRemovesFlowingCells() {
        CompoundTag tag = template(
            state("minecraft:water", "level", "0"),
            state("minecraft:water", "level", "3"),
            state("minecraft:lava", "level", "8"),
            state("minecraft:oak_stairs", "waterlogged", "true"),
            state("minecraft:stone"));

        assertTrue(TemplateFluids.dropFlowing(tag, false));

        assertEquals(List.of("0:minecraft:water@0", "3:minecraft:oak_stairs", "4:minecraft:stone"),
            namesAtBlocks(tag));
    }

    @Test
    void airSavedCaptureTurnsFlowingCellsIntoAir() {
        CompoundTag tag = template(
            state("minecraft:water", "level", "0"),
            state("minecraft:water", "level", "1"),
            state("minecraft:lava", "level", "12"));

        assertTrue(TemplateFluids.dropFlowing(tag, true));

        assertEquals(List.of("0:minecraft:water@0", "1:minecraft:air", "2:minecraft:air"), namesAtBlocks(tag));
        // One air entry appended, shared by both cells.
        assertEquals(4, tag.getList("palette", Tag.TAG_COMPOUND).size());
    }

    @Test
    void airSavedCaptureReusesAnExistingAirEntry() {
        CompoundTag tag = template(
            state("minecraft:air"),
            state("minecraft:water", "level", "5"));

        assertTrue(TemplateFluids.dropFlowing(tag, true));

        assertEquals(List.of("0:minecraft:air", "1:minecraft:air"), namesAtBlocks(tag));
        assertEquals(2, tag.getList("palette", Tag.TAG_COMPOUND).size());
    }

    @Test
    void nothingFlowingLeavesTheTagAlone() {
        CompoundTag tag = template(
            state("minecraft:water", "level", "0"),
            state("minecraft:stone"));
        CompoundTag before = tag.copy();

        assertFalse(TemplateFluids.dropFlowing(tag, true));
        assertFalse(TemplateFluids.dropFlowing(tag, false));

        assertEquals(before, tag);
    }

    @Test
    void multiPaletteDropsOnlyCellsFlowingInEveryPalette() {
        ListTag a = new ListTag();
        a.add(state("minecraft:water", "level", "2"));
        a.add(state("minecraft:water", "level", "2"));
        ListTag b = new ListTag();
        b.add(state("minecraft:water", "level", "4"));
        b.add(state("minecraft:stone"));
        ListTag palettes = new ListTag();
        palettes.add(a);
        palettes.add(b);
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0));
        blocks.add(block(1, 1));
        CompoundTag tag = new CompoundTag();
        tag.put("palettes", palettes);
        tag.put("blocks", blocks);

        assertTrue(TemplateFluids.dropFlowing(tag, true));

        ListTag after = tag.getList("blocks", Tag.TAG_COMPOUND);
        int airIndex = after.getCompound(0).getInt("state");
        assertEquals(1, after.getCompound(1).getInt("state"), "stone in one palette: kept");
        for (int i = 0; i < 2; i++) {
            ListTag p = tag.getList("palettes", Tag.TAG_LIST).getList(i);
            assertEquals("minecraft:air", p.getCompound(airIndex).getString("Name"));
        }
    }

    @Test
    void tagWithoutBlocksIsUntouched() {
        assertFalse(TemplateFluids.dropFlowing(new CompoundTag(), true));
    }
}
