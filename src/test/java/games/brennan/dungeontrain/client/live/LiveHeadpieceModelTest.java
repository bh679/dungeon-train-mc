package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the live headpiece's model files to {@link AntennaTarget}: the overrides' thresholds fall
 * between neighbouring property values, every variant wraps the 3D base model in a
 * {@code neoforge:separate_transforms} loader whose gui/fixed/ground perspectives are the flat
 * sprite, every texture the models name exists, and the geometry stays inside what vanilla's
 * item-model loader accepts.
 */
class LiveHeadpieceModelTest {

    private static final String PREDICATE = "dungeontrain:antenna_target";
    private static final String LOADER = "neoforge:separate_transforms";
    private static final String BASE = "dungeontrain:item/live_headpiece_base";
    private static final Map<AntennaTarget, String> VARIANTS = Map.of(
        AntennaTarget.HOSTILE, "dungeontrain:item/live_headpiece_red",
        AntennaTarget.FRIENDLY, "dungeontrain:item/live_headpiece_green",
        AntennaTarget.BLOCK, "dungeontrain:item/live_headpiece_blue");
    private static final Set<Double> ALLOWED_ANGLES = Set.of(-45.0, -22.5, 0.0, 22.5, 45.0);

    private static Path models() {
        return RepoPaths.resources().resolve("assets/dungeontrain/models/item");
    }

    private static JsonObject model(String name) throws IOException {
        try (Reader r = Files.newBufferedReader(models().resolve(name + ".json"))) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /** Every texture reference in a model: its own {@code textures}, plus those of a separate_transforms {@code base} and {@code perspectives}. */
    private static List<String> textureRefs(JsonObject model) {
        List<String> refs = new ArrayList<>();
        if (model.has("textures")) {
            for (Map.Entry<String, JsonElement> t : model.getAsJsonObject("textures").entrySet()) refs.add(t.getValue().getAsString());
        }
        if (model.has("base")) refs.addAll(textureRefs(model.getAsJsonObject("base")));
        if (model.has("perspectives")) {
            for (Map.Entry<String, JsonElement> p : model.getAsJsonObject("perspectives").entrySet()) refs.addAll(textureRefs(p.getValue().getAsJsonObject()));
        }
        return refs;
    }

    @Test
    void overridesSelectEachColourBetweenNeighbouringPropertyValues() throws IOException {
        JsonArray overrides = model("live_headpiece").getAsJsonArray("overrides");
        assertEquals(VARIANTS.size(), overrides.size(), "one override per lit colour");
        AntennaTarget[] targets = AntennaTarget.values();
        for (int i = 1; i < targets.length; i++) {
            AntennaTarget target = targets[i];
            float below = targets[i - 1].propertyValue();
            float at = target.propertyValue();
            String wanted = VARIANTS.get(target);
            boolean found = false;
            for (JsonElement e : overrides) {
                JsonObject o = e.getAsJsonObject();
                double threshold = o.getAsJsonObject("predicate").get(PREDICATE).getAsDouble();
                if (threshold > below && threshold <= at) {
                    assertEquals(wanted, o.get("model").getAsString(), "override at " + threshold);
                    found = true;
                }
            }
            assertTrue(found, "no override lands between " + below + " and " + at + " for " + target);
        }
    }

    @Test
    void variantsOnlyRetintTheTip() throws IOException {
        for (String variant : VARIANTS.values()) {
            JsonObject m = model(variant.substring(variant.lastIndexOf('/') + 1));
            assertEquals(LOADER, m.get("loader").getAsString(), variant);
            JsonObject base = m.getAsJsonObject("base");
            assertEquals(BASE, base.get("parent").getAsString(), variant);
            JsonObject textures = base.getAsJsonObject("textures");
            assertEquals(Set.of("tip"), textures.keySet(), variant + " overrides exactly the tip texture");
        }
        JsonObject top = model("live_headpiece");
        assertEquals(LOADER, top.get("loader").getAsString());
        assertEquals(BASE, top.getAsJsonObject("base").get("parent").getAsString());
    }

    @Test
    void everyPerspectiveShowsTheFlatSprite() throws IOException {
        for (String name : List.of("live_headpiece", "live_headpiece_red", "live_headpiece_green", "live_headpiece_blue")) {
            JsonObject m = model(name);
            assertEquals("front", m.get("gui_light").getAsString(), name + " must be flat-lit like item/generated, or the sprite renders half-dark");
            JsonObject perspectives = m.getAsJsonObject("perspectives");
            assertEquals(Set.of("gui", "fixed", "ground"), perspectives.keySet(), name);
            for (Map.Entry<String, JsonElement> p : perspectives.entrySet()) {
                JsonObject flat = p.getValue().getAsJsonObject();
                assertEquals("minecraft:item/generated", flat.get("parent").getAsString(), name + "." + p.getKey());
                assertTrue(flat.getAsJsonObject("textures").get("layer0").getAsString().startsWith("dungeontrain:item/live_headpiece_icon"),
                    name + "." + p.getKey() + " sprite");
            }
        }
    }

    @Test
    void everyDungeonTrainTextureExists() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : List.of("live_headpiece_base", "live_headpiece_red", "live_headpiece_green", "live_headpiece_blue")) {
            for (String ref : textureRefs(model(name))) {
                if (!ref.startsWith("dungeontrain:")) continue;
                Path png = RepoPaths.resources().resolve("assets/dungeontrain/textures/" + ref.substring("dungeontrain:".length()) + ".png");
                if (!Files.isRegularFile(png)) missing.add(name + " -> " + ref);
            }
        }
        assertTrue(missing.isEmpty(), "textures missing: " + missing);
    }

    @Test
    void geometryStaysWithinVanillaLimits() throws IOException {
        JsonObject base = model("live_headpiece_base");
        Set<String> declared = base.getAsJsonObject("textures").keySet();
        for (JsonElement e : base.getAsJsonArray("elements")) {
            JsonObject el = e.getAsJsonObject();
            String name = el.get("name").getAsString();
            for (String key : List.of("from", "to")) {
                for (JsonElement v : el.getAsJsonArray(key)) {
                    double c = v.getAsDouble();
                    assertTrue(c >= -16 && c <= 32, name + " " + key + " out of [-16,32]: " + c);
                }
            }
            if (el.has("rotation")) {
                double angle = el.getAsJsonObject("rotation").get("angle").getAsDouble();
                assertTrue(ALLOWED_ANGLES.contains(angle), name + " rotation " + angle);
            }
            for (Map.Entry<String, JsonElement> face : el.getAsJsonObject("faces").entrySet()) {
                JsonObject f = face.getValue().getAsJsonObject();
                String tex = f.get("texture").getAsString();
                if (!tex.startsWith("#") || !declared.contains(tex.substring(1))) {
                    fail(name + "." + face.getKey() + " uses undeclared texture " + tex);
                }
                for (JsonElement v : f.getAsJsonArray("uv")) {
                    double u = v.getAsDouble();
                    assertTrue(u >= 0 && u <= 16, name + "." + face.getKey() + " uv out of [0,16]: " + u);
                }
            }
        }
    }
}
