package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.block.VariantPlaceholderBlock;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.worldgen.LostCityVariantsProcessor.Phase;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LostCityVariantsProcessor} and {@link LostCityVariantDocs}: the committed documents load whole,
 * a building without one (or with the setting off) is untouched, a roll is a pure function of the
 * placement, stretch copies roll on their own while lock groups match, columns grow against the piece,
 * and no placeholder or tag ever reaches the placed blocks. Needs the bootstrap.
 */
final class LostCityVariantsProcessorTest {

    private static final Path LISTS = RepoPaths.resources().resolve("data/dungeontrain/worldgen/processor_list/lost_city");
    private static final ResourceLocation TOWER = ResourceLocation.fromNamespaceAndPath("dungeontrain", "lost_city/test_tower");
    private static final BlockPos AT = new BlockPos(1000 + 7, 64, -2000 + 11);
    private static final BlockPos ELSEWHERE = new BlockPos(-3000 + 3, 70, 500 + 13);

    private static final int PERIOD = 5;
    private static final int FLOORS = 6;
    /** The layer the first floor slab sits on; a slab every {@link #PERIOD} above it. */
    private static final int BASE = 3;
    private static final String WOOLS = "[\"minecraft:white_wool\", \"minecraft:orange_wool\", \"minecraft:magenta_wool\","
            + " \"minecraft:light_blue_wool\", \"minecraft:yellow_wool\", \"minecraft:lime_wool\", \"minecraft:pink_wool\","
            + " \"minecraft:gray_wool\"]";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void on() {
        LostCityVariantsProcessor.enabled = () -> true;
    }

    @AfterEach
    void restore() {
        LostCityVariantsProcessor.enabled = DungeonTrainCommonConfig::isLostCityBlockVariants;
        LostCityVariantDocs.publish(Map.of());
        LostCityPlacementMemo.enabled = true;
        LostCityPlacementMemo.clear();
    }

    // ---- helpers ----

    private static StructurePlaceSettings settings(List<StructureProcessor> processors, Rotation rotation, Mirror mirror) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror)
                .setRotationPivot(new BlockPos(9, 0, 5));
        processors.forEach(settings::addProcessor);
        return settings;
    }

    private static List<StructureBlockInfo> place(List<StructureProcessor> processors, BlockPos offset, List<StructureBlockInfo> template) {
        return place(processors, offset, template, Rotation.NONE, Mirror.NONE);
    }

    private static List<StructureBlockInfo> place(List<StructureProcessor> processors, BlockPos offset, List<StructureBlockInfo> template,
                                                  Rotation rotation, Mirror mirror) {
        return LostCityPlacementMemoTest.place(processors, offset, settings(processors, rotation, mirror), template);
    }

    private static List<StructureProcessor> variants(ResourceLocation template, StructureProcessor... between) {
        List<StructureProcessor> out = new ArrayList<>();
        out.add(new LostCityVariantsProcessor(template, Phase.MARK));
        out.addAll(List.of(between));
        out.add(new LostCityVariantsProcessor(template, Phase.ROLL));
        return out;
    }

    private static Map<BlockPos, BlockState> byPos(List<StructureBlockInfo> placed, BlockPos origin) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (StructureBlockInfo info : placed) out.put(info.pos().subtract(origin), info.state());
        return out;
    }

    private static void publish(ResourceLocation template, String json) {
        LostCityVariantDocs.Doc doc = LostCityVariantDocs.parse(template, json);
        assertNotNull(doc, "document parsed to nothing");
        LostCityVariantDocs.publish(Map.of(template, doc));
    }

    private static String doc(String cells) {
        return "{\"schemaVersion\": 10, \"variants\": {" + cells + "}}";
    }

    private static boolean slab(int y) {
        return y >= BASE && (y - BASE) % PERIOD == 0;
    }

    /** A 9×9 glass tower of {@link #FLOORS} floors: a stone slab every {@link #PERIOD} layers, air inside, on a grass pad. */
    private static List<StructureBlockInfo> tower() {
        List<StructureBlockInfo> out = new ArrayList<>();
        int height = BASE + FLOORS * PERIOD + 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 9; x++) {
                for (int z = 0; z < 9; z++) {
                    boolean edge = x == 0 || x == 8 || z == 0 || z == 8;
                    Block b;
                    if (y == 0) b = Blocks.GRASS_BLOCK;
                    else if (y < BASE) b = Blocks.BRICKS;
                    else if (y >= height - 2) b = Blocks.STONE;
                    else if (slab(y)) b = Blocks.STONE;
                    else if (edge) b = (x + z) % 4 == 0 ? Blocks.STONE : Blocks.GLASS;
                    else b = Blocks.AIR;
                    out.add(new StructureBlockInfo(new BlockPos(x, y, z), b.defaultBlockState(), null));
                }
            }
        }
        return out;
    }

    /** The first layer of the stretch's first repeat for which {@code wanted} holds. */
    private static int layerInFirstRepeat(LostCityStretchProcessor.Plan plan, java.util.function.IntPredicate wanted) {
        for (int y = plan.band().start(); y < plan.band().start() + PERIOD; y++) {
            if (wanted.test(y)) return y;
        }
        throw new AssertionError("no such layer in the first repeat");
    }

    private static Map<String, List<StructureBlockInfo>> realTemplates() throws IOException {
        Map<String, List<StructureBlockInfo>> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(LostCityTemplatesTest.DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".nbt")).toList()) {
                String name = p.getFileName().toString().replace(".nbt", "");
                out.put(name, LostCityPlacementMemoTest.load(name));
            }
        }
        return out;
    }

    private static ResourceLocation id(String building) {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", "lost_city/" + building);
    }

    private static Map<ResourceLocation, LostCityVariantDocs.Doc> realDocs(Set<String> buildings) throws IOException {
        Map<ResourceLocation, LostCityVariantDocs.Doc> docs = new HashMap<>();
        for (String name : buildings) {
            Path file = LostCityTemplatesTest.DIR.resolve(name + LostCityVariantDocs.SUFFIX);
            if (!Files.exists(file)) continue;
            LostCityVariantDocs.Doc doc = LostCityVariantDocs.parse(id(name), Files.readString(file));
            assertNotNull(doc, name + " document parsed to nothing");
            docs.put(id(name), doc);
        }
        return docs;
    }

    private static List<StructureProcessor> realList(String list) throws IOException {
        return LostCityPlacementMemoTest.processors(JsonParser.parseString(Files.readString(LISTS.resolve(list + ".json"))).getAsJsonObject());
    }

    private static void assertSameBlocks(List<StructureBlockInfo> expected, List<StructureBlockInfo> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + " block count");
        for (int i = 0; i < expected.size(); i++) {
            StructureBlockInfo e = expected.get(i), a = actual.get(i);
            assertEquals(e.pos(), a.pos(), what + " pos #" + i);
            assertSame(e.state(), a.state(), what + " state #" + i + " at " + e.pos());
            assertTrue(Objects.equals(e.nbt(), a.nbt()), what + " nbt #" + i + " at " + e.pos());
        }
    }

    // ---- the committed documents ----

    @Test
    @DisplayName("every building's document loads whole, round-trips, and names only real cells above the pad")
    void documentsRoundTrip() throws IOException {
        Map<String, List<StructureBlockInfo>> templates = realTemplates();
        int documents = 0;
        for (Map.Entry<String, List<StructureBlockInfo>> t : templates.entrySet()) {
            Path file = LostCityTemplatesTest.DIR.resolve(t.getKey() + LostCityVariantDocs.SUFFIX);
            if (!Files.exists(file)) continue;
            documents++;
            String json = Files.readString(file);
            JsonObject raw = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("variants");
            LostCityVariantDocs.Doc doc = LostCityVariantDocs.parse(id(t.getKey()), json);
            assertNotNull(doc, t.getKey());
            assertEquals(raw.size(), doc.cells().size(), t.getKey() + ": a cell was dropped on load");

            Set<BlockPos> cells = new HashSet<>();
            for (StructureBlockInfo info : t.getValue()) cells.add(info.pos());
            for (Map.Entry<String, JsonElement> cell : raw.entrySet()) {
                String[] xyz = cell.getKey().split(",");
                BlockPos pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                assertTrue(pos.getY() >= 1, t.getKey() + " cell " + cell.getKey() + " is on the pad");
                assertTrue(cells.contains(pos), t.getKey() + " cell " + cell.getKey() + " is not a template cell");
                JsonElement value = cell.getValue();
                int written = (value.isJsonObject() ? value.getAsJsonObject().getAsJsonArray("states") : value.getAsJsonArray()).size();
                assertEquals(written, doc.blocks().statesAt(pos).size(), t.getKey() + " cell " + cell.getKey() + ": a candidate was dropped on load");
            }

            LostCityVariantDocs.Doc again = LostCityVariantDocs.parse(id(t.getKey()), doc.blocks().asJsonText());
            assertNotNull(again);
            assertEquals(doc.cells(), again.cells(), t.getKey() + " cells after a round trip");
            for (CarriageVariantBlocks.Entry entry : doc.blocks().entries()) {
                List<VariantState> back = again.blocks().statesAt(entry.localPos());
                assertEquals(entry.states().size(), back.size());
                for (int i = 0; i < back.size(); i++) {
                    assertSame(entry.states().get(i).state(), back.get(i).state());
                    assertEquals(entry.states().get(i).weight(), back.get(i).weight());
                    assertEquals(entry.states().get(i).growth(), back.get(i).growth());
                }
                assertEquals(doc.blocks().lockIdAt(entry.localPos()), again.blocks().lockIdAt(entry.localPos()));
            }
        }
        assertTrue(documents > 0, "no building has a variants document");
    }

    @Test
    @DisplayName("a document resource maps to its template id")
    void templateIds() {
        assertEquals(id("office_tower"), LostCityVariantDocs.templateOf(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "structure/lost_city/office_tower.variants.json")));
    }

    // ---- no-op cases ----

    @Test
    @DisplayName("no document: both phases hand back exactly what they were given")
    void noDocumentIsUntouched() {
        List<StructureBlockInfo> template = tower();
        List<StructureProcessor> processors = variants(TOWER);
        assertSameBlocks(place(List.of(), AT, template), place(processors, AT, template), "no document");

        List<StructureBlockInfo> processed = place(List.of(), AT, template);
        assertSame(processed, processors.get(1).finalizeProcessing(null, AT, BlockPos.ZERO, template, processed,
                settings(processors, Rotation.NONE, Mirror.NONE)));
    }

    @Test
    @DisplayName("setting off: a real building with a document generates as it does without the processor")
    void settingOffIsUntouched() throws IOException {
        LostCityVariantDocs.publish(realDocs(Set.of("office_tower")));
        List<StructureBlockInfo> template = LostCityPlacementMemoTest.load("office_tower");
        List<StructureProcessor> list = realList("office_tower_dark_tall");
        List<StructureProcessor> without = list.stream().filter(p -> !(p instanceof LostCityVariantsProcessor)).toList();
        assertTrue(without.size() < list.size(), "the list carries no variants processor");

        LostCityVariantsProcessor.enabled = () -> false;
        assertSameBlocks(place(without, AT, template), place(list, AT, template), "setting off");
    }

    // ---- the roll ----

    @Test
    @DisplayName("same offset → same roll; another offset → a different one")
    void deterministicPerPlacement() throws IOException {
        LostCityVariantDocs.publish(realDocs(Set.of("office_tower")));
        List<StructureBlockInfo> template = LostCityPlacementMemoTest.load("office_tower");
        List<StructureProcessor> list = realList("office_tower_shipped");

        List<StructureBlockInfo> first = place(list, AT, template);
        LostCityPlacementMemo.clear();
        assertSameBlocks(first, place(list, AT, template), "same offset");

        Map<BlockPos, BlockState> here = byPos(first, AT), there = byPos(place(list, ELSEWHERE, template), ELSEWHERE);
        Map<BlockPos, BlockState> shipped = byPos(place(List.of(), AT, template), AT);
        int rolled = 0, differ = 0;
        Set<BlockPos> all = new HashSet<>(here.keySet());
        all.addAll(there.keySet());
        for (BlockPos pos : all) {
            if (!Objects.equals(here.get(pos), shipped.get(pos))) rolled++;
            if (!Objects.equals(here.get(pos), there.get(pos))) differ++;
        }
        assertTrue(rolled > 20, "the roll changed only " + rolled + " blocks of the shipped tower");
        assertTrue(differ > 20, "two placements differ in only " + differ + " blocks");
    }

    @Test
    @DisplayName("the memo changes nothing")
    void memoIsTransparent() throws IOException {
        LostCityVariantDocs.publish(realDocs(Set.of("office_tower")));
        List<StructureBlockInfo> template = LostCityPlacementMemoTest.load("office_tower");
        List<StructureProcessor> list = realList("office_tower_dark_tall");

        LostCityPlacementMemo.enabled = false;
        List<StructureBlockInfo> reference = place(list, AT, template, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK);
        LostCityPlacementMemo.enabled = true;
        long hits = LostCityPlacementMemo.HITS.get();
        for (int call = 0; call < 3; call++) {
            assertSameBlocks(reference, place(list, AT, template, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK), "memo call " + call);
        }
        assertTrue(LostCityPlacementMemo.HITS.get() > hits, "the memo was never hit");
    }

    @Test
    @DisplayName("a stretched building: every added floor rolls on its own, a lock group matches throughout")
    void stretchCopiesRollIndependently() {
        List<StructureBlockInfo> template = tower();
        LostCityStretchProcessor stretch = new LostCityStretchProcessor(6, 6, PERIOD, PERIOD, 0.45F, Direction.Axis.Y);
        LostCityStretchProcessor.Plan plan = stretch.plan(AT, template);
        assertNotNull(plan, "the tower has no band");
        assertEquals(6, plan.delta());
        int y = layerInFirstRepeat(plan, layer -> !slab(layer));      // a wall layer: air inside
        publish(TOWER, doc("\"4," + y + ",4\": " + WOOLS
                + ", \"2," + y + ",2\": {\"lockId\": 1, \"states\": " + WOOLS + "}"
                + ", \"6," + y + ",6\": {\"lockId\": 1, \"states\": " + WOOLS + "}"));

        List<StructureBlockInfo> placed = place(variants(TOWER, stretch), AT, template);
        Map<BlockPos, BlockState> at = byPos(placed, AT);
        Set<BlockState> free = new HashSet<>(), locked = new HashSet<>();
        for (int k = 0; k <= 6; k++) {
            int floor = y + k * PERIOD;
            BlockState pick = at.get(new BlockPos(4, floor, 4));
            assertTrue(pick.getBlock().toString().contains("wool"), "floor " + k + " was not rolled: " + pick);
            free.add(pick);
            BlockState a = at.get(new BlockPos(2, floor, 2)), b = at.get(new BlockPos(6, floor, 6));
            assertSame(a, b, "lock group split on floor " + k);
            locked.add(a);
        }
        assertTrue(free.size() > 1, "every copy of the unlocked cell rolled the same block");
        assertEquals(1, locked.size(), "the lock group rolled differently on a copy");
        for (StructureBlockInfo info : placed) assertFalse(LostCityVariantsProcessor.isMarked(info), "a tag survived at " + info.pos());
    }

    @Test
    @DisplayName("a growth entry hangs a column into free space, stops at what is there, and moves with a stretch")
    void growthColumns() {
        List<StructureBlockInfo> template = tower();
        LostCityStretchProcessor stretch = new LostCityStretchProcessor(2, 2, PERIOD, PERIOD, 0.45F, Direction.Axis.Y);
        LostCityStretchProcessor.Plan plan = stretch.plan(AT, template);
        assertNotNull(plan);
        int top = layerInFirstRepeat(plan, layer -> slab(layer + 1));  // just under a slab: four air layers down to the floor
        String vines = "[{\"state\": \"minecraft:vine[north=true]\", \"growth\": \"down 8-8\"},"
                + " {\"state\": \"minecraft:vine[south=true]\", \"growth\": \"down 8-8\"}]";
        publish(TOWER, doc("\"4," + top + ",4\": " + vines));

        Map<BlockPos, BlockState> at = byPos(place(variants(TOWER, stretch), AT, template), AT);
        Map<BlockPos, BlockState> plain = byPos(place(List.of(stretch), AT, template), AT);
        for (int k = 0; k <= 2; k++) {
            int head = top + k * PERIOD;
            for (int down = 0; down < PERIOD - 1; down++) {
                assertSame(Blocks.VINE, at.get(new BlockPos(4, head - down, 4)).getBlock(), "floor " + k + ", " + down + " below the cell");
            }
            assertSame(Blocks.STONE, at.get(new BlockPos(4, head - (PERIOD - 1), 4)).getBlock(), "the column overwrote the floor under it");
        }
        int changed = 0;
        for (Map.Entry<BlockPos, BlockState> e : at.entrySet()) if (e.getValue() != plain.get(e.getKey())) changed++;
        assertEquals(3 * (PERIOD - 1), changed, "blocks other than the three columns changed");
        assertEquals(plain.keySet(), at.keySet(), "the roll added or removed positions");
    }

    @Test
    @DisplayName("an empty pick and a placeholder leave air; a swap in the list covers the pick too")
    void emptyAndSwappedPicks() {
        List<StructureBlockInfo> template = tower();
        publish(TOWER, doc("\"4,5,4\": [\"dungeontrain:variant_placeholder\", \"minecraft:command_block\"],"
                + " \"5,5,5\": [\"minecraft:white_wool\", \"minecraft:white_wool\"],"
                + " \"3,5,3\": [\"minecraft:orange_wool\", \"minecraft:orange_wool\"]"));
        LostCitySwapProcessor swap = new LostCitySwapProcessor(List.of(
                new LostCitySwapProcessor.Swap(Blocks.WHITE_WOOL, Blocks.BLACK_WOOL, 1.0F),
                new LostCitySwapProcessor.Swap(Blocks.ORANGE_WOOL, Blocks.AIR, 1.0F)));

        Map<BlockPos, BlockState> at = byPos(place(variants(TOWER, swap), AT, template), AT);
        assertTrue(at.get(new BlockPos(4, 5, 4)).isAir(), "an empty pick is air");
        assertSame(Blocks.BLACK_WOOL, at.get(new BlockPos(5, 5, 5)).getBlock(), "the list's recolour reaches a pick");
        assertFalse(at.containsKey(new BlockPos(3, 5, 3)), "a pick swapped to air leaves the piece, as a template block does");
    }

    // ---- the whole pipeline ----

    @Test
    @DisplayName("every real list on its real template: no placeholder, stage block or tag reaches the placed blocks")
    void nothingSurvivesThePipeline() throws IOException {
        Map<String, List<StructureBlockInfo>> templates = realTemplates();
        LostCityVariantDocs.publish(realDocs(templates.keySet()));
        Map<String, Path> lists = new TreeMap<>();
        try (Stream<Path> s = Files.list(LISTS)) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> lists.put(p.getFileName().toString().replace(".json", ""), p));
        }
        int rolling = 0;
        for (String list : lists.keySet()) {
            List<StructureProcessor> processors = realList(list);
            if (processors.stream().noneMatch(p -> p instanceof LostCityVariantsProcessor)) continue;
            rolling++;
            List<StructureBlockInfo> template = templates.get(LostCityPlacementMemoTest.templateOf(list));
            for (int run = 0; run < 2; run++) {
                Rotation rotation = run == 0 ? Rotation.NONE : Rotation.COUNTERCLOCKWISE_90;
                Mirror mirror = run == 0 ? Mirror.NONE : Mirror.LEFT_RIGHT;
                for (StructureBlockInfo info : place(processors, run == 0 ? AT : ELSEWHERE, template, rotation, mirror)) {
                    BlockState state = info.state();
                    assertFalse(state.getBlock() instanceof VariantPlaceholderBlock, list + ": placeholder at " + info.pos());
                    assertFalse(CarriageVariantBlocks.isEmptyPlaceholder(state), list + ": legacy placeholder at " + info.pos());
                    assertFalse(StagePlaceholderBlocks.isPlaceholder(state), list + ": stage placeholder at " + info.pos());
                    assertFalse(LostCityVariantsProcessor.isMarked(info), list + ": tag at " + info.pos());
                }
                LostCityPlacementMemo.clear();
            }
        }
        assertTrue(rolling >= 16, "only " + rolling + " lists carry the variants processor");
    }

    @Test
    @DisplayName("a dry design stays dry: its swaps strip the vines a pick would hang")
    void dryDesignStaysDry() throws IOException {
        LostCityVariantDocs.publish(realDocs(Set.of("office_tower")));
        List<StructureBlockInfo> template = LostCityPlacementMemoTest.load("office_tower");
        for (StructureBlockInfo info : place(realList("office_tower_dry"), AT, template)) {
            assertNotEquals(Blocks.VINE, info.state().getBlock(), "a vine at " + info.pos());
        }
    }
}
