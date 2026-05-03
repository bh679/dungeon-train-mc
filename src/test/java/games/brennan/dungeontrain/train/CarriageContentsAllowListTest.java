package games.brennan.dungeontrain.train;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for {@link CarriageContentsAllowList}. */
final class CarriageContentsAllowListTest {

    @Test
    @DisplayName("EMPTY allows everything")
    void empty_allowsAll() {
        assertTrue(CarriageContentsAllowList.EMPTY.isAllowed("default"));
        assertTrue(CarriageContentsAllowList.EMPTY.isAllowed("anything"));
        assertTrue(CarriageContentsAllowList.EMPTY.excluded().isEmpty());
    }

    @Test
    @DisplayName("withExcluded adds an id; isAllowed returns false for it")
    void withExcluded_excludesId() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY.withExcluded("default");
        assertFalse(a.isAllowed("default"));
        assertTrue(a.isAllowed("other"));
    }

    @Test
    @DisplayName("withExcluded is idempotent — same instance returned when already excluded")
    void withExcluded_idempotent() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY.withExcluded("default");
        CarriageContentsAllowList b = a.withExcluded("default");
        assertSame(a, b);
    }

    @Test
    @DisplayName("withAllowed removes an id from the excluded set")
    void withAllowed_removesId() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY.withExcluded("default");
        CarriageContentsAllowList b = a.withAllowed("default");
        assertTrue(b.excluded().isEmpty());
        assertTrue(b.isAllowed("default"));
    }

    @Test
    @DisplayName("withAllowed is idempotent — same instance returned when already allowed")
    void withAllowed_idempotent() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY.withAllowed("default");
        assertSame(CarriageContentsAllowList.EMPTY, a);
    }

    @Test
    @DisplayName("toggle flips membership")
    void toggle_flipsMembership() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY.toggle("default");
        assertFalse(a.isAllowed("default"));
        CarriageContentsAllowList b = a.toggle("default");
        assertTrue(b.isAllowed("default"));
    }

    @Test
    @DisplayName("ids are lowercased on construction")
    void construction_lowercases() {
        Set<String> raw = new HashSet<>();
        raw.add("LavaPool");
        raw.add("DEFAULT");
        CarriageContentsAllowList a = new CarriageContentsAllowList(raw);
        assertTrue(a.excluded().contains("lavapool"));
        assertTrue(a.excluded().contains("default"));
        assertEquals(2, a.excluded().size());
    }

    @Test
    @DisplayName("isAllowed lowercases the query — case-insensitive")
    void isAllowed_caseInsensitive() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY.withExcluded("Default");
        assertFalse(a.isAllowed("DEFAULT"));
        assertFalse(a.isAllowed("default"));
        assertFalse(a.isAllowed("Default"));
    }

    @Test
    @DisplayName("excluded set is unmodifiable")
    void excluded_isUnmodifiable() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY.withExcluded("default");
        assertThrows(UnsupportedOperationException.class, () -> a.excluded().add("other"));
    }

    @Test
    @DisplayName("toJson / fromJson round trip preserves excluded set")
    void jsonRoundTrip() {
        CarriageContentsAllowList a = CarriageContentsAllowList.EMPTY
            .withExcluded("default")
            .withExcluded("lava_pool");
        JsonObject json = a.toJson();
        assertEquals(CarriageContentsAllowList.SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
        CarriageContentsAllowList b = CarriageContentsAllowList.fromJson(json);
        assertEquals(a.excluded(), b.excluded());
    }

    @Test
    @DisplayName("fromJson tolerates missing 'excluded' key")
    void fromJson_missingKeyEmpty() {
        JsonObject empty = new JsonObject();
        empty.addProperty("schemaVersion", 1);
        CarriageContentsAllowList a = CarriageContentsAllowList.fromJson(empty);
        assertTrue(a.excluded().isEmpty());
    }

    @Test
    @DisplayName("fromJson tolerates non-array 'excluded' value")
    void fromJson_nonArrayValueEmpty() {
        JsonObject root = new JsonObject();
        root.addProperty("excluded", "not an array");
        CarriageContentsAllowList a = CarriageContentsAllowList.fromJson(root);
        assertTrue(a.excluded().isEmpty());
    }

    @Test
    @DisplayName("fromJson skips non-string entries inside the array")
    void fromJson_skipsNonStrings() {
        JsonObject root = JsonParser.parseString(
            "{\"schemaVersion\":1,\"excluded\":[\"valid\", 42, null, true, \"\", \"another\"]}"
        ).getAsJsonObject();
        CarriageContentsAllowList a = CarriageContentsAllowList.fromJson(root);
        assertEquals(2, a.excluded().size());
        assertTrue(a.excluded().contains("valid"));
        assertTrue(a.excluded().contains("another"));
    }

    @Test
    @DisplayName("toJson emits stable, sorted excluded array")
    void toJson_sortedOutput() {
        CarriageContentsAllowList a = new CarriageContentsAllowList(Set.of("zeta", "alpha", "mu"));
        JsonObject json = a.toJson();
        assertEquals("alpha", json.getAsJsonArray("excluded").get(0).getAsString());
        assertEquals("mu",    json.getAsJsonArray("excluded").get(1).getAsString());
        assertEquals("zeta",  json.getAsJsonArray("excluded").get(2).getAsString());
    }
}
