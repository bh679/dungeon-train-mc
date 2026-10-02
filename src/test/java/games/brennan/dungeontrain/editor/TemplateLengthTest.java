package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link TemplateLength} on a template's saved NBT: grow keeps every block, shrink crops past the end. */
final class TemplateLengthTest {

    private static ListTag ints(int... v) {
        ListTag l = new ListTag();
        for (int i : v) l.add(IntTag.valueOf(i));
        return l;
    }

    private static CompoundTag block(int x) {
        CompoundTag b = new CompoundTag();
        b.put("pos", ints(x, 0, 0));
        b.putInt("state", 0);
        return b;
    }

    /** A 13-long template with a block at x = 0, 8 and 12, and an entity at x = 10. */
    private static CompoundTag sample() {
        CompoundTag t = new CompoundTag();
        t.put("size", ints(13, 7, 7));
        ListTag blocks = new ListTag();
        blocks.add(block(0));
        blocks.add(block(8));
        blocks.add(block(12));
        t.put("blocks", blocks);
        ListTag ents = new ListTag();
        CompoundTag e = new CompoundTag();
        e.put("blockPos", ints(10, 1, 3));
        ents.add(e);
        t.put("entities", ents);
        return t;
    }

    @Test
    @DisplayName("shrinking to a room crops every block and entity at or past the new end")
    void shrinkCrops() {
        CompoundTag out = TemplateLength.withLength(sample(), 9);
        assertEquals(9, out.getList("size", Tag.TAG_INT).getInt(0));
        assertEquals(7, out.getList("size", Tag.TAG_INT).getInt(1));
        ListTag blocks = out.getList("blocks", Tag.TAG_COMPOUND);
        assertEquals(2, blocks.size());
        assertEquals(8, blocks.getCompound(1).getList("pos", Tag.TAG_INT).getInt(0));
        assertEquals(0, out.getList("entities", Tag.TAG_COMPOUND).size());
    }

    @Test
    @DisplayName("growing to a group keeps every block and entity and only lengthens the box")
    void growKeeps() {
        CompoundTag out = TemplateLength.withLength(sample(), 27);
        assertEquals(27, out.getList("size", Tag.TAG_INT).getInt(0));
        assertEquals(3, out.getList("blocks", Tag.TAG_COMPOUND).size());
        assertEquals(1, out.getList("entities", Tag.TAG_COMPOUND).size());
    }

    @Test
    @DisplayName("the input is left untouched, and a zero length is refused")
    void pure() {
        CompoundTag in = sample();
        TemplateLength.withLength(in, 9);
        assertEquals(13, in.getList("size", Tag.TAG_INT).getInt(0));
        assertEquals(3, in.getList("blocks", Tag.TAG_COMPOUND).size());
        assertThrows(IllegalArgumentException.class, () -> TemplateLength.withLength(in, 0));
    }
}
