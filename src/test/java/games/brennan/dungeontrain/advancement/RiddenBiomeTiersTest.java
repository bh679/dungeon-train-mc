package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The riding biome tiers were raised to count every biome the train meets — modded and the train's own
 * Nether and End among them. New ids reset everyone who earned the old, lower tiers; the old display keys
 * are kept so every translation carries over, and each derived hint key exists in English.
 */
final class RiddenBiomeTiersTest {

    private static final Path DIR = RepoPaths.advancements().resolve("dungeon_train");
    private static final Map<String, String[]> TIERS = Map.of(
            "ridden_biomes_25", new String[] {"25", "biomes_10"},
            "ridden_biomes_50", new String[] {"50", "biomes_17"},
            "ridden_biomes_100", new String[] {"100", "biomes_25"},
            "ridden_biomes_150", new String[] {"150", "all_biome_families"});

    @Test
    @DisplayName("each tier: new id, new threshold, old title key, a hint for its own id")
    void tiers() throws IOException {
        JsonObject en = JsonParser.parseString(Files.readString(RepoPaths.langFile("en_us"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (Map.Entry<String, String[]> t : TIERS.entrySet()) {
            Path file = DIR.resolve(t.getKey() + ".json");
            assertTrue(Files.isRegularFile(file), "missing " + file);
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(Integer.parseInt(t.getValue()[0]), json.getAsJsonObject("criteria").getAsJsonObject("milestone")
                    .getAsJsonObject("conditions").get("threshold").getAsInt(), t.getKey());
            assertEquals("advancements.dungeontrain.dungeon_train." + t.getValue()[1] + ".title",
                    json.getAsJsonObject("display").getAsJsonObject("title").get("translate").getAsString());
            assertTrue(en.has("advancements.dungeontrain.dungeon_train." + t.getKey() + ".hint"), t.getKey() + " hint");
            assertFalse(Files.exists(DIR.resolve(t.getValue()[1] + ".json")), "old id still ships: " + t.getValue()[1]);
        }
    }
}
