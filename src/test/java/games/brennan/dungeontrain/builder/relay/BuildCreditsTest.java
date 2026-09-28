package games.brennan.dungeontrain.builder.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuildCredits.Credit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The byline store's shape on disk and its tolerance for a file that is not the shape it wrote.
 *
 * <p>The file is read at world join to caption a build; a garbled one must read as "nobody
 * recorded" rather than throw, because the alternative is a builder world that will not open over
 * a display line.</p>
 */
final class BuildCreditsTest {

    private static Map<String, Credit> map(String key, Credit credit) {
        Map<String, Credit> out = new LinkedHashMap<>();
        out.put(key, credit);
        return out;
    }

    private static String key(String id) {
        return BuildCredits.keyOf(BuilderPhotoPaths.Kind.CARRIAGE, "", id);
    }

    @Test
    @DisplayName("A credit survives the round trip through the file's shape")
    void roundTrip(@TempDir Path dir) throws Exception {
        Credit credit = new Credit("aa-bb", "Edda", 1_700_000_000_000L);
        Path file = dir.resolve(BuildCredits.FILENAME);
        Files.writeString(file, BuildCredits.toJson(map(key("crate_car"), credit)).toString(),
                StandardCharsets.UTF_8);

        Map<String, Credit> read = BuildCredits.readFrom(file);
        assertEquals(credit, read.get(key("crate_car")));
    }

    @Test
    @DisplayName("The key names the store as well as the name — a part and a carriage never collide")
    void keyIsPerStore() {
        assertTrue(BuildCredits.keyOf(BuilderPhotoPaths.Kind.PART, "wall", "standard")
                .equals(BuilderRelayBuilds.keyOf("part", "wall", "standard")));
        assertTrue(!BuildCredits.keyOf(BuilderPhotoPaths.Kind.PART, "wall", "standard")
                .equals(BuildCredits.keyOf(BuilderPhotoPaths.Kind.PART, "door", "standard")));
    }

    @Test
    @DisplayName("An entry naming nobody is not a credit")
    void anonymousEntryIsDropped() {
        JsonObject empty = new JsonObject();
        empty.addProperty("uuid", "");
        empty.addProperty("name", "");
        assertNull(BuildCredits.decode(empty));
        assertNull(BuildCredits.decode(JsonParser.parseString("\"Edda\"")));
        assertNull(BuildCredits.decode(null));
    }

    @Test
    @DisplayName("A file that is not what we wrote reads as no credits, never as a failure")
    void garbledFileIsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(BuildCredits.FILENAME);
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        assertTrue(BuildCredits.readFrom(file).isEmpty());

        Files.writeString(file, "[]", StandardCharsets.UTF_8);
        assertTrue(BuildCredits.readFrom(file).isEmpty());

        assertTrue(BuildCredits.readFrom(dir.resolve("absent.json")).isEmpty());
        assertTrue(BuildCredits.readFrom(null).isEmpty());
    }

    @Test
    @DisplayName("A credit shows the name when it has one and falls back to the uuid when it doesn't")
    void displayPrefersTheName() {
        assertEquals("Edda", new Credit("aa-bb", "Edda", 0L).display());
        assertEquals("aa-bb", new Credit("aa-bb", "", 0L).display());
        assertTrue(new Credit(null, null, 0L).display().isEmpty());
    }
}
