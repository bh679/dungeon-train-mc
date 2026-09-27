package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.density.UpsideDownTrackFlatten;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandConfig;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LostCityStructuresTest {

    private static final long START = 10_000L;
    private static final long SEED = 1450L;
    private static final int LOST_CITY_FADE = LegacyBandConfig.LOST_CITY_DEFAULTS.fade();

    /** The shipped era defaults, with Lost City's own (longer) fade as it ships. */
    private static LegacySpan[] eras() {
        LegacySpan[] eras = CycleLayoutTest.eraDefaults();
        int i = LegacyBandKind.LOST_CITY.ordinal();
        eras[i] = new LegacySpan(LegacyBandKind.LOST_CITY, eras[i].leadGap(), LOST_CITY_FADE, eras[i].hold());
        return eras;
    }

    private static CycleLayout layout(String order) {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse(order, CycleLayoutTest.FADES, eras(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return l;
    }

    private static WorldGenCycle cycle(CycleLayout layout) {
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
                eras(), layout, 0);
    }

    private static final CycleLayout LAYOUT = layout(CycleLayout.DEFAULT_ORDER);
    private static final WorldGenCycle C = cycle(LAYOUT);
    private static final long P = LAYOUT.period();

    /** World X of base coordinate {@code u} on run {@code k}. */
    private static int x(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    /** Lost City's own legacy-run slot. */
    private static int slot() {
        return LAYOUT.legacySlotOf(LegacyBandKind.LOST_CITY);
    }

    private static long legacyStart() {
        return LAYOUT.start(slot());
    }

    /** Base coordinate where the Lost City core starts. */
    private static long coreStart() {
        return legacyStart() + LAYOUT.eraCoreStart(slot(), LAYOUT.eraIndex(LegacyBandKind.LOST_CITY));
    }

    /** Fraction of chunks across an X range (a 64-chunk-deep Z strip) that may host a city. */
    private static double share(long fromU, long toU) {
        int hits = 0;
        int total = 0;
        for (int cx = x(fromU, 0) >> 4; cx < x(toU, 0) >> 4; cx++) {
            for (int cz = -32; cz < 32; cz++) {
                total++;
                if (LostCityStructures.allowedAt(SEED, C, cx, cz)) hits++;
            }
        }
        return (double) hits / total;
    }

    @Test
    @DisplayName("Lost City runs 4000 blocks alone between BetterNether and BetterEnd, entered over its own 750-block fade")
    void shippedPlacement() {
        int slot = slot();
        assertEquals(CycleLayout.Type.NETHER, LAYOUT.slot(slot - 1).type());
        assertEquals(CycleLayout.Style.BETTER, LAYOUT.slot(slot - 1).style());
        assertEquals(CycleLayout.Type.END, LAYOUT.slot(slot + 1).type());
        assertEquals(CycleLayout.Style.BETTER, LAYOUT.slot(slot + 1).style());
        assertEquals(1, LAYOUT.eras(slot).length);
        assertEquals(0, LAYOUT.eraIndex(LegacyBandKind.LOST_CITY));
        assertEquals(4000L, C.legacyLen(LegacyBandKind.LOST_CITY));
        assertEquals(750L, LAYOUT.fadeBefore(slot, 0));
        assertEquals(480L, LAYOUT.fadeBefore(slot, 1));                // the run's exit fade
        assertEquals(legacyStart() + 750L, coreStart());
        WorldGenCycle.LegacyHit in = C.legacyAt(x(legacyStart() + 375L, 0));
        assertEquals(null, in.from());
        assertEquals(LegacyBandKind.LOST_CITY, in.to());
        long lead = LAYOUT.legacyLeadIn(slot);                         // the ramp began on the Nether's mountains
        assertEquals((lead + 375.0 + 1) / (lead + 750 + 1), in.t(), 1e-9);
        // Amplified now crossfades straight into Beta.
        int main = LAYOUT.legacySlotOf(LegacyBandKind.AMPLIFIED);
        assertEquals(LAYOUT.eraIndex(LegacyBandKind.AMPLIFIED) + 1, LAYOUT.eraIndex(LegacyBandKind.BETA));
        assertEquals(main, LAYOUT.legacySlotOf(LegacyBandKind.BETA));
    }

    @Test
    @DisplayName("cities thicken in across the entry crossfade and fill the core")
    void entryRamp() {
        long cs = coreStart();
        double early = share(cs - 740L, cs - 500L);
        double late = share(cs - 250L, cs - 10L);
        double core = share(cs + 1000L, cs + 3000L);
        assertTrue(early > 0.0, "the first buildings appear inside the crossfade");
        assertTrue(early < late && late < core, early + " < " + late + " < " + core);
        assertEquals(1.0, core, 1e-9);
        assertEquals(0.0, share(cs - 2500L, cs - 1000L), 1e-9);        // BetterNether: never
    }

    @Test
    @DisplayName("the first buildings stand on the Nether's exit mountains, and none before them")
    void leadInOnTheMountains() {
        long lc = legacyStart();
        long lead = LAYOUT.legacyLeadIn(slot());
        assertTrue(lead > 0L);
        assertTrue(share(lc - lead, lc) > 0.0, "some buildings on the mountains");
        assertTrue(share(lc - lead, lc) < share(lc, coreStart()), "fewer on the mountains than in the run-in");
        assertEquals(0.0, share(lc - lead - 1500L, lc - lead - 16L), 1e-9);   // the Nether proper: never
    }

    @Test
    @DisplayName("a start is allowed only on a chunk that rolled Lost City")
    void followsTheChunkRoll() {
        long cs = coreStart();
        for (int cx = x(cs - 750L, 0) >> 4; cx < x(cs, 0) >> 4; cx++) {
            for (int cz = -16; cz < 16; cz++) {
                boolean lostCity = LegacyBands.kindOfChunk(SEED, C, cx, cz) == LegacyBandKind.LOST_CITY;
                assertEquals(lostCity, LostCityStructures.allowedAt(SEED, C, cx, cz));
            }
        }
    }

    @Test
    @DisplayName("the exit keeps its margin: nothing within 128 blocks of the core's end, nothing in the End")
    void exitMargin() {
        long ce = coreStart() + 4000L;
        int lastOk = Math.floorDiv(x(ce, 0) - 1 - LostCityStructures.EXIT_MARGIN_BLOCKS - 15, 16);
        assertTrue(LostCityStructures.allowedAt(SEED, C, lastOk, 0));
        assertFalse(LostCityStructures.allowedAt(SEED, C, lastOk + 1, 0));
        assertEquals(0.0, share(ce - 100L, ce + 480L), 1e-9);
        assertEquals(0.0, share(ce + 1000L, ce + 3000L), 1e-9);
    }

    @Test
    @DisplayName("run 1 stretches the era, and cities follow it")
    void doubling() {
        long cs = coreStart();
        assertTrue(LostCityStructures.allowedAt(SEED, C, x(cs + 2000L, 1) >> 4, 0));
        assertFalse(LostCityStructures.allowedAt(SEED, C, x(cs - 2500L, 1) >> 4, 0));
    }

    @Test
    @DisplayName("a disabled Lost City era allows no city anywhere")
    void disabled() {
        CycleLayout without = layout(CycleLayout.DEFAULT_ORDER.replace("legacy:wwoo:lost_city=4000, ", ""));
        assertEquals(-1, without.legacySlotOf(LegacyBandKind.LOST_CITY));
        WorldGenCycle c = cycle(without);
        for (long u = 0; u < without.period(); u += 500) {
            assertFalse(LostCityStructures.allowedAt(SEED, c, (int) (START + u) >> 4, 0));
        }
    }

    @Test
    @DisplayName("the flat track zone ramps in inside the slot and never reaches the Nether's mountains or the End")
    void flattened() {
        long slotStart = legacyStart();
        long slotEnd = slotStart + LAYOUT.length(slot());
        long ramp = UpsideDownTrackFlatten.BAND_RAMP;
        assertEquals(1.0, UpsideDownTrackFlatten.bandWeight(C, x(coreStart() + 2000L, 0)), 1e-9);
        assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(C, x(slotStart - 1L, 0)), 1e-9);          // lead-in: Nether
        assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(C, x(slotStart - 100L, 0)), 1e-9);
        assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(C, x(slotStart, 0)), 1e-9);               // edge: none yet
        double in = UpsideDownTrackFlatten.bandWeight(C, x(slotStart + ramp / 2, 0));
        assertTrue(in > 0.0 && in < 1.0, "ramps in inside the slot: " + in);
        assertEquals(1.0, UpsideDownTrackFlatten.bandWeight(C, x(slotStart + ramp, 0)), 1e-9);
        assertTrue(UpsideDownTrackFlatten.bandWeight(C, x(slotEnd - ramp / 2, 0)) < 1.0);
        assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(C, x(slotEnd, 0)), 1e-9);                 // BetterEnd
        assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(C, x(slotEnd + 100L, 0)), 1e-9);
    }

    @Test
    @DisplayName("Lost City structures: the big_lost_city namespace and DT's trackside copies, nothing else")
    void namespace() {
        assertTrue(LostCityStructures.isLostCityStructure(ResourceLocation.parse("big_lost_city:tallskyscraper")));
        assertTrue(LostCityStructures.isLostCityStructure(COPY));
        assertTrue(LostCityStructures.isTracksideCopy(COPY));
        assertFalse(LostCityStructures.isTracksideCopy(ORIGINAL));
        assertFalse(LostCityStructures.isLostCityStructure(ResourceLocation.parse("minecraft:village_plains")));
        assertFalse(LostCityStructures.isLostCityStructure(ResourceLocation.parse("dungeontrain:lost_city")));
        assertFalse(LostCityStructures.isLostCityStructure(ResourceLocation.parse("dungeontrain:end_city")));
        assertFalse(LostCityStructures.isLostCityStructure(null));
    }

    private static final ResourceLocation COPY = ResourceLocation.parse("dungeontrain:lost_city/tallskyscraper");
    private static final ResourceLocation ORIGINAL = ResourceLocation.parse("big_lost_city:tallskyscraper");

    @Test
    @DisplayName("a trackside copy starts only within 160 blocks of the track; an original ignores the track")
    void trackside() {
        int cx = x(coreStart() + 2000L, 0) >> 4;
        int trackZ = 8;
        assertTrue(LostCityStructures.allowedAt(SEED, C, cx, 0, COPY, trackZ));
        assertTrue(LostCityStructures.allowedAt(SEED, C, cx, (trackZ + 160) >> 4, COPY, trackZ));
        assertTrue(LostCityStructures.allowedAt(SEED, C, cx, (trackZ - 160) >> 4, COPY, trackZ));
        assertFalse(LostCityStructures.allowedAt(SEED, C, cx, (trackZ + 200) >> 4, COPY, trackZ));
        assertFalse(LostCityStructures.allowedAt(SEED, C, cx, (trackZ - 200) >> 4, COPY, trackZ));
        assertTrue(LostCityStructures.allowedAt(SEED, C, cx, (trackZ + 2000) >> 4, ORIGINAL, trackZ));
        // Beside the track but outside the era: the era rule still applies to copies.
        assertFalse(LostCityStructures.allowedAt(SEED, C, x(coreStart() - 2500L, 0) >> 4, 0, COPY, trackZ));
    }

    @Test
    @DisplayName("every trackside copy is its original with only the biomes widened, weighted like it in the set")
    void copiesMatchTheirOriginals() throws Exception {
        String dir = "/data/dungeontrain/worldgen/structure/lost_city/";
        com.google.gson.JsonObject set = json("/data/dungeontrain/worldgen/structure_set/lost_city.json");
        java.util.Map<String, Integer> weights = new java.util.HashMap<>();
        for (com.google.gson.JsonElement e : set.getAsJsonArray("structures")) {
            com.google.gson.JsonObject o = e.getAsJsonObject();
            weights.put(o.get("structure").getAsString(), o.get("weight").getAsInt());
        }
        String[] big = {"blackskyscraper", "redskyscraper", "ruindedredskyscraper", "ruinedblackskyscraper",
                "ruinedskyscraper", "tallskyscraper", "powerplant", "house_1", "house_2", "house_3", "store_1",
                "warehouse", "ferriswheel"};
        for (String name : big) {
            com.google.gson.JsonObject copy = json(dir + name + ".json");
            assertEquals("big_lost_city:" + name, copy.get("start_pool").getAsString(), name);
            assertEquals("#dungeontrain:lost_city_trackside", copy.get("biomes").getAsString(), name);
            assertEquals("beard_thin", copy.get("terrain_adaptation").getAsString(), name);
            assertEquals("big_lost_city", copy.getAsJsonArray("neoforge:conditions").get(0).getAsJsonObject()
                    .get("modid").getAsString(), name);
            assertEquals(weights.get("big_lost_city:" + name), weights.get("dungeontrain:lost_city/" + name), name);
        }
        assertEquals(42 + big.length, weights.size());
    }

    private static com.google.gson.JsonObject json(String path) throws Exception {
        try (var in = LostCityStructuresTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "missing " + path);
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
