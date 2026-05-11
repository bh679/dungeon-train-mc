package games.brennan.dungeontrain.train;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for {@link CarriageContentsGroup}. */
final class CarriageContentsGroupTest {

    private static CarriageContentsGroup of(String id, int weight) {
        List<CarriageContentsGroup.Member> ms = new ArrayList<>();
        ms.add(new CarriageContentsGroup.Member(id, weight));
        return new CarriageContentsGroup(ms);
    }

    @Test
    @DisplayName("EMPTY has no members and pick() returns null")
    void empty_pickNull() {
        assertTrue(CarriageContentsGroup.EMPTY.isEmpty());
        assertNull(CarriageContentsGroup.EMPTY.pick(new Random(0)));
    }

    @Test
    @DisplayName("Member constructor lowercases id and clamps weight to [0, 100]")
    void member_normalises() {
        CarriageContentsGroup.Member m = new CarriageContentsGroup.Member("Container_Wooden", 250);
        assertEquals("container_wooden", m.id());
        assertEquals(CarriageContentsGroup.MAX_WEIGHT, m.weight());

        CarriageContentsGroup.Member n = new CarriageContentsGroup.Member("x", -5);
        assertEquals(CarriageContentsGroup.MIN_WEIGHT, n.weight());
    }

    @Test
    @DisplayName("Member constructor rejects invalid ids")
    void member_rejectsInvalidIds() {
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageContentsGroup.Member("bad name!", 1));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageContentsGroup.Member("", 1));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageContentsGroup.Member(null, 1));
    }

    @Test
    @DisplayName("Constructor de-dupes members by id (last entry wins)")
    void constructor_dedupes() {
        List<CarriageContentsGroup.Member> raw = new ArrayList<>();
        raw.add(new CarriageContentsGroup.Member("a", 1));
        raw.add(new CarriageContentsGroup.Member("b", 2));
        raw.add(new CarriageContentsGroup.Member("a", 9));
        CarriageContentsGroup g = new CarriageContentsGroup(raw);
        assertEquals(2, g.members().size());
        // Last 'a' entry wins; position preserves the FIRST occurrence's slot.
        assertEquals("a", g.members().get(0).id());
        assertEquals(9, g.members().get(0).weight());
        assertEquals("b", g.members().get(1).id());
    }

    @Test
    @DisplayName("withMember replaces an existing id, appends a new one")
    void withMember_replaceOrAppend() {
        CarriageContentsGroup g = of("a", 1).withMember(new CarriageContentsGroup.Member("b", 2));
        assertEquals(2, g.members().size());
        CarriageContentsGroup g2 = g.withMember(new CarriageContentsGroup.Member("a", 5));
        assertEquals(2, g2.members().size());
        assertEquals(5, g2.members().get(0).weight());
    }

    @Test
    @DisplayName("withoutMember is idempotent and case-insensitive")
    void withoutMember_idempotent() {
        CarriageContentsGroup g = of("a", 1).withMember(new CarriageContentsGroup.Member("b", 2));
        assertEquals(1, g.withoutMember("A").members().size());
        assertEquals(g, g.withoutMember("nonexistent"));
    }

    @Test
    @DisplayName("pick returns null when every weight is zero")
    void pick_allZeroReturnsNull() {
        CarriageContentsGroup g = of("a", 0).withMember(new CarriageContentsGroup.Member("b", 0));
        assertNull(g.pick(new Random(42)));
    }

    @Test
    @DisplayName("pick is weighted — high-weight member dominates over many trials")
    void pick_weightedDistribution() {
        CarriageContentsGroup g = of("heavy", 90).withMember(new CarriageContentsGroup.Member("light", 10));
        int heavy = 0;
        for (int i = 0; i < 1000; i++) {
            CarriageContentsGroup.Member m = g.pick(new Random(i));
            if ("heavy".equals(m.id())) heavy++;
        }
        // 90/100 expectation ≈ 900. Be lenient — within ±5% for 1000 trials.
        assertTrue(heavy > 800 && heavy < 950, "heavy was picked " + heavy + " / 1000 times");
    }

    @Test
    @DisplayName("pick spans every member across enough trials")
    void pick_spansSet() {
        CarriageContentsGroup g = of("a", 1)
            .withMember(new CarriageContentsGroup.Member("b", 1))
            .withMember(new CarriageContentsGroup.Member("c", 1));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(g.pick(new Random(i)).id());
        }
        assertEquals(3, seen.size());
    }

    @Test
    @DisplayName("toJson / fromJson round trip preserves member ids, order, weights, schemaVersion")
    void jsonRoundTrip() {
        CarriageContentsGroup g = of("a", 5)
            .withMember(new CarriageContentsGroup.Member("b", 2))
            .withMember(new CarriageContentsGroup.Member("c", 0));
        JsonObject json = g.toJson();
        assertEquals(CarriageContentsGroup.SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
        CarriageContentsGroup back = CarriageContentsGroup.fromJson(json);
        assertEquals(g.members().size(), back.members().size());
        for (int i = 0; i < g.members().size(); i++) {
            assertEquals(g.members().get(i).id(), back.members().get(i).id());
            assertEquals(g.members().get(i).weight(), back.members().get(i).weight());
        }
    }

    @Test
    @DisplayName("fromJson tolerates missing 'variants' key")
    void fromJson_missingVariantsKey() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        CarriageContentsGroup g = CarriageContentsGroup.fromJson(root);
        assertTrue(g.isEmpty());
    }

    @Test
    @DisplayName("fromJson tolerates non-array 'variants' value")
    void fromJson_nonArrayVariants() {
        JsonObject root = new JsonObject();
        root.addProperty("variants", "not an array");
        CarriageContentsGroup g = CarriageContentsGroup.fromJson(root);
        assertTrue(g.isEmpty());
    }

    @Test
    @DisplayName("fromJson skips invalid entries (non-objects, bad ids, blank ids)")
    void fromJson_skipsInvalidEntries() {
        JsonObject root = JsonParser.parseString(
            "{\"schemaVersion\":1,\"variants\":["
                + "{\"id\":\"valid_a\", \"weight\":3}, "
                + "\"not an object\", "
                + "{\"id\":\"bad id!\", \"weight\":1}, "
                + "{\"id\":\"\", \"weight\":1}, "
                + "{\"weight\":5}, "
                + "{\"id\":\"valid_b\"} "
                + "]}"
        ).getAsJsonObject();
        CarriageContentsGroup g = CarriageContentsGroup.fromJson(root);
        assertEquals(2, g.members().size());
        assertEquals("valid_a", g.members().get(0).id());
        assertEquals(3, g.members().get(0).weight());
        assertEquals("valid_b", g.members().get(1).id());
        // Missing weight → DEFAULT_WEIGHT
        assertEquals(CarriageContentsGroup.DEFAULT_WEIGHT, g.members().get(1).weight());
    }

    @Test
    @DisplayName("fromJson clamps out-of-range weights")
    void fromJson_clampsWeights() {
        JsonObject root = JsonParser.parseString(
            "{\"schemaVersion\":1,\"variants\":["
                + "{\"id\":\"a\", \"weight\":9999}, "
                + "{\"id\":\"b\", \"weight\":-3}"
                + "]}"
        ).getAsJsonObject();
        CarriageContentsGroup g = CarriageContentsGroup.fromJson(root);
        assertEquals(CarriageContentsGroup.MAX_WEIGHT, g.members().get(0).weight());
        assertEquals(CarriageContentsGroup.MIN_WEIGHT, g.members().get(1).weight());
    }

    @Test
    @DisplayName("toJson emits a 'variants' array of {id, weight} objects")
    void toJson_shape() {
        CarriageContentsGroup g = of("x", 7);
        JsonObject json = g.toJson();
        assertNotNull(json.getAsJsonArray("variants"));
        assertEquals(1, json.getAsJsonArray("variants").size());
        JsonObject entry = json.getAsJsonArray("variants").get(0).getAsJsonObject();
        assertEquals("x", entry.get("id").getAsString());
        assertEquals(7, entry.get("weight").getAsInt());
    }

    @Test
    @DisplayName("Empty member list constructs but isEmpty() is true")
    void emptyListConstruction() {
        CarriageContentsGroup g = new CarriageContentsGroup(new ArrayList<>());
        assertTrue(g.isEmpty());
        assertFalse(g.equals(null));
    }
}
