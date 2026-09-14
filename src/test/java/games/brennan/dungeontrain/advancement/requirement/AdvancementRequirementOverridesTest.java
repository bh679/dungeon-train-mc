package games.brennan.dungeontrain.advancement.requirement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

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

    @Test
    @DisplayName("Flags parse to id → raised flags; unknown keys, wrong tabs and lowered flags are dropped")
    void parsesFlags() {
        String body = """
            {"ok":true,"units":{},"flags":{
              "dungeontrain:dungeon_train/multiplayer":{"disabled":{"ts":1,"by":"op"},"notRequired":{"ts":2,"by":""}},
              "dungeontrain:dungeon_train/carts_1000":{"notRequired":{"ts":3,"by":"op"},"hidden":{"ts":4}},
              "dungeontrain:dungeon_train/friends":{"hidden":{"ts":4}},
              "dungeontrain:dungeon_train/left_train":{"disabled":null},
              "dungeontrain:editor/used_tunnel_stairs":{"disabled":{"ts":5}},
              "minecraft:story/root":{"disabled":{"ts":5}},
              "dungeontrain:dungeon_train/nope":"disabled"
            }}""";
        Map<ResourceLocation, Set<AdvancementFlag>> f = AdvancementRequirementOverrides.parseFlags(body);
        assertEquals(2, f.size());
        assertEquals(Set.of(AdvancementFlag.DISABLED, AdvancementFlag.NOT_REQUIRED),
            f.get(ResourceLocation.parse("dungeontrain:dungeon_train/multiplayer")));
        assertEquals(Set.of(AdvancementFlag.NOT_REQUIRED),
            f.get(ResourceLocation.parse("dungeontrain:dungeon_train/carts_1000")));

        AdvancementRequirementOverrides.Payload p = AdvancementRequirementOverrides.parsePayload(body);
        assertTrue(p.values().isEmpty());
        assertEquals(Set.of(ResourceLocation.parse("dungeontrain:dungeon_train/multiplayer")),
            p.with(AdvancementFlag.DISABLED));
        assertEquals(2, p.with(AdvancementFlag.NOT_REQUIRED).size());
    }

    @Test
    @DisplayName("A payload without flags — an older relay — is empty flags, not an exception")
    void noFlagsKey() {
        AdvancementRequirementOverrides.Payload p = AdvancementRequirementOverrides.parsePayload(
            "{\"units\":{\"dungeontrain:dungeon_train/carts_1000\":{\"field\":\"threshold\",\"value\":5}}}");
        assertEquals(1, p.values().size());
        assertTrue(p.flags().isEmpty());
        assertTrue(p.with(AdvancementFlag.DISABLED).isEmpty());
        assertTrue(AdvancementRequirementOverrides.parseFlags("[]").isEmpty());
    }
}
