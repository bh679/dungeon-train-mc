package games.brennan.dungeontrain.train;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.worldgen.BandFlavor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The band-themed {@code dirt4} clones: each swaps every dirt for its band's block, keeps
 * {@code dirt4}'s air pockets, carries its two loot cells on the edge / roof instead of the floor,
 * and is gated to its band in {@code dirt.group.json}. Also checks every {@code "bands"} token in the
 * shipped contents groups names a real {@link BandFlavor} (an unknown one fails closed, silently).
 */
final class BandDirtContentsTest {

    private static final String DATA_REL = "src/main/resources/data/dungeontrain";

    private record Clone(String block, Set<String> lootCells, String phase, String band) {}

    private static final Map<String, Clone> CLONES = Map.of(
        "veined_sand", new Clone("betternether:veined_sand", Set.of("1,3,0", "3,4,4"), "NETHER", "better_nether"),
        "dense_snow", new Clone("betterend:dense_snow", Set.of("0,2,1", "5,4,0"), "END", "better_end"),
        "echo_soil", new Clone("deeperdarker:echo_soil", Set.of("0,3,2", "2,4,0"), "DEEPER_DARK", null),
        "glowing_moss", new Clone("biomesoplenty:glowing_moss_block", Set.of("1,2,0", "4,4,4"), "OVERWORLD", "bop"));

    @Test
    void clonesSwapEveryDirtAndMoveTheLootCells() throws IOException {
        Path contents = dataDir().resolve("contents");
        JsonObject dirt4 = variants(contents.resolve("dirt4.variants.json"));
        for (Map.Entry<String, Clone> e : CLONES.entrySet()) {
            String id = e.getKey();
            Clone c = e.getValue();
            String text = Files.readString(contents.resolve(id + ".variants.json"));
            assertFalse(text.contains("\"minecraft:dirt\""), id + ": a dirt state is left in the variants");
            assertTrue(text.contains("\"" + c.block() + "\""), id + ": its block is missing from the variants");

            JsonObject v = variants(contents.resolve(id + ".variants.json"));
            assertEquals(dirt4.keySet(), v.keySet(), id + ": the variant cells differ from dirt4");
            Set<String> loot = new TreeSet<>();
            for (Map.Entry<String, JsonElement> cell : v.entrySet()) {
                if (cell.getValue().toString().contains("lootPrefab")) loot.add(cell.getKey());
            }
            assertEquals(new TreeSet<>(c.lootCells()), loot, id + ": loot cells");

            CompoundTag nbt = NbtIo.readCompressed(contents.resolve(id + ".nbt"), NbtAccounter.unlimitedHeap());
            List<String> palette = paletteNames(nbt);
            assertFalse(palette.contains("minecraft:dirt"), id + ": dirt left in the nbt palette");
            assertTrue(palette.contains(c.block()), id + ": block missing from the nbt palette");
            assertEquals(new TreeSet<>(c.lootCells()), chestCells(nbt, palette), id + ": nbt chests");
        }
    }

    @Test
    void groupGatesEachCloneToItsBand() throws IOException {
        JsonObject group = JsonParser.parseString(
            Files.readString(dataDir().resolve("contents/dirt.group.json"))).getAsJsonObject();
        for (Map.Entry<String, Clone> e : CLONES.entrySet()) {
            JsonObject member = null;
            for (JsonElement m : group.getAsJsonArray("variants")) {
                if (e.getKey().equals(m.getAsJsonObject().get("id").getAsString())) member = m.getAsJsonObject();
            }
            assertTrue(member != null, e.getKey() + " is not in dirt.group.json");
            assertEquals("[\"" + e.getValue().phase() + "\"]", member.get("phases").toString(), e.getKey() + ": phases");
            String band = e.getValue().band();
            assertEquals(band == null ? null : "[\"" + band + "\"]",
                member.has("bands") ? member.get("bands").toString() : null, e.getKey() + ": bands");
        }
    }

    @Test
    void shippedBandTokensAreKnown() throws IOException {
        List<String> unknown = new ArrayList<>();
        try (var files = Files.list(dataDir().resolve("contents"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".group.json")).toList()) {
                CarriageContentsGroup g = CarriageContentsGroup.fromJson(
                    JsonParser.parseString(Files.readString(p)).getAsJsonObject());
                for (CarriageContentsGroup.Member m : g.members()) {
                    for (String t : m.bands()) {
                        if (BandFlavor.byToken(t).isEmpty()) unknown.add(p.getFileName() + ":" + m.id() + ":" + t);
                    }
                }
            }
        }
        assertEquals(List.of(), unknown, "unknown band tokens (a member with only these never spawns)");
    }

    private static JsonObject variants(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonObject("variants");
    }

    private static List<String> paletteNames(CompoundTag nbt) {
        List<String> out = new ArrayList<>();
        ListTag palette = nbt.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < palette.size(); i++) out.add(palette.getCompound(i).getString("Name"));
        return out;
    }

    private static Set<String> chestCells(CompoundTag nbt, List<String> palette) {
        Set<String> out = new TreeSet<>();
        ListTag blocks = nbt.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            if (!"minecraft:chest".equals(palette.get(b.getInt("state")))) continue;
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            out.add(pos.getInt(0) + "," + pos.getInt(1) + "," + pos.getInt(2));
        }
        return out;
    }

    private static Path dataDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(DATA_REL);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("data dir '" + DATA_REL + "' not found from user.dir="
            + System.getProperty("user.dir"));
    }
}
