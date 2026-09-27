package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The underground set kept out of legacy and sunk chunks — and what is deliberately left in. */
final class LegacyUndergroundTest {

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    @Test
    @DisplayName("deep structures are out; strongholds and surface structures stay")
    void structures() {
        assertTrue(LegacyUnderground.excludesStructure(mc("ancient_city")));
        assertTrue(LegacyUnderground.excludesStructure(mc("trial_chambers")));
        assertTrue(LegacyUnderground.excludesStructure(mc("mineshaft")));
        assertTrue(LegacyUnderground.excludesStructure(mc("mineshaft_mesa")));
        assertFalse(LegacyUnderground.excludesStructure(mc("stronghold")), "End portal / stronghold rings");
        assertFalse(LegacyUnderground.excludesStructure(mc("village_plains")));
        assertFalse(LegacyUnderground.excludesStructure(null));
    }

    @Test
    @DisplayName("geodes, dungeons and fossils are out; ores stay")
    void features() {
        assertTrue(LegacyUnderground.excludesFeature(mc("amethyst_geode")));
        assertTrue(LegacyUnderground.excludesFeature(mc("monster_room")));
        assertTrue(LegacyUnderground.excludesFeature(mc("monster_room_deep")));
        assertTrue(LegacyUnderground.excludesFeature(mc("fossil_upper")));
        assertTrue(LegacyUnderground.excludesFeature(mc("fossil_lower")));
        assertFalse(LegacyUnderground.excludesFeature(mc("ore_iron_upper")));
        assertFalse(LegacyUnderground.excludesFeature(null));
    }
}
