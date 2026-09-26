package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the End every DT world is created with to WorldWeaver's End biome source.
 *
 * <p>The vanilla End source ({@code minecraft:the_end}) is patched by both WorldWeaver and TerraBlender,
 * and TerraBlender's patch decides every biome it returns — so a world created with it has no BetterEnd
 * biomes at all (only vanilla and Biomes O' Plenty's), and the BetterEnd End band copies vanilla End.
 * {@code wover:end_biome_source} is BetterEnd's own layout.</p>
 */
final class EndPresetBiomeSourceTest {

    private static final String PRESETS = "src/main/resources/data/dungeontrain/worldgen/world_preset";
    private static final String WOVER_BIOME_CONFIG = "src/main/resources/data/wover/config/biome_config.json";

    @Test
    @DisplayName("every DT preset with an End generates it with WorldWeaver's End biome source")
    void everyEndUsesWoverEndBiomeSource() throws IOException {
        List<Path> presets = presets();
        int ends = 0;
        for (Path preset : presets) {
            JsonObject dims = read(preset).getAsJsonObject("dimensions");
            if (!dims.has("minecraft:the_end")) continue;
            ends++;
            String name = preset.getFileName().toString();
            JsonObject generator = dims.getAsJsonObject("minecraft:the_end").getAsJsonObject("generator");
            assertEquals("wover:betterx", generator.get("type").getAsString(),
                name + ": the End must use WorldWeaver's generator");
            assertEquals("minecraft:end", generator.get("settings").getAsString(),
                name + ": End noise settings stay vanilla, so the End band's island shape is unchanged");
            assertEquals("wover:end_biome_source",
                generator.getAsJsonObject("biome_source").get("type").getAsString(),
                name + ": minecraft:the_end yields no BetterEnd biomes once TerraBlender is installed");
        }
        assertTrue(ends > 0, "expected DT presets that define an End");
    }

    @Test
    @DisplayName("Biomes O' Plenty is excluded from WorldWeaver's End biomes")
    void bopIsExcludedFromTheEnd() throws IOException {
        JsonArray excluded = read(repoFile(WOVER_BIOME_CONFIG))
            .getAsJsonObject("exclude")
            .getAsJsonArray("*:is_end");
        assertFalse(excluded == null || excluded.isEmpty(), "the *:is_end exclusion list is missing");
        assertTrue(excluded.asList().stream().anyMatch(e -> e.getAsString().equals("biomesoplenty:*")),
            "BoP stays in its overworld stretch — WorldWeaver would otherwise import its End biomes");
    }

    private static List<Path> presets() throws IOException {
        try (Stream<Path> files = Files.list(repoFile(PRESETS))) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /** Walk up from the test working dir to locate a repo file (cwd varies by runner). */
    private static Path repoFile(String relative) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("'" + relative + "' not found from user.dir="
            + System.getProperty("user.dir"));
    }
}
