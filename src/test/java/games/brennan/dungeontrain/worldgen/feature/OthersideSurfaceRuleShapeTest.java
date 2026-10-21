package games.brennan.dungeontrain.worldgen.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of Deeper Darker's Otherside noise settings — the jar DT builds against — to what
 * {@link OthersideSurfaceSkin} and {@code OthersideGeometry} reproduce by hand. A Deeper Darker update that
 * changes the surface rule, the sea level, the height or the cell size fails here instead of silently
 * drifting the Deeper Dark core away from the Otherside.
 */
final class OthersideSurfaceRuleShapeTest {

    private static final String PATH = "data/deeperdarker/worldgen/noise_settings/otherside.json";
    /** Set by the Gradle test task to the Deeper Darker jar DT builds against (see build.gradle). */
    private static final String JAR_PROPERTY = "dungeontrain.test.deeperdarkerJar";

    private static JsonObject settings() throws Exception {
        String jar = System.getProperty(JAR_PROPERTY, "");
        assertTrue(!jar.isEmpty(), JAR_PROPERTY + " is unset — run through Gradle, which points it at the Deeper Darker jar");
        try (var zip = new java.util.zip.ZipFile(jar)) {
            var entry = zip.getEntry(PATH);
            assertTrue(entry != null, "missing " + PATH + " in " + jar);
            try (var in = zip.getInputStream(entry)) {
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
    }

    @Test
    @DisplayName("height, cells, sea and fluids are what the geometry assumes")
    void geometryInputs() throws Exception {
        JsonObject s = settings();
        JsonObject noise = s.getAsJsonObject("noise");
        assertEquals(0, noise.get("min_y").getAsInt());
        assertEquals(128, noise.get("height").getAsInt());
        assertEquals(1, noise.get("size_horizontal").getAsInt(), "cell width 4");
        assertEquals(1, noise.get("size_vertical").getAsInt(), "cell height 4");
        assertEquals(17, s.get("sea_level").getAsInt());
        assertFalse(s.get("aquifers_enabled").getAsBoolean(), "the carve uses a disabled aquifer");
        assertEquals("deeperdarker:sculk_stone", s.getAsJsonObject("default_block").get("Name").getAsString());
        assertEquals("deeperdarker:sculk_grime", s.getAsJsonObject("default_fluid").get("Name").getAsString());
        assertFalse(s.get("legacy_random_source").getAsBoolean());
    }

    @Test
    @DisplayName("the surface rule is the seven-step sequence the skin reproduces")
    void surfaceRuleShape() throws Exception {
        JsonArray seq = settings().getAsJsonObject("surface_rule").getAsJsonArray("sequence");
        assertEquals(7, seq.size());

        gradient(seq.get(0).getAsJsonObject(), "minecraft:bedrock_floor", "above_bottom", 0, "above_bottom", 5, false, "minecraft:bedrock");
        gradient(seq.get(1).getAsJsonObject(), "minecraft:bedrock_roof", "below_top", 5, "below_top", 0, true, "minecraft:bedrock");

        JsonObject echo = seq.get(2).getAsJsonObject();
        assertEquals("deeperdarker:echoing_forest", biomeOf(echo.getAsJsonObject("if_true")));
        JsonObject echoDepth = echo.getAsJsonObject("then_run");
        stoneDepth(echoDepth.getAsJsonObject("if_true"), true);
        assertEquals("deeperdarker:echo_soil", blockOf(echoDepth.getAsJsonObject("then_run")));

        JsonObject floor = seq.get(3).getAsJsonObject();
        stoneDepth(floor.getAsJsonObject("if_true"), false);
        JsonArray byBiome = floor.getAsJsonObject("then_run").getAsJsonArray("sequence");
        assertEquals(3, byBiome.size());
        assertEquals("deeperdarker:blooming_caverns", biomeOf(byBiome.get(0).getAsJsonObject().getAsJsonObject("if_true")));
        assertEquals("deeperdarker:blooming_sculk_stone", blockOf(byBiome.get(0).getAsJsonObject().getAsJsonObject("then_run")));
        assertEquals("deeperdarker:overcast_columns", biomeOf(byBiome.get(1).getAsJsonObject().getAsJsonObject("if_true")));
        assertEquals("deeperdarker:gloomy_sculk", blockOf(byBiome.get(1).getAsJsonObject().getAsJsonObject("then_run")));
        assertEquals("minecraft:sculk", blockOf(byBiome.get(2).getAsJsonObject()));

        gradient(seq.get(4).getAsJsonObject(), "minecraft:deepslate_floor", "above_bottom", 0, "above_bottom", 12, false, "minecraft:deepslate");
        gradient(seq.get(5).getAsJsonObject(), "minecraft:deepslate_roof", "below_top", 12, "below_top", 0, true, "minecraft:deepslate");

        JsonObject overcast = seq.get(6).getAsJsonObject();
        assertEquals("deeperdarker:overcast_columns", biomeOf(overcast.getAsJsonObject("if_true")));
        assertEquals("deeperdarker:gloomslate", blockOf(overcast.getAsJsonObject("then_run")));
    }

    private static void gradient(JsonObject cond, String name, String trueKind, int trueY, String falseKind, int falseY,
                                 boolean inverted, String block) {
        assertEquals("minecraft:condition", cond.get("type").getAsString());
        JsonObject test = cond.getAsJsonObject("if_true");
        if (inverted) {
            assertEquals("minecraft:not", test.get("type").getAsString());
            test = test.getAsJsonObject("invert");
        }
        assertEquals("minecraft:vertical_gradient", test.get("type").getAsString());
        assertEquals(name, test.get("random_name").getAsString());
        assertEquals(trueY, test.getAsJsonObject("true_at_and_below").get(trueKind).getAsInt());
        assertEquals(falseY, test.getAsJsonObject("false_at_and_above").get(falseKind).getAsInt());
        assertEquals(block, blockOf(cond.getAsJsonObject("then_run")));
    }

    private static void stoneDepth(JsonObject test, boolean addSurfaceDepth) {
        assertEquals("minecraft:stone_depth", test.get("type").getAsString());
        assertEquals(addSurfaceDepth, test.get("add_surface_depth").getAsBoolean());
        assertEquals(0, test.get("offset").getAsInt());
        assertEquals(0, test.get("secondary_depth_range").getAsInt());
        assertEquals("floor", test.get("surface_type").getAsString());
    }

    private static String biomeOf(JsonObject test) {
        assertEquals("minecraft:biome", test.get("type").getAsString());
        JsonArray is = test.getAsJsonArray("biome_is");
        assertEquals(1, is.size());
        return is.get(0).getAsString();
    }

    private static String blockOf(JsonObject rule) {
        assertEquals("minecraft:block", rule.get("type").getAsString());
        return rule.getAsJsonObject("result_state").get("Name").getAsString();
    }
}
