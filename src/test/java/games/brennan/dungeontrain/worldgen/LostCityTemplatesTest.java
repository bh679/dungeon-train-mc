package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins DT's own Lost City templates ({@code data/dungeontrain/structure/lost_city/*.nbt}, written by
 * {@code scripts/lost-city/build-templates.py}) to what the processors and the ground handling rely on: a
 * current DataVersion, resolvable blocks, a complete pad, a clear margin, a footprint the chunk-reference
 * radius can hold, and — the one that matters most — floor and bay repeats that
 * {@link LostCityStretchProcessor#plan} actually finds at the periods the manifest declares.
 */
class LostCityTemplatesTest {

    private static final int DATA_VERSION = 3955;
    private static final int MAX_FOOTPRINT = 128;
    private static final int MAX_STRETCH_BAYS = 4;

    private static final Path DIR = RepoPaths.resources().resolve("data/dungeontrain/structure/lost_city");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private record Template(String name, JsonObject spec, CompoundTag root, List<StructureTemplate.StructureBlockInfo> blocks) {
        int[] size() {
            ListTag list = root.getList("size", Tag.TAG_INT);
            return new int[]{list.getInt(0), list.getInt(1), list.getInt(2)};
        }
    }

    private static Map<String, Template> templates() throws IOException {
        JsonObject manifest = JsonParser.parseString(Files.readString(DIR.resolve("manifest.json"))).getAsJsonObject();
        Map<String, Template> out = new TreeMap<>();
        for (String name : manifest.keySet()) {
            Path file = DIR.resolve(name + ".nbt");
            assertTrue(Files.exists(file), "manifest names " + name + " but there is no template");
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            out.put(name, new Template(name, manifest.getAsJsonObject(name), root, blocks(root)));
        }
        try (Stream<Path> files = Files.list(DIR)) {
            files.filter(p -> p.toString().endsWith(".nbt")).forEach(p -> assertTrue(
                    manifest.has(p.getFileName().toString().replace(".nbt", "")), p + " is not in the manifest"));
        }
        return out;
    }

    private static List<StructureTemplate.StructureBlockInfo> blocks(CompoundTag root) {
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            String id = entry.getString("Name");
            assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(id)), "unknown block " + id);
            states.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry));
        }
        ListTag list = root.getList("blocks", Tag.TAG_COMPOUND);
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            out.add(new StructureTemplate.StructureBlockInfo(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)),
                    states.get(b.getInt("state")), null));
        }
        return out;
    }

    @Test
    @DisplayName("every template is current, sized as declared, and made of real blocks")
    void headers() throws IOException {
        for (Template t : templates().values()) {
            assertEquals(DATA_VERSION, t.root().getInt("DataVersion"), t.name());
            int[] size = t.size();
            for (int i = 0; i < 3; i++) assertEquals(t.spec().getAsJsonArray("size").get(i).getAsInt(), size[i], t.name());
            for (StructureTemplate.StructureBlockInfo b : t.blocks()) {
                BlockPos p = b.pos();
                assertTrue(p.getX() >= 0 && p.getX() < size[0] && p.getY() >= 0 && p.getY() < size[1]
                        && p.getZ() >= 0 && p.getZ() < size[2], t.name() + " block outside size " + p);
            }
        }
    }

    @Test
    @DisplayName("the pad covers the whole footprint: natural ground the world may take, paving it may not")
    void pad() throws IOException {
        for (Template t : templates().values()) {
            int[] size = t.size();
            long padCells = t.blocks().stream().filter(b -> b.pos().getY() == 0).count();
            assertEquals((long) size[0] * size[2], padCells, t.name() + " pad incomplete");
            assertTrue(t.blocks().stream().anyMatch(b -> b.pos().getY() == 0 && LostCityGroundProcessor.isBase(b.state())),
                    t.name() + " has no natural ground on its pad");
            assertTrue(t.blocks().stream().anyMatch(b -> b.pos().getY() == 0 && !LostCityGroundProcessor.isBase(b.state())
                    && !b.state().isAir()), t.name() + " has no paving on its pad");
        }
    }

    @Test
    @DisplayName("nothing stands in the declared margin, and the footprint plus its widest stretch fits the chunk-reference radius")
    void marginAndFootprint() throws IOException {
        for (Template t : templates().values()) {
            int[] size = t.size();
            int margin = t.spec().get("margin").getAsInt();
            for (StructureTemplate.StructureBlockInfo b : t.blocks()) {
                BlockPos p = b.pos();
                if (p.getY() == 0 || b.state().isAir()) continue;
                assertFalse(p.getX() < margin || p.getZ() < margin || p.getX() >= size[0] - margin || p.getZ() >= size[2] - margin,
                        t.name() + " block inside the margin at " + p);
            }
            assertTrue(size[0] + MAX_STRETCH_BAYS * period(t, "bay_period_x") <= MAX_FOOTPRINT, t.name() + " too wide in x");
            assertTrue(size[2] + MAX_STRETCH_BAYS * period(t, "bay_period_z") <= MAX_FOOTPRINT, t.name() + " too wide in z");
        }
    }

    @Test
    @DisplayName("lost_city_stretch finds every floor and bay repeat the manifest declares, at that period")
    void stretchFindsDeclaredPeriods() throws IOException {
        for (Template t : templates().values()) {
            check(t, "floor_period", Direction.Axis.Y);
            check(t, "bay_period_x", Direction.Axis.X);
            check(t, "bay_period_z", Direction.Axis.Z);
        }
    }

    private static void check(Template t, String key, Direction.Axis axis) {
        int period = period(t, key);
        if (period == 0) return;
        LostCityStretchProcessor stretch = new LostCityStretchProcessor(1, 1, period, period, 0.45F, axis);
        LostCityStretchProcessor.Plan plan = stretch.plan(BlockPos.ZERO, t.blocks());
        assertNotNull(plan, t.name() + ": no " + axis + " band at period " + period);
        assertEquals(period, plan.band().period(), t.name() + " " + axis);
        assertTrue(plan.band().count() >= 2, t.name() + " " + axis + " repeats only " + plan.band().count() + " times");
    }

    private static int period(Template t, String key) {
        return t.spec().get(key).isJsonNull() ? 0 : t.spec().get(key).getAsInt();
    }

    @Test
    @DisplayName("templates carry no block-entity NBT and no structure blocks")
    void noBlockEntities() throws IOException {
        for (Template t : templates().values()) {
            ListTag list = t.root().getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) assertFalse(list.getCompound(i).contains("nbt"), t.name());
            assertFalse(t.blocks().stream().anyMatch(b -> b.state().is(Blocks.STRUCTURE_BLOCK)), t.name());
        }
    }
}
