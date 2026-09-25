package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.client.worldgen.FloorYState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the TerraBlender {@code overworld_regions} dimension-type tag against drifting away from DT's
 * world-floor presets.
 *
 * <p>TerraBlender only sets up its regions — and so only adds Biomes O' Plenty's biomes to a
 * generator's {@code possibleBiomes()} — for dimension types in that tag. DT's
 * {@code OverworldStretchBiomes} places BoP biomes on its stretch whatever the tag says, and vanilla's
 * {@code applyBiomeDecoration} drops the features of any biome outside {@code possibleBiomes()}. So a
 * floor preset missing from the tag gets a BoP stretch with no trees, plants or ores. Four floors
 * (y0, y-32, y80, y96) shipped that way before this test existed.</p>
 */
final class TerraBlenderRegionTagTest {

    private static final String TAG = "data/terrablender/tags/dimension_type/overworld_regions.json";
    private static final String PRESETS = "data/dungeontrain/worldgen/world_preset";

    /** The Floor-Y value that is served by the plain {@code dungeon_train} preset. */
    private static final String DEFAULT_PRESET = "dungeon_train";
    private static final String Y_PRESET_PREFIX = "dungeon_train_y";
    private static final String DT_NAMESPACE = "dungeontrain:";

    @Test
    @DisplayName("every Floor-Y choice's dimension type initialises TerraBlender regions")
    void everyFloorPresetIsInTheRegionTag() throws IOException {
        Set<String> tagged = taggedDimensionTypes();
        List<String> missing = new ArrayList<>();
        for (int y : FloorYState.VALUES) {
            String preset = y == FloorYState.DEFAULT ? DEFAULT_PRESET : Y_PRESET_PREFIX + y;
            Path file = RepoPaths.resources().resolve(PRESETS).resolve(preset + ".json");
            assertTrue(Files.isRegularFile(file), "Floor-Y " + y + " has no world preset at " + file);
            String type = overworldType(read(file));
            if (!tagged.contains(type)) missing.add("y=" + y + " -> " + type);
        }
        assertTrue(missing.isEmpty(), "Floor presets missing from " + TAG + ": " + missing);
    }

    @Test
    @DisplayName("every noise-overworld preset on a DT dimension type initialises TerraBlender regions")
    void everyNoiseOverworldPresetIsInTheRegionTag() throws IOException {
        Set<String> tagged = taggedDimensionTypes();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(RepoPaths.resources().resolve(PRESETS))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonObject overworld = overworld(read(file));
                if (overworld == null || !isNoiseGenerator(overworld)) continue;
                String type = overworld.get("type").getAsString();
                // Vanilla's overworld is in TerraBlender's own tag; only DT's types need listing here.
                if (!type.startsWith(DT_NAMESPACE)) continue;
                checked++;
                if (!tagged.contains(type)) missing.add(file.getFileName() + " -> " + type);
            }
        }
        assertTrue(checked > 0, "no noise-overworld presets found under " + PRESETS);
        assertTrue(missing.isEmpty(), "Presets missing from " + TAG + ": " + missing);
    }

    private static Set<String> taggedDimensionTypes() throws IOException {
        JsonObject tag = read(RepoPaths.resources().resolve(TAG));
        assertFalse(tag.has("replace") && tag.get("replace").getAsBoolean(),
            "the tag must not replace TerraBlender's own entries");
        Set<String> out = new HashSet<>();
        for (JsonElement e : tag.getAsJsonArray("values")) out.add(e.getAsString());
        return out;
    }

    private static String overworldType(JsonObject preset) {
        JsonObject overworld = overworld(preset);
        if (overworld == null) throw new IllegalStateException("preset has no minecraft:overworld");
        return overworld.get("type").getAsString();
    }

    private static JsonObject overworld(JsonObject preset) {
        JsonObject dims = preset.getAsJsonObject("dimensions");
        return dims == null ? null : dims.getAsJsonObject("minecraft:overworld");
    }

    private static boolean isNoiseGenerator(JsonObject dimension) {
        JsonObject generator = dimension.getAsJsonObject("generator");
        return generator != null && "minecraft:noise".equals(generator.get("type").getAsString());
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }
}
