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
        assertEquals(98, DriftEngine.burn(99), "a stored total is not held to the 12 an input takes");
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
        assertEquals(DriftEngine.RETROFIT_COAL, DriftEngine.arrivingCoal(-1, shell(), null));
    }

    // ---- writing the engine ----

    /** {@link #shell()} with its stone walls built of {@code wall} and its plank floor of {@code floor}. */
    private static CompoundTag shellOf(String wall, String floor) {
        CompoundTag snap = shell();
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag s = cells.getCompound(i).getCompound("s");
            if (s.getString("Name").equals("minecraft:stone")) s.putString("Name", wall);
            else if (s.getString("Name").equals("minecraft:oak_planks")) s.putString("Name", floor);
        }
        return snap;
    }

    private static int[] enginePos(CompoundTag snap) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        return cells.getCompound(DriftEngine.findEngine(cells)).getIntArray("p");
    }

    @Test
    void takesThePlaceOfAStoneWallBlockFacingTheRoom() {
        CompoundTag before = shell();
        CompoundTag snap = DriftEngine.withEngineCoal(before, 3);
        assertEquals(3, DriftEngine.coalIn(snap));
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        assertEquals(before.getList("cells", Tag.TAG_COMPOUND).size(), cells.size(), "replaced, not added");
        CompoundTag engine = cells.getCompound(DriftEngine.findEngine(cells));
        assertArrayEquals(new int[]{4, 1, 0}, engine.getIntArray("p"), "in the side wall, at floor level, mid-length");
        assertEquals("south", engine.getCompound("s").getCompound("Properties").getString("facing"), "faces into the room");
        assertEquals("true", engine.getCompound("s").getCompound("Properties").getString("lit"));
    }

    @Test
    void withNothingReplaceableItStandsOnAFreeFloorCellAgainstAWall() {
        CompoundTag before = shellOf("minecraft:glass", "minecraft:glass");
        CompoundTag snap = DriftEngine.withEngineCoal(before, 3);
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        assertEquals(before.getList("cells", Tag.TAG_COMPOUND).size() + 1, cells.size(), "added, not replaced");
        assertArrayEquals(new int[]{4, 1, 1}, cells.getCompound(DriftEngine.findEngine(cells)).getIntArray("p"));
    }

    @Test
    void neverTakesTheEndPlanesOrABlockEntity() {
        // Bricks only in the door plane (x=0) and a stone-named chest mid-wall: neither may be taken.
        CompoundTag snap = shellOf("minecraft:glass", "minecraft:glass");
        CompoundTag stoneChest = cell(4, 1, 0, "minecraft:stone");
        stoneChest.put("b", new CompoundTag());
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag c = cells.getCompound(i);
            int[] p = c.getIntArray("p");
            if (p[0] == 0 && p[2] == 0) c.getCompound("s").putString("Name", "minecraft:bricks");
            if (p[0] == 4 && p[1] == 1 && p[2] == 0) cells.set(i, stoneChest);
        }
        CompoundTag out = DriftEngine.withEngineCoal(snap, 3);
        ListTag outCells = out.getList("cells", Tag.TAG_COMPOUND);
        assertArrayEquals(new int[]{4, 1, 1}, outCells.getCompound(DriftEngine.findEngine(outCells)).getIntArray("p"),
                "falls back to the floor cell");
    }

    @Test
    void masonryBeatsWoodWhereverItIs() {
        // Wooden walls, stone floor: the stone floor wins over the wooden wall.
        int[] p = enginePos(DriftEngine.withEngineCoal(shellOf("minecraft:oak_planks", "minecraft:stone"), 3));
        assertEquals(0, p[1], "in the floor");
    }

    @Test
    void woodIsUsedWhenThereIsNoMasonry() {
        int[] p = enginePos(DriftEngine.withEngineCoal(shellOf("minecraft:spruce_planks", "minecraft:glass"), 3));
        assertArrayEquals(new int[]{4, 1, 0}, p, "in the wooden side wall");
    }

    @Test
    void grades() {
        for (String yes : new String[]{"minecraft:stone", "minecraft:stone_bricks", "minecraft:cobblestone",
                "minecraft:mossy_cobblestone", "minecraft:sandstone", "minecraft:cut_red_sandstone", "minecraft:tuff",
                "minecraft:tuff_bricks", "minecraft:polished_deepslate", "minecraft:andesite", "minecraft:bricks",
                "minecraft:nether_bricks", "minecraft:blackstone", "minecraft:basalt", "minecraft:end_stone",
                "minecraft:copper_block", "minecraft:oxidized_cut_copper", "minecraft:waxed_chiseled_copper",
                "minecraft:quartz_block", "minecraft:smooth_quartz", "minecraft:obsidian", "minecraft:crying_obsidian",
                "minecraft:mud_bricks"}) {
            assertEquals(EngineSpot.PRIMARY, EngineSpot.grade(yes), yes);
        }
        for (String wood : new String[]{"minecraft:oak_planks", "minecraft:spruce_log", "minecraft:stripped_birch_wood",
                "minecraft:crimson_stem", "minecraft:warped_hyphae", "minecraft:bamboo_block", "minecraft:dirt",
                "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:mud", "minecraft:packed_mud",
                "minecraft:moss_block", "minecraft:clay", "minecraft:terracotta", "minecraft:white_glazed_terracotta"}) {
            assertEquals(EngineSpot.SECONDARY, EngineSpot.grade(wood), wood);
        }
        for (String no : new String[]{"minecraft:stone_stairs", "minecraft:stone_brick_slab", "minecraft:cobblestone_wall",
                "minecraft:stone_button", "minecraft:redstone_block", "minecraft:glowstone", "minecraft:iron_ore",
                "minecraft:deepslate_iron_ore", "minecraft:copper_ore", "minecraft:nether_quartz_ore",
                "minecraft:stonecutter", "minecraft:infested_stone", "minecraft:copper_door", "minecraft:copper_bulb",
                "minecraft:oak_door", "minecraft:oak_fence", "minecraft:dirt_path", "minecraft:moss_carpet",
                "minecraft:glass", "betternether:obsidian_glass", "betternether:quartz_glass_framed", "minecraft:white_wool", "dungeontrain:stage_stone_bricks"}) {
            assertEquals(EngineSpot.NONE, EngineSpot.grade(no), no);
        }
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
    void joiningANewWorldBanksTheInput() {
        // Left the last world with 10: 6 banked, 4 put in that world. It arrives with 9, all banked.
        CompoundTag left = DriftEngine.withEngineCoal(shell(), 10);
        ListTag cells = left.getList("cells", Tag.TAG_COMPOUND);
        cells.getCompound(DriftEngine.findEngine(cells)).getCompound("b").putInt(DriftEngine.TAG_INPUT, 4);
        assertEquals(10, DriftEngine.coalIn(left), "the blob's count is the whole fuel, input included");
        CompoundTag arrived = DriftEngine.withEngineCoal(left, DriftEngine.burn(DriftEngine.coalIn(left)));
        ListTag out = arrived.getList("cells", Tag.TAG_COMPOUND);
        CompoundTag b = out.getCompound(DriftEngine.findEngine(out)).getCompound("b");
        assertEquals(9, b.getInt(DriftEngine.TAG_COAL));
        org.junit.jupiter.api.Assertions.assertFalse(b.contains(DriftEngine.TAG_INPUT), "the input starts empty");
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
    void coalAboveTheInputCapIsKeptUntilBurnt() {
        // Fed to 30 before the cap came down to 12: it keeps all 30.
        assertEquals(30, DriftEngine.coalIn(DriftEngine.withEngineCoal(shell(), 30)));
        assertEquals(DriftEngine.MAX_STORED, DriftEngine.coalIn(DriftEngine.withEngineCoal(shell(), 1_000_000)));
    }

    // ---- several engines ----

    private static CompoundTag engine(int x, int y, int z, int coal) {
        CompoundTag c = cell(x, y, z, DriftEngine.ENGINE_ID);
        CompoundTag props = new CompoundTag();
        props.putString("lit", coal > 0 ? "true" : "false");
        c.getCompound("s").put("Properties", props);
        CompoundTag b = new CompoundTag();
        b.putString("id", DriftEngine.ENGINE_ID);
        b.putInt(DriftEngine.TAG_COAL, coal);
        c.put("b", b);
        return c;
    }

    /** Each engine's coal, in the order they burn. */
    private static int[] coals(CompoundTag snap) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        return DriftEngine.findEngines(cells).stream()
                .mapToInt(i -> cells.getCompound(i).getCompound("b").getInt(DriftEngine.TAG_COAL)).toArray();
    }

    private static String lit(CompoundTag snap, int k) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        return cells.getCompound(DriftEngine.findEngines(cells).get(k)).getCompound("s")
                .getCompound("Properties").getString("lit");
    }

    @Test
    void theCarriagesFuelIsEveryEngineTogether() {
        CompoundTag snap = with(shell(), engine(2, 1, 1, 5), engine(6, 1, 1, 12), engine(4, 2, 1, 1));
        assertEquals(18, DriftEngine.coalIn(snap));
    }

    @Test
    void theFirstEngineBurnsUntilEmptyThenTheNext() {
        CompoundTag snap = with(shell(), engine(2, 1, 1, 2), engine(6, 1, 1, 12));
        snap = DriftEngine.burnOne(snap);
        assertArrayEquals(new int[]{1, 12}, coals(snap));
        snap = DriftEngine.burnOne(snap);
        assertArrayEquals(new int[]{0, 12}, coals(snap), "the first runs dry");
        assertEquals("false", lit(snap, 0), "and goes dark");
        assertEquals("true", lit(snap, 1));
        snap = DriftEngine.burnOne(snap);
        assertArrayEquals(new int[]{0, 11}, coals(snap), "then the second takes over");
    }

    @Test
    void burnOneLeavesAnEmptyCarriageAlone() {
        CompoundTag snap = with(shell(), engine(2, 1, 1, 0), engine(6, 1, 1, 0));
        assertSame(snap, DriftEngine.burnOne(snap));
    }

    @Test
    void enginesThatAlreadyAddUpKeepTheirOwnCoal() {
        CompoundTag snap = with(shell(), engine(2, 1, 1, 3), engine(6, 1, 1, 9));
        assertArrayEquals(new int[]{3, 9}, coals(DriftEngine.withEngineCoal(snap, 12)));
    }

    @Test
    void aStaleBlobIsSharedLastEnginesFirst() {
        // The relay says 20 but the blob's engines hold 3 + 9: the later engine fills to 12, the first takes the rest.
        CompoundTag snap = with(shell(), engine(2, 1, 1, 3), engine(6, 1, 1, 9));
        assertArrayEquals(new int[]{8, 12}, coals(DriftEngine.withEngineCoal(snap, 20)));
        // Three engines and 30: the last two are filled, the first holds 6.
        CompoundTag three = with(shell(), engine(2, 1, 1, 0), engine(4, 1, 1, 0), engine(6, 1, 1, 0));
        assertArrayEquals(new int[]{6, 12, 12}, coals(DriftEngine.withEngineCoal(three, 30)));
        // More than every engine's 12 together: the first keeps the excess, to burn away.
        assertArrayEquals(new int[]{20, 12}, coals(DriftEngine.withEngineCoal(snap, 32)));
    }

    @Test
    void shareFillsLastEnginesToTwelveFirst() {
        assertArrayEquals(new int[]{0, 5}, DriftEngine.share(5, 2));
        assertArrayEquals(new int[]{1, 12}, DriftEngine.share(13, 2));
        assertArrayEquals(new int[]{7}, DriftEngine.share(7, 1));
        assertArrayEquals(new int[]{}, DriftEngine.share(7, 0));
    }

    @Test
    void arrivingWithTwoEnginesBurnsFromTheFirst() {
        // Arrives with the relay's 14 over two engines holding 2 + 12: the first burns, the second is untouched.
        CompoundTag snap = with(shell(), engine(2, 1, 1, 2), engine(6, 1, 1, 12));
        CompoundTag fuelled = DriftEngine.burnOne(DriftEngine.withEngineCoal(snap, DriftEngine.arrivingCoal(14, snap, null)));
        assertArrayEquals(new int[]{1, 12}, coals(fuelled));
        assertEquals(13, DriftEngine.coalIn(fuelled));
    }

    @Test
    void anEngineAboveTheCapBurnsDownAsUsual() {
        CompoundTag snap = with(shell(), engine(2, 1, 1, 40));
        assertArrayEquals(new int[]{39}, coals(DriftEngine.burnOne(snap)));
    }

    // ---- the last coal burns as embers ----

    private static String prop(CompoundTag snap, int k, String name) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        return cells.getCompound(DriftEngine.findEngines(cells).get(k)).getCompound("s")
                .getCompound("Properties").getString(name);
    }

    @Test
    void aFreshCarriageStartsEmpty() {
        assertEquals(0, DriftEngine.FRESH_COAL);
    }

    @Test
    void anEngineOnItsLastCoalIsLowAndStillLit() {
        CompoundTag one = DriftEngine.withEngineCoal(shell(), 1);
        assertEquals("true", prop(one, 0, "lit"));
        assertEquals("true", prop(one, 0, "low"), "the last coal burns as embers");
        CompoundTag two = DriftEngine.withEngineCoal(shell(), 2);
        assertEquals("false", prop(two, 0, "low"));
        CompoundTag none = DriftEngine.withEngineCoal(shell(), 0);
        assertEquals("false", prop(none, 0, "lit"));
        assertEquals("false", prop(none, 0, "low"), "cold is not embers");
    }

    @Test
    void burningDownGoesFullThenEmbersThenCold() {
        CompoundTag snap = with(shell(), engine(2, 1, 1, 2));
        snap = DriftEngine.burnOne(snap);
        assertEquals("true", prop(snap, 0, "low"), "2 to 1: embers");
        assertEquals("true", prop(snap, 0, "lit"));
        snap = DriftEngine.burnOne(snap);
        assertEquals("false", prop(snap, 0, "lit"), "1 to 0: cold");
        assertEquals("false", prop(snap, 0, "low"));
    }

    @Test
    void embersArePerEngine() {
        // Two engines sharing 13: the first holds 1 (embers), the second 12 (full).
        CompoundTag snap = DriftEngine.withEngineCoal(with(shell(), engine(2, 1, 1, 0), engine(6, 1, 1, 0)), 13);
        assertEquals("true", prop(snap, 0, "low"));
        assertEquals("false", prop(snap, 1, "low"));
    }

    // ---- retrofit ----

    @Test
    void retrofitWithNoHistoryIsTheFreshTwo() {
        assertEquals(DriftEngine.RETROFIT_COAL, DriftEngine.retrofitCoal(null, shell()));
    }

    @Test
    void retrofitIgnoresUpToFiveAddedBlocks() {
        CompoundTag origin = shell();
        CompoundTag now = with(origin, cell(2, 1, 2, "minecraft:lantern"), cell(3, 1, 2, "minecraft:lantern"),
                cell(4, 1, 2, "minecraft:lantern"), cell(5, 1, 2, "minecraft:lantern"), cell(6, 1, 2, "minecraft:lantern"));
        assertEquals(DriftEngine.RETROFIT_COAL, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void retrofitAddsOnePerBlockPastFive() {
        CompoundTag origin = shell();
        CompoundTag now = with(origin, cell(1, 1, 2, "minecraft:lantern"), cell(2, 1, 2, "minecraft:lantern"),
                cell(3, 1, 2, "minecraft:lantern"), cell(4, 1, 2, "minecraft:lantern"), cell(5, 1, 2, "minecraft:lantern"),
                cell(6, 1, 2, "minecraft:lantern"));
        assertEquals(DriftEngine.RETROFIT_COAL + 6, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void replacedBlockCountsAsAdded() {
        CompoundTag origin = shell();
        CompoundTag now = shell();
        ListTag cells = now.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < 6; i++) cells.getCompound(i).getCompound("s").putString("Name", "minecraft:gold_block");
        assertEquals(DriftEngine.RETROFIT_COAL + 6, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void retrofitCountsItemsAddedIncludingShulkerContents() {
        CompoundTag origin = with(shell(), chest(2, 1, 2, item("minecraft:bread", 4)));
        CompoundTag now = with(shell(), chest(2, 1, 2, item("minecraft:bread", 4), item("minecraft:torch", 2), shulker(5)));
        // 2 torches + 1 shulker box + 5 diamonds inside it
        assertEquals(DriftEngine.RETROFIT_COAL + 8, DriftEngine.retrofitCoal(origin, now));
    }

    @Test
    void retrofitIgnoresItemsTaken() {
        CompoundTag origin = with(shell(), chest(2, 1, 2, item("minecraft:bread", 9)));
        CompoundTag now = with(shell(), chest(2, 1, 2, item("minecraft:bread", 1)));
        assertEquals(DriftEngine.RETROFIT_COAL, DriftEngine.retrofitCoal(origin, now));
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
        EngineSpot.Grade noStone = (x, y, z) -> EngineSpot.NONE;
        assertNull(EngineSpot.find(5, 4, 5, (x, y, z) -> solid[x][y][z], noStone));
        solid[2][2][2] = false;
        EngineSpot.Spot spot = EngineSpot.find(5, 4, 5, (x, y, z) -> solid[x][y][z], noStone);
        assertArrayEquals(new int[]{2, 1, 2}, spot.pos());
        org.junit.jupiter.api.Assertions.assertFalse(spot.replaces());
    }
}
