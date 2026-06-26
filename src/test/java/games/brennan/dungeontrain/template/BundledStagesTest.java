package games.brennan.dungeontrain.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the <b>shipped</b> {@code /data/dungeontrain/stages.json} (the bundled Stage defaults
 * {@code StageStore} loads on every install): it must exist on the classpath, be a JSON object, and
 * parse cleanly through {@link Stage#fromJson} — so a malformed bundled file never breaks the gate
 * resolution for every carriage/contents/track/part linked to one of these Stages.
 */
final class BundledStagesTest {

    private static final String RESOURCE = "/data/dungeontrain/stages.json";

    @Test
    @DisplayName("bundled stages.json exists, is a JSON object, and every entry parses to a Stage")
    void bundledStagesParse() {
        InputStream in = BundledStagesTest.class.getResourceAsStream(RESOURCE);
        assertNotNull(in, "bundled " + RESOURCE + " must ship on the classpath");

        JsonElement root;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r);
        } catch (Exception e) {
            throw new AssertionError("bundled stages.json failed to read: " + e, e);
        }
        assertTrue(root != null && root.isJsonObject(), "bundled stages.json must be a JSON object");

        Map<String, Stage> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : ((JsonObject) root).entrySet()) {
            Stage s = Stage.fromJson(e.getKey(), e.getValue());
            assertNotNull(s, "bundled stage '" + e.getKey() + "' must parse");
            parsed.put(s.id(), s);
        }
        assertTrue(parsed.size() >= 1, "at least one bundled Stage should ship");
        // The shipped presets authored in-game — pin so an accidental wipe of stages.json is caught.
        for (String expected : new String[] {"desert", "nether", "stone"}) {
            assertTrue(parsed.containsKey(expected), "bundled stages.json should contain '" + expected + "'");
        }
        // id is normalised (lower-case) + non-null gate (no NPEs on the worldgen gate path).
        for (Stage s : parsed.values()) {
            assertTrue(s.id().equals(s.id().toLowerCase(java.util.Locale.ROOT)), "stage id must be lower-case: " + s.id());
            assertNotNull(s.gate(), "stage '" + s.id() + "' must have a non-null gate");
        }
    }
}
