package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ContentsShellOverrides}' manifest: what it reads and what it writes. */
final class ContentsShellOverridesTest {

    @Test
    @DisplayName("a manifest round-trips")
    void roundTrip() {
        Map<String, String> values = Map.of("creeking", "cargo", "library", "standard");
        String json = ContentsShellOverrides.toJson(values).toString();
        assertEquals(values, ContentsShellOverrides.parse(new StringReader(json)));
    }

    @Test
    @DisplayName("a missing body is empty and a non-string row is skipped, not fatal")
    void tolerant() {
        assertTrue(ContentsShellOverrides.parse(new StringReader("{\"schemaVersion\":1}")).isEmpty());
        assertEquals(Map.of("creeking", "cargo"), ContentsShellOverrides.parse(new StringReader(
            "{\"schemaVersion\":1,\"shells\":{\"creeking\":\"cargo\",\"fire\":3}}")));
    }
}
