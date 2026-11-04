package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which band features ride on End biomes.
 *
 * <p>Since the void fix (#2469) an End-band column is End-labelled at <em>every</em> height, so a chunk
 * deep inside a vanilla End piece holds no overworld biome at all — the overworld modifier's features
 * never iterate there. The islands come from {@code dungeontrain:disintegration}, so it must ride on
 * {@code #minecraft:is_end} too, ahead of {@code track_bed} (the track rests its pillars on the islands).
 * Dropping it generated the whole vanilla piece as void (v0.1649.0 / v0.1650.0).</p>
 */
final class BiomeModifierFeaturesTest {

    private static final Path MODIFIERS = RepoPaths.root()
            .resolve("src/main/resources/data/dungeontrain/neoforge/biome_modifier");

    @Test
    @DisplayName("End biomes carry the island stamp before the track bed")
    void endModifierStampsIslandsBeforeTrack() throws IOException {
        List<String> end = features("track_bed_end.json");
        int islands = end.indexOf("dungeontrain:disintegration");
        int track = end.indexOf("dungeontrain:track_bed");
        assertTrue(islands >= 0, "disintegration missing from track_bed_end.json: " + end);
        assertTrue(track >= 0, "track_bed missing from track_bed_end.json: " + end);
        assertTrue(islands < track, "islands must be stamped before the track bed: " + end);
    }

    @Test
    @DisplayName("overworld modifier keeps the same islands-then-track order")
    void overworldModifierOrderMatches() throws IOException {
        List<String> overworld = features("track_bed_overworld.json");
        assertTrue(overworld.indexOf("dungeontrain:disintegration") < overworld.indexOf("dungeontrain:track_bed"),
                "overworld order: " + overworld);
        assertEquals("#minecraft:is_overworld", read("track_bed_overworld.json").get("biomes").getAsString());
        assertEquals("#minecraft:is_end", read("track_bed_end.json").get("biomes").getAsString());
    }

    private static List<String> features(String file) throws IOException {
        JsonElement f = read(file).get("features");
        List<String> out = new ArrayList<>();
        if (f.isJsonArray()) {
            for (JsonElement e : (JsonArray) f) out.add(e.getAsString());
        } else {
            out.add(f.getAsString());
        }
        return out;
    }

    private static JsonObject read(String file) throws IOException {
        try (Reader r = Files.newBufferedReader(MODIFIERS.resolve(file))) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }
}
