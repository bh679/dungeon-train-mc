package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link WorldInfoReporter#buildPayload}. The world-info telemetry record must carry
 * the world + train seeds as JSON <b>strings</b> (they are 64-bit longs — JSON numbers would lose
 * precision on the relay's JavaScript parse), nest carriage dims under {@code {l,w,h}}, and list mods as
 * an ordered array of {@code {modId,version}} objects. Pure — no running server needed.
 */
class WorldInfoReporterTest {

    private static JsonObject sample(long worldSeed, long trainSeed, List<WorldInfoReporter.ModEntry> mods) {
        return WorldInfoReporter.buildPayload(
                "069a79f444e94726a5befca90e38aaf5", "Notch",
                worldSeed, trainSeed,
                "RANDOM_GROUPED", 3,
                9, 7, 7,
                245,
                "OVERWORLD",
                "0.391.1",
                "CurseForge · brand minecraft v2.4.28",
                mods);
    }

    @Test
    @DisplayName("seeds are emitted as JSON strings with exact 64-bit precision (no number rounding)")
    void seedsAreExactStrings() {
        long bigWorld = 9007199254740993L;    // 2^53 + 1: unrepresentable as a JS double, must survive as text
        long negTrain = -9007199254740993L;

        JsonObject out = sample(bigWorld, negTrain, List.of());

        assertTrue(out.getAsJsonPrimitive("worldSeed").isString(), "worldSeed must be a JSON string");
        assertTrue(out.getAsJsonPrimitive("trainSeed").isString(), "trainSeed must be a JSON string");
        assertEquals("9007199254740993", out.get("worldSeed").getAsString());
        assertEquals("-9007199254740993", out.get("trainSeed").getAsString());
        // Prove the serialized form is quoted (a string), not a bare JSON number.
        assertTrue(out.toString().contains("\"worldSeed\":\"9007199254740993\""), out.toString());
        assertTrue(out.toString().contains("\"trainSeed\":\"-9007199254740993\""), out.toString());
    }

    @Test
    @DisplayName("dims nest as {l,w,h}; groupSize and trainY stay numeric")
    void dimsAndNumbers() {
        JsonObject out = sample(1L, 2L, List.of());

        JsonObject dims = out.getAsJsonObject("dims");
        assertEquals(9, dims.get("l").getAsInt());
        assertEquals(7, dims.get("w").getAsInt());
        assertEquals(7, dims.get("h").getAsInt());
        assertTrue(out.getAsJsonPrimitive("groupSize").isNumber(), "groupSize must be a JSON number");
        assertEquals(3, out.get("groupSize").getAsInt());
        assertTrue(out.getAsJsonPrimitive("trainY").isNumber(), "trainY must be a JSON number");
        assertEquals(245, out.get("trainY").getAsInt());
    }

    @Test
    @DisplayName("mods serialize as an ordered array of {modId,version} objects")
    void modsArrayPreservesOrder() {
        JsonObject out = sample(1L, 2L, List.of(
                new WorldInfoReporter.ModEntry("dungeontrain", "0.391.1"),
                new WorldInfoReporter.ModEntry("sable", "2.0.2+mc1.21.1")));

        JsonArray arr = out.getAsJsonArray("mods");
        assertEquals(2, arr.size());
        assertEquals("dungeontrain", arr.get(0).getAsJsonObject().get("modId").getAsString());
        assertEquals("0.391.1", arr.get(0).getAsJsonObject().get("version").getAsString());
        assertEquals("sable", arr.get(1).getAsJsonObject().get("modId").getAsString());
        assertEquals("2.0.2+mc1.21.1", arr.get(1).getAsJsonObject().get("version").getAsString());
    }

    @Test
    @DisplayName("scalar fields carry through verbatim")
    void scalarFields() {
        JsonObject out = sample(1L, 2L, List.of());

        assertEquals("069a79f444e94726a5befca90e38aaf5", out.get("uuid").getAsString());
        assertEquals("Notch", out.get("player").getAsString());
        assertEquals("RANDOM_GROUPED", out.get("mode").getAsString());
        assertEquals("OVERWORLD", out.get("startingDimension").getAsString());
        assertEquals("0.391.1", out.get("dtVersion").getAsString());
        assertEquals("CurseForge · brand minecraft v2.4.28", out.get("launcher").getAsString());
    }

    @Test
    @DisplayName("empty mod list still produces a valid empty array")
    void emptyModsArray() {
        JsonObject out = sample(1L, 2L, List.of());
        assertTrue(out.has("mods"));
        assertEquals(0, out.getAsJsonArray("mods").size());
    }
}
