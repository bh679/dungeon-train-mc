package games.brennan.dungeontrain.advancement.requirement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The relay payload parser: accepts our advancements with a known field and a sane value, drops the rest. */
final class AdvancementRequirementOverridesTest {

    @Test
    @DisplayName("Valid units parse to id → value")
    void parsesValidUnits() {
        Map<ResourceLocation, Long> m = AdvancementRequirementOverrides.parse("""
            {"ok":true,"units":{
              "dungeontrain:dungeon_train/carts_1000":{"field":"threshold","value":500,"shipped":1000,"by":"op"},
              "dungeontrain:dungeon_train/enjoying_the_ride":{"field":"thresholdTicks","value":36000}
            }}""");
        assertEquals(2, m.size());
        assertEquals(500L, m.get(ResourceLocation.parse("dungeontrain:dungeon_train/carts_1000")));
        assertEquals(36000L, m.get(ResourceLocation.parse("dungeontrain:dungeon_train/enjoying_the_ride")));
    }

    @Test
    @DisplayName("Wrong namespace, tab, field, or value is dropped without failing the rest")
    void dropsMalformed() {
        Map<ResourceLocation, Long> m = AdvancementRequirementOverrides.parse("""
            {"units":{
              "minecraft:story/root":{"field":"threshold","value":5},
              "dungeontrain:editor/used_tunnel_stairs":{"field":"threshold","value":5},
              "dungeontrain:dungeon_train/a":{"field":"count","value":5},
              "dungeontrain:dungeon_train/b":{"field":"threshold","value":0},
              "dungeontrain:dungeon_train/c":{"field":"threshold","value":-3},
              "dungeontrain:dungeon_train/d":{"field":"threshold","value":"12"},
              "dungeontrain:dungeon_train/e":{"field":"threshold","value":1e12},
              "dungeontrain:dungeon_train/f":"nope",
              "dungeontrain:dungeon_train/ok":{"field":"thresholdMeters","value":12}
            }}""");
        assertEquals(1, m.size());
        assertEquals(12L, m.get(ResourceLocation.parse("dungeontrain:dungeon_train/ok")));
    }

    @Test
    @DisplayName("A body with no units, or not an object, is an empty set — never an exception")
    void emptyShapes() {
        assertTrue(AdvancementRequirementOverrides.parse("{}").isEmpty());
        assertTrue(AdvancementRequirementOverrides.parse("[]").isEmpty());
        assertTrue(AdvancementRequirementOverrides.parse("{\"units\":[]}").isEmpty());
    }
}
