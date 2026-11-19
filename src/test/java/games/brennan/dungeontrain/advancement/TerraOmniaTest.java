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
            id("biomesoplenty:wintry_origin_valley"), id("biomesoplenty:moor"), id("biomesoplenty:origin_valley"),
            id("biomesoplenty:tropics"), id("biomesoplenty:wasteland_steppe"), id("biomesoplenty:fungal_jungle"),
            id("biomesoplenty:old_growth_dead_forest"), id("biomesoplenty:dead_forest"));

    private static Set<ResourceLocation> with(List<ResourceLocation> base, ResourceLocation... more) {
        Set<ResourceLocation> out = new java.util.HashSet<>(base);
        out.addAll(List.of(more));
        return out;
    }

    @Test
    @DisplayName("the eight rare biomes: any 4 complete it, 3 do not; the rest are all still needed")
    void fourOfEightRare() {
        assertEquals(Set.copyOf(RARE), TerraOmnia.RARE);
        List<ResourceLocation> common = List.of(id("minecraft:plains"), id("biomesoplenty:auroral_garden"));
        List<ResourceLocation> all = new java.util.ArrayList<>(common);
        all.addAll(RARE);
        assertTrue(TerraOmnia.isComplete(with(common, RARE.get(0), RARE.get(4), RARE.get(5), RARE.get(7)), all));
        assertTrue(TerraOmnia.isComplete(with(common, RARE.get(1), RARE.get(2), RARE.get(3), RARE.get(5), RARE.get(6)), all));
        assertFalse(TerraOmnia.isComplete(with(common, RARE.get(2), RARE.get(5), RARE.get(7)), all));
        assertFalse(TerraOmnia.isComplete(with(List.of(id("minecraft:plains")), RARE.get(0), RARE.get(1), RARE.get(2), RARE.get(3)), all));
    }

    @Test
    @DisplayName("fewer than 4 rare biomes in the registry: those present are all needed")
    void rareMissingFromRegistry() {
        List<ResourceLocation> all = List.of(id("minecraft:plains"), RARE.get(3));
        assertFalse(TerraOmnia.isComplete(Set.of(id("minecraft:plains")), all));
        assertTrue(TerraOmnia.isComplete(Set.of(id("minecraft:plains"), RARE.get(3)), all));
        assertTrue(TerraOmnia.isComplete(Set.of(id("minecraft:plains")), List.of(id("minecraft:plains"))));
    }

    @Test
    @DisplayName("progress counts the rare ones only up to 4, out of the rest plus 4")
    void progress() {
        List<ResourceLocation> all = new java.util.ArrayList<>(List.of(id("minecraft:plains"), id("minecraft:taiga")));
        all.addAll(RARE);
        assertEquals(new TerraOmnia.Progress(0, 6), TerraOmnia.progress(Set.of(), all));
        assertEquals(new TerraOmnia.Progress(5, 6), TerraOmnia.progress(Set.of(id("minecraft:plains"),
                RARE.get(0), RARE.get(1), RARE.get(2), RARE.get(3), RARE.get(4)), all));
    }

    @Test
    @DisplayName("spare rare: unridden rare biomes once 4 are ridden; never a ridden or common one")
    void spareRare() {
        Set<ResourceLocation> three = Set.of(RARE.get(0), RARE.get(1), RARE.get(5));
        Set<ResourceLocation> four = Set.of(RARE.get(0), RARE.get(1), RARE.get(5), RARE.get(7));
        assertFalse(TerraOmnia.isSpareRare(RARE.get(2), three));
        assertTrue(TerraOmnia.isSpareRare(RARE.get(2), four));
        assertFalse(TerraOmnia.isSpareRare(RARE.get(0), four));
        assertFalse(TerraOmnia.isSpareRare(id("minecraft:plains"), four));
    }

    @Test
    @DisplayName("enough rare: 4 of the 8 or more, all of them when the registry holds fewer, never when it holds none")
    void enoughRare() {
        assertFalse(TerraOmnia.hasEnoughRare(Set.of(RARE.get(0), RARE.get(1), RARE.get(5)), RARE));
        assertTrue(TerraOmnia.hasEnoughRare(Set.of(RARE.get(0), RARE.get(1), RARE.get(5), RARE.get(7)), RARE));
        assertTrue(TerraOmnia.hasEnoughRare(Set.of(RARE.get(0), RARE.get(1), RARE.get(2), RARE.get(3), RARE.get(4)), RARE));
        assertTrue(TerraOmnia.hasEnoughRare(Set.of(RARE.get(0)), List.of(RARE.get(0))));
        assertFalse(TerraOmnia.hasEnoughRare(Set.of(RARE.get(1)), List.of(RARE.get(0))));
        assertFalse(TerraOmnia.hasEnoughRare(Set.of(id("minecraft:plains")), List.of(id("minecraft:plains"))));
    }

    @Test
    @DisplayName("Coffee Table Book's check still needs every biome, all eight rare ones included")
    void holdsEveryStaysStrict() {
        List<ResourceLocation> all = new java.util.ArrayList<>(List.of(id("minecraft:plains")));
        all.addAll(RARE);
        assertFalse(TerraOmnia.holdsEvery(with(List.of(id("minecraft:plains")), RARE.get(0), RARE.get(1)), all));
        assertTrue(TerraOmnia.holdsEvery(Set.copyOf(all), all));
        assertFalse(TerraOmnia.holdsEvery(Set.of(), List.of()));
    }
}
