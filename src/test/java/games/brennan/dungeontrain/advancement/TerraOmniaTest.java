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

    private static final List<ResourceLocation> RARE = List.of(
            id("biomesoplenty:wintry_origin_valley"), id("biomesoplenty:origin_valley"), id("biomesoplenty:hot_springs"),
            id("biomesoplenty:auroral_garden"), id("biomesoplenty:tropics"));

    private static Set<ResourceLocation> with(List<ResourceLocation> base, ResourceLocation... more) {
        Set<ResourceLocation> out = new java.util.HashSet<>(base);
        out.addAll(List.of(more));
        return out;
    }

    @Test
    @DisplayName("the five rare biomes: any 2 complete it, 1 does not; the rest are all still needed")
    void twoOfFiveRare() {
        assertEquals(Set.copyOf(RARE), TerraOmnia.RARE);
        List<ResourceLocation> common = List.of(id("minecraft:plains"), id("biomesoplenty:bayou"));
        List<ResourceLocation> all = new java.util.ArrayList<>(common);
        all.addAll(RARE);
        assertTrue(TerraOmnia.isComplete(with(common, RARE.get(0), RARE.get(4)), all));
        assertTrue(TerraOmnia.isComplete(with(common, RARE.get(1), RARE.get(2), RARE.get(3)), all));
        assertFalse(TerraOmnia.isComplete(with(common, RARE.get(2)), all));
        assertFalse(TerraOmnia.isComplete(with(List.of(id("minecraft:plains")), RARE.get(0), RARE.get(1)), all));
    }

    @Test
    @DisplayName("fewer than 2 rare biomes in the registry: those present are all needed")
    void rareMissingFromRegistry() {
        List<ResourceLocation> all = List.of(id("minecraft:plains"), RARE.get(3));
        assertFalse(TerraOmnia.isComplete(Set.of(id("minecraft:plains")), all));
        assertTrue(TerraOmnia.isComplete(Set.of(id("minecraft:plains"), RARE.get(3)), all));
        assertTrue(TerraOmnia.isComplete(Set.of(id("minecraft:plains")), List.of(id("minecraft:plains"))));
    }

    @Test
    @DisplayName("progress counts the rare ones only up to 2, out of the rest plus 2")
    void progress() {
        List<ResourceLocation> all = new java.util.ArrayList<>(List.of(id("minecraft:plains"), id("minecraft:taiga")));
        all.addAll(RARE);
        assertEquals(new TerraOmnia.Progress(0, 4), TerraOmnia.progress(Set.of(), all));
        assertEquals(new TerraOmnia.Progress(3, 4),
                TerraOmnia.progress(Set.of(id("minecraft:plains"), RARE.get(0), RARE.get(1), RARE.get(2)), all));
    }

    @Test
    @DisplayName("spare rare: unridden rare biomes once 2 are ridden; never a ridden or common one")
    void spareRare() {
        Set<ResourceLocation> one = Set.of(RARE.get(0));
        Set<ResourceLocation> two = Set.of(RARE.get(0), RARE.get(1));
        assertFalse(TerraOmnia.isSpareRare(RARE.get(2), one));
        assertTrue(TerraOmnia.isSpareRare(RARE.get(2), two));
        assertFalse(TerraOmnia.isSpareRare(RARE.get(0), two));
        assertFalse(TerraOmnia.isSpareRare(id("minecraft:plains"), two));
    }

    @Test
    @DisplayName("Coffee Table Book: any 4 rare biomes complete it, 3 do not; the rest are all still needed")
    void coffeeTableFourOfFiveRare() {
        List<ResourceLocation> common = List.of(id("minecraft:plains"), id("biomesoplenty:bayou"));
        List<ResourceLocation> all = new java.util.ArrayList<>(common);
        all.addAll(RARE);
        assertTrue(TerraOmnia.isCoffeeTableComplete(with(common, RARE.get(0), RARE.get(1), RARE.get(2), RARE.get(3)), all));
        assertTrue(TerraOmnia.isCoffeeTableComplete(with(common, RARE.get(1), RARE.get(2), RARE.get(3), RARE.get(4)), all));
        assertTrue(TerraOmnia.isCoffeeTableComplete(Set.copyOf(all), all));
        assertFalse(TerraOmnia.isCoffeeTableComplete(with(common, RARE.get(0), RARE.get(1), RARE.get(2)), all));
        assertFalse(TerraOmnia.isCoffeeTableComplete(Set.copyOf(RARE), all));
        assertFalse(TerraOmnia.isCoffeeTableComplete(Set.of(), List.of()));
    }

    @Test
    @DisplayName("Coffee Table Book's count: a fifth rare biome adds nothing; fewer in the registry are all needed")
    void coffeeTableProgress() {
        List<ResourceLocation> all = new java.util.ArrayList<>(List.of(id("minecraft:plains")));
        all.addAll(RARE);
        assertEquals(new TerraOmnia.Progress(0, 5), TerraOmnia.coffeeTableProgress(Set.of(), all));
        assertEquals(new TerraOmnia.Progress(4, 5), TerraOmnia.coffeeTableProgress(Set.copyOf(RARE), all));
        assertEquals(new TerraOmnia.Progress(5, 5), TerraOmnia.coffeeTableProgress(Set.copyOf(all), all));
        assertEquals(new TerraOmnia.Progress(1, 2), TerraOmnia.coffeeTableProgress(Set.of(RARE.get(0)), RARE.subList(0, 2)));
    }
}
