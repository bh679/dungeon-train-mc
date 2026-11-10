package games.brennan.dungeontrain.train.engine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DriftEngineTest {

    private static final int L = 9, H = 7, W = 7;

    // ---- snapshot builders ----

    private static CompoundTag cell(int x, int y, int z, String name) {
        CompoundTag c = new CompoundTag();
        c.put("p", new IntArrayTag(new int[]{x, y, z}));
        CompoundTag s = new CompoundTag();
        s.putString("Name", name);
        c.put("s", s);
        return c;
    }

    /** A hollow box: floor at y=0, walls at z=0 and z=W-1, ends open. */
    private static CompoundTag shell() {
        CompoundTag snap = new CompoundTag();
        snap.putInt("l", L);
        snap.putInt("h", H);
        snap.putInt("w", W);
        ListTag cells = new ListTag();
        for (int x = 0; x < L; x++) {
            for (int z = 0; z < W; z++) cells.add(cell(x, 0, z, "minecraft:oak_planks"));
            for (int y = 1; y < H; y++) {
                cells.add(cell(x, y, 0, "minecraft:stone"));
                cells.add(cell(x, y, W - 1, "minecraft:stone"));
            }
        }
        snap.put("cells", cells);
        return snap;
    }

    private static CompoundTag with(CompoundTag snap, CompoundTag... extra) {
        CompoundTag out = snap.copy();
        ListTag cells = out.getList("cells", Tag.TAG_COMPOUND);
        for (CompoundTag c : extra) cells.add(c);
        out.put("cells", cells);
        return out;
    }

    private static CompoundTag item(String id, int count) {
        CompoundTag i = new CompoundTag();
        i.putString("id", id);
        i.putInt("count", count);
        return i;
    }

    private static CompoundTag chest(int x, int y, int z, CompoundTag... items) {
        CompoundTag c = cell(x, y, z, "minecraft:chest");
        CompoundTag b = new CompoundTag();
        b.putString("id", "minecraft:chest");
        ListTag list = new ListTag();
        for (CompoundTag i : items) list.add(i);
        b.put("Items", list);
        c.put("b", b);
        return c;
    }

    private static CompoundTag shulker(int count) {
        CompoundTag box = item("minecraft:shulker_box", 1);
        CompoundTag components = new CompoundTag();
        ListTag container = new ListTag();
        CompoundTag slot = new CompoundTag();
        slot.putInt("slot", 0);
        slot.put("item", item("minecraft:diamond", count));
        container.add(slot);
        components.put("minecraft:container", container);
        box.put("components", components);
        return box;
    }

    // ---- burn / clamp ----

    @Test
    void burnTakesOneAndStopsAtZero() {
        assertEquals(2, DriftEngine.burn(3));
        assertEquals(0, DriftEngine.burn(1));
        assertEquals(0, DriftEngine.burn(0));
        assertEquals(63, DriftEngine.burn(99));
    }

    // ---- arriving coal ----

    @Test
    void relayCountWinsOverTheBlob() {
        CompoundTag snap = DriftEngine.withEngineCoal(shell(), 10);
        assertEquals(4, DriftEngine.arrivingCoal(4, snap, null));
    }

    @Test
    void blobEngineUsedWhenRelayHasNoCount() {
        CompoundTag snap = DriftEngine.withEngineCoal(shell(), 10);
        assertEquals(10, DriftEngine.arrivingCoal(-1, snap, null));
    }

    @Test
    void noCountAndNoEngineRetrofits() {
        assertEquals(DriftEngine.FRESH_COAL, DriftEngine.arrivingCoal(-1, shell(), null));
    }

    // ---- writing the engine ----

    @Test
    void addsEngineOnAFreeFloorCellAgainstAWall() {
        CompoundTag snap = DriftEngine.withEngineCoal(shell(), 3);
        assertEquals(3, DriftEngine.coalIn(snap));
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        CompoundTag engine = cells.getCompound(DriftEngine.findEngine(cells));
        assertArrayEquals(new int[]{4, 1, 1}, engine.getIntArray("p"), "middle of the length, on the floor, by the wall");
        assertEquals("south", engine.getCompound("s").getCompound("Properties").getString("facing"));
        assertEquals("true", engine.getCompound("s").getCompound("Properties").getString("lit"));
    }

    @Test
    void updatesAnExistingEngineInsteadOfAddingOne() {
        CompoundTag once = DriftEngine.withEngineCoal(shell(), 5);
        CompoundTag twice = DriftEngine.withEngineCoal(once, 0);
        ListTag cells = twice.getList("cells", Tag.TAG_COMPOUND);
        assertEquals(once.getList("cells", Tag.TAG_COMPOUND).size(), cells.size());
        assertEquals(0, DriftEngine.coalIn(twice));
        assertEquals("false", cells.getCompound(DriftEngine.findEngine(cells)).getCompound("s")
                .getCompound("Properties").getString("lit"));
    }

    @Test
    void neverMutatesItsArgument() {
        CompoundTag snap = shell();
        CompoundTag before = snap.copy();
        CompoundTag out = DriftEngine.withEngineCoal(snap, 3);
        assertNotSame(snap, out);
        assertEquals(before, snap);
    }

    @Test
    void solidBoxIsLeftAlone() {
        CompoundTag snap = new CompoundTag();
        snap.putInt("l", 3);
        snap.putInt("h", 3);
        snap.putInt("w", 3);
        ListTag cells = new ListTag();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
            cells.add(cell(x, y, z, "minecraft:stone"));
        }
        snap.put("cells", cells);
        assertSame(snap, DriftEngine.withEngineCoal(snap, 3));
    }

    @Test
    void clampsToAStack() {
        assertEquals(DriftEngine.MAX_COAL, DriftEngine.coalIn(DriftEngine.withEngineCoal(shell(), 500)));
    }

    // ---- retrofit ----

    @Test
    void retrofitWithNoHistoryIsThree() {
        assertEquals(3, DriftEngine.retrofitCoal(null, shell()));
    }

    @Test
    void retrofitIgnoresUpToFiveAddedBlocks() {
        CompoundTag origin = shell();
        CompoundTag now = with(origin, cell(2, 1, 2, "minecraft:lantern"), cell(3, 1, 2, "minecraft:lantern"),
                cell(4, 1, 2, "minecraft:lantern"), cell(5, 1, 2, "minecraft:lantern"), cell(6, 1, 2, "minecraft:lantern"));
        assertEquals(3, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void retrofitAddsOnePerBlockPastFive() {
        CompoundTag origin = shell();
        CompoundTag now = with(origin, cell(1, 1, 2, "minecraft:lantern"), cell(2, 1, 2, "minecraft:lantern"),
                cell(3, 1, 2, "minecraft:lantern"), cell(4, 1, 2, "minecraft:lantern"), cell(5, 1, 2, "minecraft:lantern"),
                cell(6, 1, 2, "minecraft:lantern"));
        assertEquals(3 + 6, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void replacedBlockCountsAsAdded() {
        CompoundTag origin = shell();
        CompoundTag now = shell();
        ListTag cells = now.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < 6; i++) cells.getCompound(i).getCompound("s").putString("Name", "minecraft:gold_block");
        assertEquals(3 + 6, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void retrofitCountsItemsAddedIncludingShulkerContents() {
        CompoundTag origin = with(shell(), chest(2, 1, 2, item("minecraft:bread", 4)));
        CompoundTag now = with(shell(), chest(2, 1, 2, item("minecraft:bread", 4), item("minecraft:torch", 2), shulker(5)));
        // 2 torches + 1 shulker box + 5 diamonds inside it
        assertEquals(3 + 8, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void retrofitIgnoresItemsTaken() {
        CompoundTag origin = with(shell(), chest(2, 1, 2, item("minecraft:bread", 9)));
        CompoundTag now = with(shell(), chest(2, 1, 2, item("minecraft:bread", 1)));
        assertEquals(3, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void retrofitCapsAtAStack() {
        CompoundTag origin = shell();
        CompoundTag now = with(origin, chest(2, 1, 2, item("minecraft:cobblestone", 64), item("minecraft:cobblestone", 64)));
        assertEquals(DriftEngine.MAX_COAL, DriftEngine.retrofitCoal(origin, now));
    }

    // ---- spot ----

    @Test
    void spotNeedsFloorAndHeadroomAndAvoidsDoorPlanes() {
        // Only cell free with floor below and air above: x=0 (a door plane) and (4,1,3) in the open.
        boolean[][][] solid = new boolean[5][4][5];
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) solid[x][0][z] = true;
        for (int x = 0; x < 5; x++) for (int y = 1; y < 4; y++) for (int z = 0; z < 5; z++) solid[x][y][z] = true;
        solid[0][1][2] = false;
        solid[0][2][2] = false;
        solid[2][1][2] = false; // has floor, but the cell above is solid
        assertNull(EngineSpot.find(5, 4, 5, (x, y, z) -> solid[x][y][z]));
        solid[2][2][2] = false;
        assertArrayEquals(new int[]{2, 1, 2}, EngineSpot.find(5, 4, 5, (x, y, z) -> solid[x][y][z]));
    }
}
