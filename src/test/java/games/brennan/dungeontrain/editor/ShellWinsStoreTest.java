package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ShellWinsStore}'s manifest: what it reads, what it writes, and what ships switched on. */
final class ShellWinsStoreTest {

    @Test
    @DisplayName("a manifest round-trips, an explicit Off included")
    void roundTrip() {
        Map<String, Boolean> values = Map.of("cargo", true, "stonecargo", false);
        String json = ShellWinsStore.toJson(values).toString();
        assertEquals(values, ShellWinsStore.parse(new StringReader(json)));
    }

    @Test
    @DisplayName("a missing body is empty and a non-boolean row is skipped, not fatal")
    void tolerant() {
        assertTrue(ShellWinsStore.parse(new StringReader("{\"schemaVersion\":1}")).isEmpty());
        assertEquals(Map.of("cargo", true), ShellWinsStore.parse(new StringReader(
            "{\"schemaVersion\":1,\"shellWins\":{\"cargo\":true,\"fire\":\"yes\"}}")));
    }

    @Test
    @DisplayName("the shipped manifest switches it on for Cargo and Cargo Stone only")
    void shipped() throws Exception {
        try (InputStream in = ShellWinsStore.class.getResourceAsStream(
                "/data/dungeontrain/templates/shell-wins.json")) {
            assertNotNull(in, "templates/shell-wins.json ships with the mod");
            assertEquals(Map.of("cargo", true, "stonecargo", true),
                ShellWinsStore.parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }
}
