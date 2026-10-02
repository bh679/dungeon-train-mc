package games.brennan.dungeontrain.client.render;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stage placeholder's item icon is drawn from its block model with no block state, and a
 * {@code multipart} model has nothing to draw without one — the slot comes out empty. Every such
 * placeholder therefore needs a dedicated icon model, and that model has to exist.
 */
final class StagePlaceholderIconsTest {

    private static final Path ASSETS = RepoPaths.resources().resolve("assets/dungeontrain");

    @Test
    @DisplayName("every multipart stage placeholder has an icon model")
    void multipartPlaceholdersHaveIcons() throws IOException {
        List<String> names = placeholderNames();
        assertFalse(names.isEmpty(), "no stage_* blockstates found under " + ASSETS);
        for (String name : names) {
            String json = Files.readString(ASSETS.resolve("blockstates/" + name + ".json"));
            if (JsonParser.parseString(json).getAsJsonObject().has("multipart")) {
                assertNotNull(StagePlaceholderIcons.iconModel(name),
                    name + " is multipart, so its block model draws no item icon — give it an icon model");
            }
        }
    }

    @Test
    @DisplayName("every icon model file exists")
    void iconModelsExist() throws IOException {
        for (String name : placeholderNames()) {
            String model = StagePlaceholderIcons.iconModel(name);
            if (model == null) continue;
            assertTrue(Files.isRegularFile(ASSETS.resolve("models/" + model + ".json")),
                name + " → missing models/" + model + ".json");
        }
    }

    /** Every {@code stage_*} blockstate — one per placeholder block. */
    private static List<String> placeholderNames() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(ASSETS.resolve("blockstates"))) {
            files.map(p -> p.getFileName().toString())
                .filter(f -> f.startsWith("stage_") && f.endsWith(".json"))
                .forEach(f -> out.add(f.substring(0, f.length() - ".json".length())));
        }
        return out;
    }
}
