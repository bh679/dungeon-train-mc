package games.brennan.dungeontrain.template;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped per-stage terracotta / concrete colours in {@code stages.json}, as designed. */
final class StageColourPalettesTest {

    /**
     * Stage → {terracotta primary, secondary, background, concrete primary, secondary, background};
     * an empty terracotta entry is the undyed ("regular") terracotta.
     */
    private static final Map<String, List<String>> EXPECTED = Map.ofEntries(
        Map.entry("acacia", List.of("orange", "", "cyan", "orange", "brown", "white")),
        Map.entry("bamboo", List.of("green", "lime", "yellow", "green", "lime", "yellow")),
        Map.entry("bamboo_mosaic", List.of("green", "lime", "yellow", "green", "lime", "yellow")),
        Map.entry("birch", List.of("white", "yellow", "", "white", "light_gray", "gray")),
        Map.entry("cherry", List.of("pink", "magenta", "purple", "pink", "black", "white")),
        Map.entry("copper", List.of("orange", "", "cyan", "orange", "cyan", "gray")),
        Map.entry("crimson", List.of("magenta", "red", "purple", "red", "magenta", "light_gray")),
        Map.entry("darkwood", List.of("black", "brown", "gray", "black", "brown", "gray")),
        Map.entry("deepdark", List.of("cyan", "blue", "gray", "cyan", "gray", "black")),
        Map.entry("deeper_dark", List.of("cyan", "black", "blue", "cyan", "black", "blue")),
        Map.entry("desert", List.of("orange", "blue", "purple", "black", "orange", "cyan")),
        Map.entry("jungle", List.of("brown", "", "cyan", "green", "brown", "gray")),
        Map.entry("mangrove", List.of("red", "pink", "brown", "red", "brown", "white")),
        Map.entry("mud", List.of("", "brown", "black", "gray", "green", "black")),
        Map.entry("nether", List.of("red", "", "black", "red", "light_blue", "black")),
        Map.entry("obsidian", List.of("white", "blue", "black", "black", "cyan", "white")),
        Map.entry("quartz", List.of("white", "blue", "black", "white", "purple", "black")),
        Map.entry("spruce", List.of("brown", "", "black", "brown", "black", "gray")),
        Map.entry("stone", List.of("brown", "lime", "cyan", "brown", "gray", "light_gray")),
        Map.entry("warped", List.of("purple", "blue", "cyan", "cyan", "light_blue", "light_gray")),
        Map.entry("wood_oak", List.of("orange", "red", "", "cyan", "gray", "light_gray")),
        Map.entry("pale_oak", List.of("light_gray", "white", "gray", "white", "light_gray", "gray")),
        Map.entry("fir", List.of("green", "brown", "white", "green", "white", "brown")),
        Map.entry("pine", List.of("brown", "green", "black", "green", "brown", "black")),
        Map.entry("redwood", List.of("red", "brown", "orange", "red", "brown", "black")),
        Map.entry("mahogany", List.of("red", "brown", "pink", "red", "brown", "white")),
        Map.entry("jacaranda", List.of("purple", "magenta", "white", "purple", "magenta", "white")),
        Map.entry("palm", List.of("yellow", "orange", "white", "yellow", "light_blue", "white")),
        Map.entry("willow", List.of("lime", "green", "brown", "lime", "green", "gray")),
        Map.entry("dead", List.of("gray", "light_gray", "black", "gray", "light_gray", "black")),
        Map.entry("magic", List.of("blue", "light_blue", "cyan", "blue", "light_blue", "white")),
        Map.entry("maple", List.of("red", "orange", "yellow", "red", "orange", "brown")),
        Map.entry("umbran", List.of("blue", "purple", "black", "blue", "purple", "black")),
        Map.entry("hellbark", List.of("red", "orange", "black", "red", "orange", "black")),
        Map.entry("empyreal", List.of("light_blue", "white", "yellow", "light_blue", "white", "yellow")),
        Map.entry("anchor_tree", List.of("cyan", "green", "black", "cyan", "green", "black")),
        Map.entry("gloomwood", List.of("gray", "cyan", "black", "gray", "cyan", "black")),
        Map.entry("gloomwood_dark", List.of("black", "gray", "purple", "black", "gray", "purple")),
        Map.entry("mushroom_fir", List.of("brown", "light_gray", "red", "brown", "red", "white")),
        Map.entry("nether_mushroom", List.of("red", "white", "brown", "red", "white", "brown")),
        Map.entry("nether_reed", List.of("cyan", "lime", "black", "cyan", "lime", "gray")),
        Map.entry("nether_sakura", List.of("pink", "magenta", "white", "pink", "magenta", "white")),
        Map.entry("rubeus", List.of("red", "orange", "black", "red", "orange", "gray")),
        Map.entry("stalagnate", List.of("lime", "yellow", "brown", "lime", "yellow", "brown")),
        Map.entry("wart", List.of("red", "magenta", "black", "red", "black", "gray")),
        Map.entry("nether_willow", List.of("red", "brown", "black", "brown", "red", "black")),
        Map.entry("dragon_tree", List.of("purple", "magenta", "black", "purple", "magenta", "black")),
        Map.entry("helix_tree", List.of("orange", "yellow", "brown", "orange", "yellow", "white")),
        Map.entry("lacugrove", List.of("green", "lime", "cyan", "green", "lime", "cyan")),
        Map.entry("lucernia", List.of("orange", "red", "black", "orange", "red", "gray")),
        Map.entry("pythadendron", List.of("purple", "pink", "white", "purple", "pink", "white")),
        Map.entry("tenanea", List.of("pink", "magenta", "purple", "pink", "magenta", "white")),
        Map.entry("end_lotus", List.of("light_blue", "cyan", "white", "light_blue", "cyan", "white")),
        Map.entry("jellyshroom", List.of("purple", "light_blue", "black", "purple", "light_blue", "black")),
        Map.entry("mossy_glowshroom", List.of("light_blue", "green", "gray", "light_blue", "green", "gray")),
        Map.entry("umbrella_tree", List.of("green", "light_blue", "black", "green", "light_blue", "gray")));

    private static final Set<String> DYES = Set.of("white", "orange", "magenta", "light_blue", "yellow",
        "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    @Test
    @DisplayName("every stage carries its designed terracotta and concrete colours")
    void shippedColours() throws IOException {
        JsonObject stages = JsonParser.parseString(Files.readString(
            RepoPaths.resources().resolve("data/dungeontrain/stages.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(EXPECTED.keySet(), stages.keySet());
        for (Map.Entry<String, List<String>> e : EXPECTED.entrySet()) {
            StagePalette pal = StagePalette.fromJson(stages.getAsJsonObject(e.getKey()).get("palette"));
            List<String> want = e.getValue();
            for (int i = 0; i < 3; i++) {
                String t = want.get(i);
                assertEquals(t.isEmpty() ? "minecraft:terracotta" : "minecraft:" + t + "_terracotta",
                    pal.terracotta(i), e.getKey() + " terracotta " + i);
                assertEquals("minecraft:" + want.get(3 + i) + "_concrete", pal.concrete(i), e.getKey() + " concrete " + i);
            }
            for (String dye : want) assertTrue(dye.isEmpty() || DYES.contains(dye), dye);
        }
    }

    @Test
    @DisplayName("glazed terracotta picked in the editor is kept as an override")
    void glazedOverrides() throws IOException {
        JsonObject stages = JsonParser.parseString(Files.readString(
            RepoPaths.resources().resolve("data/dungeontrain/stages.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> want = Map.of(
            "birch", "minecraft:white_glazed_terracotta",
            "wood_oak", "minecraft:orange_glazed_terracotta");
        for (Map.Entry<String, String> e : want.entrySet()) {
            StagePalette pal = StagePalette.fromJson(stages.getAsJsonObject(e.getKey()).get("palette"));
            assertEquals(e.getValue(), pal.override("stage_glazed_terracotta"), e.getKey());
        }
    }
}
