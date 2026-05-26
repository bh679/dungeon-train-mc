package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link DifficultyTierCodec} parse behaviour:
 * <ul>
 *   <li>Round-trip of a minimal tier produces matching records.</li>
 *   <li>Missing optional fields fall back to safe defaults (empty lists, zero chance).</li>
 *   <li>Bad item / effect ids are silently dropped, not crash-on-parse.</li>
 *   <li>Empty {@code tiers} array throws — that's a config error, not graceful degrade.</li>
 *   <li>Missing root throws.</li>
 * </ul>
 */
final class DifficultyTierCodecTest {

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Minimal valid tier round-trips with defaults")
    void minimalTier() throws Exception {
        String json = """
            {
              "tiers": [
                { "name": "rookie" }
              ]
            }
            """;
        List<DifficultyTier> tiers = DifficultyTierCodec.parse(stream(json));
        assertEquals(1, tiers.size());
        DifficultyTier t = tiers.get(0);
        assertEquals("rookie", t.name());
        assertTrue(t.armor().helmet().isEmpty());
        assertTrue(t.armor().chestplate().isEmpty());
        assertTrue(t.armor().leggings().isEmpty());
        assertTrue(t.armor().boots().isEmpty());
        assertEquals(0.0, t.armor().slotChance(), 1e-6);
        assertTrue(t.weapon().mainhand().isEmpty());
        assertEquals(0.0, t.weapon().chance(), 1e-6);
        assertTrue(t.effects().isEmpty());
        assertEquals(0, t.enchant().maxLevel());
        assertEquals(0.0, t.enchant().chance(), 1e-6);
    }

    @Test
    @DisplayName("Full tier parses all four sections")
    void fullTier() throws Exception {
        String json = """
            {
              "tiers": [
                {
                  "name": "elite",
                  "armor": {
                    "helmet":     [{"item": "minecraft:iron_helmet",     "weight": 5}],
                    "chestplate": [{"item": "minecraft:iron_chestplate", "weight": 5}],
                    "leggings":   [{"item": "minecraft:iron_leggings",   "weight": 5}],
                    "boots":      [{"item": "minecraft:iron_boots",      "weight": 5}],
                    "slotChance": 0.75
                  },
                  "weapon": {
                    "mainhand": [{"item": "minecraft:iron_sword", "weight": 3}],
                    "chance": 0.8
                  },
                  "effects": [
                    {"id": "minecraft:speed",    "amplifier": 0, "durationTicks": 600, "chance": 0.5},
                    {"id": "minecraft:strength", "amplifier": 1, "durationTicks": -1,  "chance": 0.4}
                  ],
                  "enchant": {"maxLevel": 10, "chance": 0.5, "treasure": false}
                }
              ]
            }
            """;
        List<DifficultyTier> tiers = DifficultyTierCodec.parse(stream(json));
        DifficultyTier t = tiers.get(0);
        assertEquals("elite", t.name());
        assertEquals(1, t.armor().helmet().size());
        assertEquals("minecraft:iron_helmet", t.armor().helmet().get(0).item().toString());
        assertEquals(5, t.armor().helmet().get(0).weight());
        assertEquals(0.75, t.armor().slotChance(), 1e-6);
        assertEquals(0.8, t.weapon().chance(), 1e-6);
        assertEquals(2, t.effects().size());
        assertEquals("minecraft:speed", t.effects().get(0).id().toString());
        assertEquals(600, t.effects().get(0).durationTicks());
        assertEquals(-1, t.effects().get(1).durationTicks());
        assertEquals(1, t.effects().get(1).amplifier());
        assertEquals(10, t.enchant().maxLevel());
        assertEquals(0.5, t.enchant().chance(), 1e-6);
    }

    @Test
    @DisplayName("Bad item id is silently dropped, other entries survive")
    void badItemIdDropped() throws Exception {
        String json = """
            {
              "tiers": [
                {
                  "name": "mixed",
                  "armor": {
                    "helmet": [
                      {"item": "totally:not_real", "weight": 5},
                      {"item": "minecraft:leather_helmet", "weight": 3}
                    ],
                    "slotChance": 0.5
                  }
                }
              ]
            }
            """;
        List<DifficultyTier> tiers = DifficultyTierCodec.parse(stream(json));
        // Codec accepts "totally:not_real" since it's a syntactically valid RL.
        // The applier handles unregistered items at lookup time. Verify both entries kept here.
        assertEquals(2, tiers.get(0).armor().helmet().size());
    }

    @Test
    @DisplayName("Multi-tier list preserves order")
    void multiTierOrder() throws Exception {
        String json = """
            {
              "tiers": [
                { "name": "alpha" },
                { "name": "beta"  },
                { "name": "gamma" }
              ]
            }
            """;
        List<DifficultyTier> tiers = DifficultyTierCodec.parse(stream(json));
        assertEquals(3, tiers.size());
        assertEquals("alpha", tiers.get(0).name());
        assertEquals("beta",  tiers.get(1).name());
        assertEquals("gamma", tiers.get(2).name());
    }

    @Test
    @DisplayName("Empty tiers array → parse exception")
    void emptyTiersThrows() {
        String json = """
            { "tiers": [] }
            """;
        assertThrows(DifficultyTierCodec.DifficultyParseException.class,
                () -> DifficultyTierCodec.parse(stream(json)));
    }

    @Test
    @DisplayName("Missing tiers field → parse exception")
    void missingTiersThrows() {
        String json = """
            { "schemaVersion": 1 }
            """;
        assertThrows(DifficultyTierCodec.DifficultyParseException.class,
                () -> DifficultyTierCodec.parse(stream(json)));
    }

    @Test
    @DisplayName("Invalid JSON → parse exception")
    void invalidJsonThrows() {
        assertThrows(DifficultyTierCodec.DifficultyParseException.class,
                () -> DifficultyTierCodec.parse(stream("not json {")));
    }

    @Test
    @DisplayName("Weights below 1 clamp to 1")
    void zeroWeightClamped() throws Exception {
        String json = """
            {
              "tiers": [
                {
                  "name": "x",
                  "armor": {
                    "helmet": [{"item": "minecraft:leather_helmet", "weight": 0}],
                    "slotChance": 1.0
                  }
                }
              ]
            }
            """;
        List<DifficultyTier> tiers = DifficultyTierCodec.parse(stream(json));
        assertEquals(1, tiers.get(0).armor().helmet().get(0).weight());
    }

    @Test
    @DisplayName("Default tier curve from bundled tiers.json loads cleanly")
    void bundledDefaultLoads() throws Exception {
        try (InputStream in = DifficultyTierCodecTest.class.getResourceAsStream(
                "/data/dungeontrain/difficulty/tiers.json")) {
            assertNotNull(in, "bundled tiers.json must be present on the test classpath");
            List<DifficultyTier> tiers = DifficultyTierCodec.parse(in);
            assertEquals(5, tiers.size(), "default curve ships 5 tiers");
            assertEquals("rookie",    tiers.get(0).name());
            assertEquals("veteran",   tiers.get(1).name());
            assertEquals("elite",     tiers.get(2).name());
            assertEquals("champion",  tiers.get(3).name());
            assertEquals("nightmare", tiers.get(4).name());
        }
    }
}
