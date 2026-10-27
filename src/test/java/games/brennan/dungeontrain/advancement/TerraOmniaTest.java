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
    @DisplayName("top-up keeps what is held, fills in order, stops at the target")
    void topUp() {
        List<ResourceLocation> order = List.of(id("a:1"), id("a:2"), id("a:3"), id("a:4"));
        assertEquals(List.of(id("a:1"), id("a:3")), TerraOmnia.topUp(Set.of(id("a:2"), id("z:9")), order, 4));
        assertEquals(List.of(), TerraOmnia.topUp(Set.of(id("a:1"), id("a:2")), order, 2));
        assertEquals(order, TerraOmnia.topUp(Set.of(), order, 10));
    }

    @Test
    @DisplayName("every-biome photos: never-placed registry entries are not asked for; coverage, not count")
    void coversEvery() {
        List<ResourceLocation> registry = List.of(id("minecraft:plains"), id("minecraft:the_void"),
            id("terrablender:deferred_placeholder"), id("biomesoplenty:bayou"));
        assertTrue(TerraOmnia.coversEvery(List.of("minecraft:plains", "biomesoplenty:bayou"), registry));
        assertFalse(TerraOmnia.coversEvery(List.of("minecraft:plains"), registry));
        // As many ids as the registry holds, but one placeable biome missing: still not every biome.
        assertFalse(TerraOmnia.coversEvery(List.of("minecraft:plains", "x:gone", "y:gone", "z:gone"), registry));
        assertTrue(TerraOmnia.coversEvery(List.of("minecraft:plains", "Not An Id!", "biomesoplenty:bayou"), registry));
        assertFalse(TerraOmnia.coversEvery(List.of(), List.of()));
        assertFalse(TerraOmnia.coversEvery(List.of("minecraft:the_void"), List.of(id("minecraft:the_void"))));
    }
}
