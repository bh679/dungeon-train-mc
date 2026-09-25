package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structure-NBT completion of half-placed doors, tall plants and beds. Pure NBT — no bootstrap. */
class DoubleBlockTemplateRepairTest {

    /** Builds a structure tag cell by cell. */
    private static final class Tpl {
        final ListTag palette = new ListTag();
        final ListTag blocks = new ListTag();
        final int[] size;

        Tpl(int x, int y, int z) {
            size = new int[] {x, y, z};
        }

        Tpl put(int x, int y, int z, String name, String... props) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", name);
            if (props.length > 0) {
                CompoundTag p = new CompoundTag();
                for (int i = 0; i < props.length; i += 2) p.putString(props[i], props[i + 1]);
                state.put("Properties", p);
            }
            int idx = -1;
            for (int i = 0; i < palette.size(); i++) if (palette.getCompound(i).equals(state)) idx = i;
            if (idx < 0) { palette.add(state); idx = palette.size() - 1; }
            CompoundTag b = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(x)); pos.add(IntTag.valueOf(y)); pos.add(IntTag.valueOf(z));
            b.put("pos", pos);
            b.putInt("state", idx);
            blocks.add(b);
            return this;
        }

        CompoundTag tag() {
            CompoundTag t = new CompoundTag();
            t.put("palette", palette);
            t.put("blocks", blocks);
            ListTag s = new ListTag();
            for (int v : size) s.add(IntTag.valueOf(v));
            t.put("size", s);
            return t;
        }
    }

    /** "x,y,z" → the state compound at that cell. */
    private static Map<String, CompoundTag> cells(CompoundTag tag) {
        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        Map<String, CompoundTag> out = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            out.put(p.getInt(0) + "," + p.getInt(1) + "," + p.getInt(2), palette.getCompound(b.getInt("state")));
        }
        return out;
    }

    private static String prop(CompoundTag state, String key) {
        return state.getCompound("Properties").getString(key);
    }

    private static final String[] LOWER_DOOR = {"facing", "west", "half", "lower", "hinge", "right", "open", "false"};

    @Test
    @DisplayName("A lone lower door gets its upper half, same wood and hinge")
    void loneLowerDoorGetsUpper() {
        CompoundTag in = new Tpl(1, 4, 1).put(0, 1, 0, "minecraft:dark_oak_door", LOWER_DOOR).tag();
        DoubleBlockTemplateRepair.Result r = DoubleBlockTemplateRepair.repair(in);
        assertEquals(1, r.added());
        CompoundTag upper = cells(r.tag()).get("0,2,0");
        assertEquals("minecraft:dark_oak_door", upper.getString("Name"));
        assertEquals("upper", prop(upper, "half"));
        assertEquals("right", prop(upper, "hinge"));
        assertEquals("west", prop(upper, "facing"));
    }

    @Test
    @DisplayName("A lone upper half gets its lower half")
    void loneUpperGetsLower() {
        CompoundTag in = new Tpl(1, 4, 1).put(0, 2, 0, "minecraft:tall_grass", "half", "upper").tag();
        CompoundTag lower = cells(DoubleBlockTemplateRepair.repair(in).tag()).get("0,1,0");
        assertEquals("minecraft:tall_grass", lower.getString("Name"));
        assertEquals("lower", prop(lower, "half"));
    }

    @Test
    @DisplayName("An air cell above a lone lower half is filled")
    void airPartnerIsFilled() {
        CompoundTag in = new Tpl(1, 4, 1)
            .put(0, 0, 0, "minecraft:tall_grass", "half", "lower")
            .put(0, 1, 0, "minecraft:air").tag();
        DoubleBlockTemplateRepair.Result r = DoubleBlockTemplateRepair.repair(in);
        assertEquals(1, r.added());
        assertEquals("minecraft:tall_grass", cells(r.tag()).get("0,1,0").getString("Name"));
    }

    @Test
    @DisplayName("A mismatched upper is rewritten to match the lower")
    void mismatchedUpperMatchesLower() {
        CompoundTag in = new Tpl(1, 4, 1)
            .put(0, 1, 0, "minecraft:oxidized_copper_door", LOWER_DOOR)
            .put(0, 2, 0, "minecraft:copper_door", "facing", "west", "half", "upper", "hinge", "right", "open", "false")
            .tag();
        DoubleBlockTemplateRepair.Result r = DoubleBlockTemplateRepair.repair(in);
        assertEquals(1, r.rewritten());
        assertEquals("minecraft:oxidized_copper_door", cells(r.tag()).get("0,2,0").getString("Name"));
    }

    @Test
    @DisplayName("A bed foot gets its head in the direction it faces")
    void bedFootGetsHead() {
        CompoundTag in = new Tpl(3, 1, 3)
            .put(1, 0, 1, "minecraft:red_bed", "facing", "east", "part", "foot", "occupied", "false").tag();
        CompoundTag head = cells(DoubleBlockTemplateRepair.repair(in).tag()).get("2,0,1");
        assertEquals("minecraft:red_bed", head.getString("Name"));
        assertEquals("head", prop(head, "part"));
    }

    @Test
    @DisplayName("Stairs (half=bottom) are not two-space blocks")
    void stairsUntouched() {
        CompoundTag in = new Tpl(1, 3, 1)
            .put(0, 0, 0, "minecraft:oak_stairs", "facing", "east", "half", "bottom", "shape", "straight").tag();
        DoubleBlockTemplateRepair.Result r = DoubleBlockTemplateRepair.repair(in);
        assertFalse(r.changed());
        assertSame(in, r.tag());
    }

    @Test
    @DisplayName("A partner cell holding another block (variant placeholder) is left alone")
    void occupiedPartnerLeftAlone() {
        CompoundTag in = new Tpl(1, 3, 1)
            .put(0, 0, 0, "minecraft:tall_grass", "half", "lower")
            .put(0, 1, 0, "minecraft:command_block", "facing", "up").tag();
        DoubleBlockTemplateRepair.Result r = DoubleBlockTemplateRepair.repair(in);
        assertFalse(r.changed());
        assertEquals("minecraft:command_block", cells(r.tag()).get("0,1,0").getString("Name"));
    }

    @Test
    @DisplayName("A partner that would fall outside the template's bounds is not added")
    void outOfBoundsSkipped() {
        CompoundTag in = new Tpl(1, 2, 1).put(0, 1, 0, "minecraft:oak_door", LOWER_DOOR).tag();
        assertFalse(DoubleBlockTemplateRepair.repair(in).changed());
    }

    @Test
    @DisplayName("A whole door is untouched and the input tag is never mutated")
    void wholeDoorAndNoMutation() {
        CompoundTag whole = new Tpl(1, 4, 1)
            .put(0, 1, 0, "minecraft:oak_door", LOWER_DOOR)
            .put(0, 2, 0, "minecraft:oak_door", "facing", "west", "half", "upper", "hinge", "right", "open", "false")
            .tag();
        assertFalse(DoubleBlockTemplateRepair.repair(whole).changed());

        CompoundTag half = new Tpl(1, 4, 1).put(0, 1, 0, "minecraft:oak_door", LOWER_DOOR).tag();
        CompoundTag before = half.copy();
        DoubleBlockTemplateRepair.repair(half);
        assertEquals(before, half);
    }

    @Test
    @DisplayName("A tag with no single palette is returned as-is")
    void noPaletteUntouched() {
        CompoundTag in = new CompoundTag();
        DoubleBlockTemplateRepair.Result r = DoubleBlockTemplateRepair.repair(in);
        assertSame(in, r.tag());
        assertNull(DoubleBlockTemplateRepair.repair((CompoundTag) null).tag());
        assertTrue(!r.changed());
    }
}
