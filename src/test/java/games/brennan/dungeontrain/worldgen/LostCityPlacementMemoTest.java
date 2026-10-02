package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link LostCityPlacementMemo} to changing nothing: every real processor list that holds a stretch, bite
 * or facade, run on its real template the way vanilla runs it — once per chunk the piece touches, fresh lists
 * each time, another placement interleaved — gives exactly the blocks it gives with the memo off.
 */
class LostCityPlacementMemoTest {

    private static final Path LISTS = RepoPaths.resources().resolve("data/dungeontrain/worldgen/processor_list/lost_city");
    private static final Map<String, MapCodec<? extends StructureProcessor>> CODECS = Map.of(
            "dungeontrain:lost_city_stretch", LostCityStretchProcessor.CODEC,
            "dungeontrain:lost_city_bite", LostCityBiteProcessor.CODEC,
            "dungeontrain:lost_city_facade", LostCityFacadeProcessor.CODEC,
            "dungeontrain:lost_city_swap", LostCitySwapProcessor.CODEC,
            "dungeontrain:lost_city_truncate", LostCityTruncateProcessor.CODEC,
            "dungeontrain:lost_city_variants", LostCityVariantsProcessor.CODEC);
    private static final List<String> MEMOISED = List.of("lost_city_stretch", "lost_city_bite", "lost_city_facade");

    /** Offsets off the chunk grid, so every piece straddles chunk borders. */
    private static final BlockPos AT = new BlockPos(1000 + 7, 64, -2000 + 11);
    private static final BlockPos ELSEWHERE = new BlockPos(-3000 + 3, 70, 500 + 13);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void restore() {
        LostCityPlacementMemo.enabled = true;
        LostCityPlacementMemo.clear();
    }

    private record Case(String list, List<StructureProcessor> processors, List<StructureTemplate.StructureBlockInfo> template) {}

    private static List<Case> cases() throws IOException {
        Map<String, List<StructureTemplate.StructureBlockInfo>> templates = new HashMap<>();
        List<Case> out = new ArrayList<>();
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> s = Files.list(LISTS)) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> files.put(p.getFileName().toString().replace(".json", ""), p));
        }
        for (Map.Entry<String, Path> e : files.entrySet()) {
            String json = Files.readString(e.getValue());
            if (MEMOISED.stream().noneMatch(json::contains)) continue;
            String template = templateOf(e.getKey());
            List<StructureTemplate.StructureBlockInfo> blocks = templates.computeIfAbsent(template, LostCityPlacementMemoTest::load);
            out.add(new Case(e.getKey(), processors(JsonParser.parseString(json).getAsJsonObject()), blocks));
        }
        assertFalse(out.isEmpty(), "no processor list holds a memoised processor");
        return out;
    }

    /** The template a list dresses: the longest template name its own name starts with. */
    static String templateOf(String list) {
        try (Stream<Path> s = Files.list(LostCityTemplatesTest.DIR)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".nbt")).map(n -> n.replace(".nbt", ""))
                    .filter(n -> list.startsWith(n + "_")).max((a, b) -> a.length() - b.length())
                    .orElseThrow(() -> new AssertionError("no template for " + list));
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    static List<StructureTemplate.StructureBlockInfo> load(String name) {
        try {
            return LostCityTemplatesTest.blocks(NbtIo.readCompressed(LostCityTemplatesTest.DIR.resolve(name + ".nbt"), NbtAccounter.unlimitedHeap()));
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    static List<StructureProcessor> processors(JsonObject root) {
        List<StructureProcessor> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("processors")) {
            JsonObject p = el.getAsJsonObject();
            String type = p.get("processor_type").getAsString();
            if (type.equals("minecraft:block_ignore")) {
                List<Block> blocks = new ArrayList<>();
                for (JsonElement b : p.getAsJsonArray("blocks")) {
                    blocks.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(b.getAsJsonObject().get("Name").getAsString())));
                }
                out.add(new BlockIgnoreProcessor(blocks));
                continue;
            }
            MapCodec<? extends StructureProcessor> codec = CODECS.get(type);
            assertNotNull(codec, "unknown processor " + type);
            out.add(codec.codec().parse(JsonOps.INSTANCE, p).getOrThrow());
        }
        return out;
    }

    private static StructurePlaceSettings settings(Rotation rotation, Mirror mirror) {
        return new StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setRotationPivot(new BlockPos(9, 0, 5));
    }

    /** {@code StructureTemplate.processBlockInfos}, level-free: per-block processing, then every finalize pass. */
    static List<StructureTemplate.StructureBlockInfo> place(List<StructureProcessor> processors, BlockPos offset,
                                                            StructurePlaceSettings settings,
                                                            List<StructureTemplate.StructureBlockInfo> template) {
        Lists lists = perBlock(processors, offset, settings, template);
        return finalize(processors, offset, settings, lists);
    }

    /** The originals and processed lists the finalize passes are handed. */
    private record Lists(List<StructureTemplate.StructureBlockInfo> originals, List<StructureTemplate.StructureBlockInfo> processed) {}

    private static Lists perBlock(List<StructureProcessor> processors, BlockPos offset, StructurePlaceSettings settings,
                                  List<StructureTemplate.StructureBlockInfo> template) {
        List<StructureTemplate.StructureBlockInfo> originals = new ArrayList<>();
        List<StructureTemplate.StructureBlockInfo> processed = new ArrayList<>();
        BlockPos pivot = settings.getRotationPivot();
        for (StructureTemplate.StructureBlockInfo info : template) {
            BlockPos world = StructureTemplate.calculateRelativePosition(settings, info.pos()).offset(offset);
            StructureTemplate.StructureBlockInfo current =
                    new StructureTemplate.StructureBlockInfo(world, info.state(), info.nbt() == null ? null : info.nbt().copy());
            for (StructureProcessor p : processors) {
                if (current == null) break;
                current = p.processBlock(null, offset, pivot, info, current, settings);
            }
            if (current != null) {
                processed.add(current);
                originals.add(info);
            }
        }
        return new Lists(originals, processed);
    }

    private static List<StructureTemplate.StructureBlockInfo> finalize(List<StructureProcessor> processors, BlockPos offset,
                                                                       StructurePlaceSettings settings, Lists lists) {
        List<StructureTemplate.StructureBlockInfo> processed = new ArrayList<>(lists.processed());
        for (StructureProcessor p : processors) {
            processed = p.finalizeProcessing(null, offset, settings.getRotationPivot(), lists.originals(), processed, settings);
        }
        return processed;
    }

    /** The chunks a placed piece's blocks touch. */
    private static int chunks(List<StructureTemplate.StructureBlockInfo> placed) {
        return (int) placed.stream().mapToLong(b -> ((long) (b.pos().getX() >> 4) << 32) ^ ((b.pos().getZ() >> 4) & 0xFFFFFFFFL))
                .distinct().count();
    }

    private static void assertSameBlocks(List<StructureTemplate.StructureBlockInfo> expected,
                                         List<StructureTemplate.StructureBlockInfo> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + " block count");
        for (int i = 0; i < expected.size(); i++) {
            StructureTemplate.StructureBlockInfo e = expected.get(i), a = actual.get(i);
            assertEquals(e.pos(), a.pos(), what + " pos #" + i);
            assertSame(e.state(), a.state(), what + " state #" + i + " at " + e.pos());
            assertTrue(Objects.equals(e.nbt(), a.nbt()), what + " nbt #" + i + " at " + e.pos());
        }
    }

    @Test
    @DisplayName("memoised placement gives the unmemoised blocks on every chunk call, for every real list")
    void memoisedMatchesUnmemoised() throws IOException {
        StructurePlaceSettings[] placements = {
                settings(Rotation.NONE, Mirror.NONE), settings(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT)};
        for (Case c : cases()) {
            for (StructurePlaceSettings s : placements) {
                String what = c.list() + " " + s.getRotation() + "/" + s.getMirror();
                LostCityPlacementMemo.enabled = false;
                List<StructureTemplate.StructureBlockInfo> here = place(c.processors(), AT, s, c.template());
                List<StructureTemplate.StructureBlockInfo> there = place(c.processors(), ELSEWHERE, s, c.template());
                LostCityPlacementMemo.enabled = true;
                LostCityPlacementMemo.clear();
                long hits = LostCityPlacementMemo.HITS.get();
                int calls = Math.max(2, Math.min(4, chunks(here)));
                for (int i = 0; i < calls; i++) {
                    assertSameBlocks(here, place(c.processors(), AT, s, c.template()), what + " call " + i);
                    assertSameBlocks(there, place(c.processors(), ELSEWHERE, s, c.template()), what + " elsewhere " + i);
                }
                assertTrue(LostCityPlacementMemo.HITS.get() > hits, what + ": the memo was never hit");
            }
        }
    }

    @Test
    @DisplayName("the inverse placement transform recovers every local position, for all rotations and mirrors")
    void frameInvertsThePlacement() {
        for (Rotation r : Rotation.values()) {
            for (Mirror m : Mirror.values()) {
                StructurePlaceSettings s = settings(r, m);
                LostCityStretchProcessor.Frame frame = LostCityStretchProcessor.Frame.of(s);
                for (int x = -3; x < 40; x += 3) for (int y = 0; y < 30; y += 4) for (int z = -2; z < 50; z += 5) {
                    BlockPos local = new BlockPos(x, y, z);
                    BlockPos world = StructureTemplate.calculateRelativePosition(s, local).offset(AT);
                    assertEquals(local, frame.toLocal(world, AT), r + "/" + m);
                }
            }
        }
    }

    @Test
    @DisplayName("a different processed list misses the memo instead of reusing another's result")
    void changedListMisses() {
        LostCityBiteProcessor bite = new LostCityBiteProcessor(1, 2, 0.3F, 0.5F, List.of(Blocks.COBBLESTONE), 0.8F, 10);
        List<StructureTemplate.StructureBlockInfo> tower = new ArrayList<>();
        for (int x = 0; x < 12; x++) for (int y = 0; y < 20; y++) for (int z = 0; z < 12; z++) {
            tower.add(new StructureTemplate.StructureBlockInfo(AT.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), null));
        }
        List<StructureTemplate.StructureBlockInfo> changed = new ArrayList<>(tower);
        changed.set(500, new StructureTemplate.StructureBlockInfo(changed.get(500).pos(), Blocks.GLASS.defaultBlockState(), null));
        StructurePlaceSettings s = settings(Rotation.NONE, Mirror.NONE);
        long misses = LostCityPlacementMemo.MISSES.get();
        bite.finalizeProcessing(null, AT, BlockPos.ZERO, List.of(), tower, s);
        bite.finalizeProcessing(null, AT, BlockPos.ZERO, List.of(), tower, s);
        assertEquals(misses + 1, LostCityPlacementMemo.MISSES.get(), "the same list must hit");
        List<StructureTemplate.StructureBlockInfo> got = bite.finalizeProcessing(null, AT, BlockPos.ZERO, List.of(), changed, s);
        assertEquals(misses + 2, LostCityPlacementMemo.MISSES.get(), "a changed list must miss");
        LostCityPlacementMemo.enabled = false;
        assertSameBlocks(bite.finalizeProcessing(null, AT, BlockPos.ZERO, List.of(), changed, s), got, "changed list");
    }

    /**
     * Per-piece cost of the finalize passes across the piece's chunk calls, memo off vs on, written to
     * {@code lost-city-memo-timing.txt} under the test working directory ({@code build/minecraft-junit/build/}).
     * Informational — asserts nothing about speed.
     */
    @Test
    @DisplayName("timing: per-piece finalize cost with and without the memo")
    void timing() throws IOException {
        StringBuilder report = new StringBuilder("list  chunks  off_ms  on_ms  (median of 5; all finalize passes x chunk calls)\n");
        StructurePlaceSettings s = settings(Rotation.NONE, Mirror.NONE);
        for (Case c : cases()) {
            if (!List.of("snapped_tower_bitten", "office_tower_bronze_setback", "hospital_cream", "apartment_block_warm")
                    .contains(c.list())) continue;
            int calls = chunks(place(c.processors(), AT, s, c.template()));
            double off = median(c, s, calls, false), on = median(c, s, calls, true);
            report.append(String.format("%s  %d  %.1f  %.1f%n", c.list(), calls, off, on));
        }
        Path out = Path.of("build/lost-city-memo-timing.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    private static double median(Case c, StructurePlaceSettings s, int calls, boolean memo) {
        LostCityPlacementMemo.enabled = memo;
        double[] runs = new double[5];
        for (int r = 0; r < runs.length; r++) {
            LostCityPlacementMemo.clear();
            long total = 0;
            for (int i = 0; i < calls; i++) {
                Lists lists = perBlock(c.processors(), AT, s, c.template());      // vanilla's own share, not timed
                long t0 = System.nanoTime();
                finalize(c.processors(), AT, s, lists);
                total += System.nanoTime() - t0;
            }
            runs[r] = total / 1e6;
        }
        Arrays.sort(runs);
        return runs[runs.length / 2];
    }
}
