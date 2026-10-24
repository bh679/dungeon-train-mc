package games.brennan.dungeontrain.perks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cosmic Debris sits in the loot pools the design names, at the design's weights (docs/design/perks-earning.md). */
final class CosmicDebrisLootTest {

    private static final String ID = "dungeontrain:cosmic_debris";

    @ParameterizedTest
    @CsvSource({"loot,1", "loot_irongold,2", "netherloot,2", "ancient_vase,1", "end,3", "upsidedown,3"})
    @DisplayName("present in each band's loot pool at its weight")
    void present(String prefab, int weight) throws IOException {
        assertEquals(weight, weightIn(prefab));
    }

    @ParameterizedTest
    @ValueSource(strings = {"starter", "basic"})
    @DisplayName("absent from the first band's pools")
    void absent(String prefab) throws IOException {
        assertEquals(0, weightIn(prefab));
    }

    private static int weightIn(String prefab) throws IOException {
        Path file = RepoPaths.resources().resolve("data/dungeontrain/prefabs/loot/" + prefab + ".json");
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        int total = 0;
        for (JsonElement e : root.getAsJsonArray("entries")) {
            JsonObject entry = e.getAsJsonObject();
            if (ID.equals(entry.get("id").getAsString())) total += entry.get("weight").getAsInt();
        }
        return total;
    }
}
