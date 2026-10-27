package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerraOmniaTest {

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    @Test
    @DisplayName("complete only with every biome; an empty registry completes nothing")
    void complete() {
        List<ResourceLocation> all = List.of(id("minecraft:plains"), id("biomesoplenty:bayou"));
        assertTrue(TerraOmnia.isComplete(Set.copyOf(all), all));
        assertTrue(TerraOmnia.isComplete(Set.of(id("minecraft:plains"), id("biomesoplenty:bayou"), id("x:gone")), all));
        assertFalse(TerraOmnia.isComplete(Set.of(id("minecraft:plains")), all));
        assertFalse(TerraOmnia.isComplete(Set.of(), List.of()));
    }

    @Test
    @DisplayName("groups: vanilla Overworld, Nether, End, then BoP, BetterNether, BetterEnd, Deeper Darker, then the rest")
    void groups() {
        int overworld = TerraOmnia.groupOf("minecraft", false, false);
        int nether = TerraOmnia.groupOf("minecraft", true, false);
        int end = TerraOmnia.groupOf("minecraft", false, true);
        int bop = TerraOmnia.groupOf("biomesoplenty", false, false);
        int bn = TerraOmnia.groupOf("betternether", true, false);
        int be = TerraOmnia.groupOf("betterend", false, true);
        int dd = TerraOmnia.groupOf("deeperdarker", false, false);
        int other = TerraOmnia.groupOf("vanillabackport", false, false);
        assertTrue(overworld < nether && nether < end && end < bop && bop < bn && bn < be && be < dd && dd < other);
    }

    @Test
    @DisplayName("ordered: by group, then id; never-placed biomes left out")
    void ordered() {
        Map<ResourceLocation, Integer> groups = new LinkedHashMap<>();
        groups.put(id("betterend:x"), 5);
        groups.put(id("minecraft:taiga"), 0);
        groups.put(id("minecraft:nether_wastes"), 1);
        groups.put(id("minecraft:badlands"), 0);
        groups.put(id("minecraft:the_void"), 0);
        groups.put(id("terrablender:deferred_placeholder"), 7);
        assertEquals(List.of(id("minecraft:badlands"), id("minecraft:taiga"), id("minecraft:nether_wastes"), id("betterend:x")),
                List.copyOf(TerraOmnia.ordered(groups).keySet()));
    }

    @Test
    @DisplayName("biomes the track never reaches are left out, and holding all the rest completes it")
    void unreachableLeftOut() {
        List<String> unreachable = List.of(
                "betterend:empty_aurora_cave", "betterend:empty_end_cave", "betterend:empty_smaragdant_cave",
                "betterend:jade_cave", "betterend:lush_aurora_cave", "betterend:lush_smaragdant_cave",
                "betternether:nether_mushroom_forest_edge", "betternether:nether_swampland_terraces",
                "betternether:upside_down_forest_cleared", "betternether:wart_forest_edge");
        Map<ResourceLocation, Integer> groups = new LinkedHashMap<>();
        groups.put(id("betterend:shadow_forest"), 5);
        groups.put(id("betternether:wart_forest"), 4);
        unreachable.forEach(s -> groups.put(id(s), s.startsWith("betterend") ? 5 : 4));
        Map<ResourceLocation, Integer> asked = TerraOmnia.ordered(groups);
        assertEquals(List.of(id("betternether:wart_forest"), id("betterend:shadow_forest")), List.copyOf(asked.keySet()));
        assertTrue(TerraOmnia.isComplete(Set.of(id("betternether:wart_forest"), id("betterend:shadow_forest")), asked.keySet()));
    }

    @Test
    @DisplayName("top-up keeps what is held, fills in order, stops at the target")
    void topUp() {
        List<ResourceLocation> order = List.of(id("a:1"), id("a:2"), id("a:3"), id("a:4"));
        assertEquals(List.of(id("a:1"), id("a:3")), TerraOmnia.topUp(Set.of(id("a:2"), id("z:9")), order, 4));
        assertEquals(List.of(), TerraOmnia.topUp(Set.of(id("a:1"), id("a:2")), order, 2));
        assertEquals(order, TerraOmnia.topUp(Set.of(), order, 10));
    }
}
