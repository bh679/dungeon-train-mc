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
        Map.entry("wood_oak", List.of("orange", "red", "", "cyan", "gray", "light_gray")));

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
