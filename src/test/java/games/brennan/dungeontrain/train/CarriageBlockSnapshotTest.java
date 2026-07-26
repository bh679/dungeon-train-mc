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
import static org.junit.jupiter.api.Assertions.assertNull;

class CarriageBlockSnapshotTest {

    // ---- helpers (raw-NBT; applyDeltaCells copies cells by pos and never inspects the "s" blockstate,
    // so a "marker" string stands in for a block state and no MC bootstrap/registries are needed) ----

    private static CompoundTag baseTag(int l, int h, int w) {
        CompoundTag root = new CompoundTag();
        root.putInt("v", 1);
        root.putInt("l", l);
        root.putInt("h", h);
        root.putInt("w", w);
        root.put("cells", new ListTag());
        return root;
    }

    private static CompoundTag markerCell(int x, int y, int z, String marker) {
        CompoundTag cell = new CompoundTag();
        cell.put("p", new IntArrayTag(new int[]{x, y, z}));
        cell.putString("marker", marker);
        return cell;
    }

    private static void addCell(CompoundTag tag, String listKey, CompoundTag cell) {
        tag.getList(listKey, Tag.TAG_COMPOUND).add(cell);
    }

    private static CompoundTag deltaTag() {
        CompoundTag d = new CompoundTag();
        d.putInt("v", 1);
        d.put("set", new ListTag());
        d.put("del", new ListTag());
        return d;
    }

    private static void del(CompoundTag delta, int x, int y, int z) {
        delta.getList("del", Tag.TAG_INT_ARRAY).add(new IntArrayTag(new int[]{x, y, z}));
    }

    /** The "marker" of the cell at (x,y,z) in a snapshot, or null when absent. */
    private static String markerAt(CompoundTag snap, int x, int y, int z) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cell = cells.getCompound(i);
            int[] p = cell.getIntArray("p");
            if (p.length == 3 && p[0] == x && p[1] == y && p[2] == z) return cell.getString("marker");
        }
        return null;
    }

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

    @Test
    void applyDeltaCellsAddsReplacesAndRemovesByPos() {
        CompoundTag base = baseTag(9, 7, 7);
        addCell(base, "cells", markerCell(0, 0, 0, "A"));
        addCell(base, "cells", markerCell(1, 0, 0, "keep"));

        CompoundTag delta = deltaTag();
        addCell(delta, "set", markerCell(0, 0, 0, "B")); // replace (0,0,0) A→B
        addCell(delta, "set", markerCell(2, 0, 0, "new")); // add (2,0,0)
        del(delta, 1, 0, 0);                               // remove (1,0,0)

        CompoundTag out = CarriageBlockSnapshot.applyDeltaCells(base, delta);
        assertEquals("B", markerAt(out, 0, 0, 0));   // replaced
        assertNull(markerAt(out, 1, 0, 0));          // removed
        assertEquals("new", markerAt(out, 2, 0, 0)); // added
        assertEquals(2, out.getList("cells", Tag.TAG_COMPOUND).size());
        // dims/version carry over; the input base is not mutated.
        assertEquals(9, out.getInt("l"));
        assertEquals("A", markerAt(base, 0, 0, 0));
    }

    @Test
    void deltaEncodeDecodeRoundTripThenApply() throws Exception {
        CompoundTag delta = deltaTag();
        addCell(delta, "set", markerCell(3, 4, 5, "sign"));
        del(delta, 6, 0, 1);

        CompoundTag back = CarriageBlockSnapshot.decode(CarriageBlockSnapshot.encode(delta));
        CompoundTag base = baseTag(9, 7, 7);
        addCell(base, "cells", markerCell(6, 0, 1, "gone"));
        CompoundTag out = CarriageBlockSnapshot.applyDeltaCells(base, back);

        assertEquals("sign", markerAt(out, 3, 4, 5));
        assertNull(markerAt(out, 6, 0, 1));
    }

    @Test
    void reconstructionIsDeterministicWhenDeltasAreAppliedInSeqOrder() {
        // Base + two deltas applied in ascending seq must equal the true end state, regardless of the
        // order they arrived on the wire (the caller sorts by seq — this asserts the fold is correct).
        CompoundTag base = baseTag(9, 7, 7);
        addCell(base, "cells", markerCell(0, 0, 0, "A"));

        CompoundTag seq1 = deltaTag();
        addCell(seq1, "set", markerCell(0, 0, 0, "B")); // A→B
        addCell(seq1, "set", markerCell(1, 0, 0, "X")); // add X

        CompoundTag seq2 = deltaTag();
        addCell(seq2, "set", markerCell(0, 0, 0, "C")); // B→C
        del(seq2, 1, 0, 0);                             // remove X

        // In seq order (1 then 2) → the true end state.
        CompoundTag inOrder = CarriageBlockSnapshot.applyDeltaCells(
            CarriageBlockSnapshot.applyDeltaCells(base, seq1), seq2);
        assertEquals("C", markerAt(inOrder, 0, 0, 0));
        assertNull(markerAt(inOrder, 1, 0, 0));

        // Out of order (2 then 1) diverges — proving seq ordering is load-bearing, not incidental.
        CompoundTag outOfOrder = CarriageBlockSnapshot.applyDeltaCells(
            CarriageBlockSnapshot.applyDeltaCells(base, seq2), seq1);
        assertEquals("B", markerAt(outOfOrder, 0, 0, 0));
        assertEquals("X", markerAt(outOfOrder, 1, 0, 0));
    }
}
