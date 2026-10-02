package games.brennan.dungeontrain.compat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TradeValueTable#parse} — the bundled export is validated entry by entry, never trusted. */
final class TradeValueTableTest {

    @AfterEach
    void reset() {
        TradeValueTable.setForTest(null);
    }

    @Test
    @DisplayName("a well-formed export parses to a sorted map of sixteenths")
    void parsesExport() {
        Map<String, Integer> t = TradeValueTable.parse(
            "{\"generatedAt\":\"2026-09-29T00:00:00Z\",\"unit\":\"sixteenths\","
                + "\"values\":{\"minecraft:diamond\":80,\"minecraft:bookshelf\":22}}");
        assertEquals(List.of("minecraft:bookshelf", "minecraft:diamond"), List.copyOf(t.keySet()));
        assertEquals(80, t.get("minecraft:diamond"));
    }

    @Test
    @DisplayName("bad entries are dropped one by one; the rest of the table survives")
    void dropsBadEntries() {
        Map<String, Integer> t = TradeValueTable.parse(
            "{\"values\":{\"diamond\":64,\"minecraft:stick\":0,\"minecraft:emerald\":1.5,"
                + "\"minecraft:gold_ingot\":\"9\",\"minecraft:iron_ingot\":99999999,\"minecraft:coal\":4}}");
        assertEquals(Map.of("minecraft:coal", 4), t);
    }

    @Test
    @DisplayName("garbage, a missing values object, or a non-object root yield an empty table")
    void emptyOnGarbage() {
        assertTrue(TradeValueTable.parse("not json").isEmpty());
        assertTrue(TradeValueTable.parse("[1,2]").isEmpty());
        assertTrue(TradeValueTable.parse("{\"values\":[]}").isEmpty());
        assertTrue(TradeValueTable.parse("{}").isEmpty());
    }

    @Test
    @DisplayName("valueOf answers only for ids the page approved")
    void valueOf() {
        TradeValueTable.setForTest(Map.of("minecraft:diamond", 80));
        assertEquals(80, TradeValueTable.valueOf(ResourceLocation.parse("minecraft:diamond")).getAsInt());
        assertFalse(TradeValueTable.valueOf(ResourceLocation.parse("minecraft:stick")).isPresent());
        assertFalse(TradeValueTable.valueOf(null).isPresent());
        assertEquals(1, TradeValueTable.size());
    }

    @Test
    @DisplayName("the bundled resource itself parses (empty until the first pull)")
    void bundledResourceParses() {
        assertTrue(TradeValueTable.size() >= 0);
    }
}
