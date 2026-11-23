package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.editor.PotPlantBandGate.Source;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PotPlantBandGate}: a Random pot's modded plants wait for the band they come from. */
final class PotPlantBandGateTest {

    private static final long START = 10_000L;
    private static final CycleLayout.Fades FADES =
        new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);

    private static WorldGenCycle shippedCycle() {
        List<LegacySpan> eras = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) eras.add(new LegacySpan(k, 3000, 480, 6000));
        LegacySpan[] spans = eras.toArray(new LegacySpan[0]);
        List<String> warnings = new ArrayList<>();
        CycleLayout layout = CycleLayout.parse(CycleLayout.DEFAULT_ORDER, FADES, spans, t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
            spans, layout, 0);
    }

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    @Test
    @DisplayName("plants map to their band by namespace; vanilla overworld and unknown mods are ungated")
    void sources() {
        assertEquals(Source.ANY, PotPlantBandGate.sourceOf(id("minecraft:poppy")));
        assertEquals(Source.NETHER, PotPlantBandGate.sourceOf(id("minecraft:crimson_fungus")));
        assertEquals(Source.BOP, PotPlantBandGate.sourceOf(id("biomesoplenty:rose")));
        assertEquals(Source.BETTER_NETHER, PotPlantBandGate.sourceOf(id("betternether:soul_lily")));
        assertEquals(Source.BETTER_END, PotPlantBandGate.sourceOf(id("betterend:umbrella_moss")));
        assertEquals(Source.OTHERSIDE, PotPlantBandGate.sourceOf(id("deeperdarker:sculk_vines")));
        assertEquals(Source.ANY, PotPlantBandGate.sourceOf(id("vanillabackport:open_eyeblossom")));
    }

    @Test
    @DisplayName("at spawn only ungated plants; the filter drops the rest")
    void spawnOnlyUngated() {
        WorldGenCycle c = shippedCycle();
        assertEquals(Set.of(Source.ANY), PotPlantBandGate.reached(c, 0));
        assertEquals(Set.of(Source.ANY), PotPlantBandGate.reached(c, START + 100));
        List<ResourceLocation> kept = PotPlantBandGate.filter(
            List.of(id("minecraft:poppy"), id("minecraft:warped_roots"), id("betterend:umbrella_moss")),
            PotPlantBandGate.reached(c, START + 100));
        assertEquals(List.of(id("minecraft:poppy")), kept);
    }

    @Test
    @DisplayName("each band unlocks at its first slot and stays unlocked; Nether before BetterNether/Otherside/BetterEnd")
    void unlocksInOrderAndStay() {
        WorldGenCycle c = shippedCycle();
        Map<Source, Long> firstAt = new EnumMap<>(Source.class);
        Set<Source> prev = Set.of(Source.ANY);
        long end = START + 6 * c.period();
        for (long x = 0; x < end; x += 211) {
            Set<Source> now = PotPlantBandGate.reached(c, x);
            assertTrue(now.containsAll(prev), "un-reached " + prev + " -> " + now + " at x=" + x);
            for (Source s : now) firstAt.putIfAbsent(s, x);
            prev = now;
        }
        for (Source s : Source.values()) assertTrue(firstAt.containsKey(s), s + " never reached");
        long nether = firstAt.get(Source.NETHER);
        assertTrue(nether > START, "Nether plants before the first band");
        assertTrue(firstAt.get(Source.BETTER_NETHER) > nether);
        assertTrue(firstAt.get(Source.OTHERSIDE) > nether);
        assertTrue(firstAt.get(Source.BETTER_END) > nether);
        assertTrue(firstAt.get(Source.WWOO) > nether, "WWOO comes after the first Nether");
        assertFalse(c.isNetherCore((int) START + 100), "sanity");
    }

    @Test
    @DisplayName("behind the anchor (mirrored layout) only ungated plants")
    void reversedSideGated() {
        WorldGenCycle c = shippedCycle();
        assertEquals(Set.of(Source.ANY), PotPlantBandGate.reached(c, START - 50_000));
    }
}
