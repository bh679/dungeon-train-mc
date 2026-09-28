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
        double core = share(cs + 2500L, cs + 3800L);                       // past the density fade
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
                if (!lostCity) assertFalse(LostCityStructures.allowedAt(SEED, C, cx, cz));     // never off a Lost City chunk
            }
        }
        for (int cx = x(cs + 2500L, 0) >> 4; cx < x(cs + 3000L, 0) >> 4; cx++) {              // full density: exactly the roll
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
        // pools: the mod's own and DT's variant pools, nothing else
        assertTrue(LostCityStructures.isLostCityPool(ORIGINAL));
        assertTrue(LostCityStructures.isLostCityPool(COPY));
        assertFalse(LostCityStructures.isLostCityPool(ResourceLocation.parse("minecraft:village/plains/town_centers")));
        assertFalse(LostCityStructures.isLostCityPool(null));
    }

    private static final ResourceLocation COPY = ResourceLocation.parse("dungeontrain:lost_city/tallskyscraper");
    private static final ResourceLocation ORIGINAL = ResourceLocation.parse("big_lost_city:tallskyscraper");

    @Test
    @DisplayName("nothing on the range; half the grid from the foot of its fall, full 2900 blocks on")
    void fadeIn() {
        long lead = LAYOUT.legacyLeadIn(slot());
        long foot = LostCityStructures.fallFoot(C);
        assertEquals(2L * 40L, foot);                                                     // megaHold 0 + two 40-block stages
        assertTrue(foot < lead);
        long leadInStart = LAYOUT.start(slot()) - lead;
        assertEquals(0.0, LostCityStructures.density(C, x(leadInStart, 0) >> 4), 1e-9);              // the plateau
        assertEquals(0.0, LostCityStructures.density(C, x(leadInStart + foot - 32, 0) >> 4), 1e-9);  // still on the fall
        double atFoot = LostCityStructures.density(C, x(leadInStart + foot + 16, 0) >> 4);
        assertTrue(atFoot >= 0.5 && atFoot < 0.52, "the floor at the foot: " + atFoot);
        assertEquals(0.75, LostCityStructures.density(C, x(leadInStart + foot + 1450, 0) >> 4), 0.01);
        assertEquals(1.0, LostCityStructures.density(C, x(leadInStart + foot + 2916, 0) >> 4), 1e-9);
        assertEquals(1.0, LostCityStructures.density(C, x(coreStart() + 3800L, 0) >> 4), 1e-9);
        assertEquals(0.0, LostCityStructures.density(C, x(coreStart() - 2500L, 0) >> 4), 1e-9);       // the Nether proper
        // the roll follows the density over a block of chunks, and is deterministic
        int cx = x(leadInStart + foot + 1450, 0) >> 4;                                                  // density ~0.75
        int kept = 0;
        for (int cz = -100; cz < 100; cz++) if (LostCityStructures.allowedAt(SEED, C, cx, cz)) kept++;
        assertTrue(kept > 120 && kept < 180, "about 75% kept: " + kept);
        int full = 0;
        for (int cz = -100; cz < 100; cz++) if (LostCityStructures.allowedAt(SEED, C, x(coreStart() + 3500L, 0) >> 4, cz)) full++;
        assertEquals(200, full);
        assertEquals(LostCityStructures.allowedAt(SEED, C, cx, 7), LostCityStructures.allowedAt(SEED, C, cx, 7));
    }

    @Test
    @DisplayName("Lap 1's WWOO stretch keeps a few percent of its starts; the vanilla stretches none")
    void wwooForetaste() {
        int wwooSlot = 2;
        int cx = x(LAYOUT.start(wwooSlot) + LAYOUT.length(wwooSlot) / 2, 0) >> 4;
        assertEquals(LostCityStructures.WWOO_STRETCH_DENSITY, LostCityStructures.density(C, cx), 1e-9);
        int kept = 0;
        for (int cz = -500; cz < 500; cz++) if (LostCityStructures.allowedAt(SEED, C, cx, cz)) kept++;
        assertTrue(kept > 20 && kept < 65, "about 4% kept: " + kept);
        assertEquals(0.0, LostCityStructures.density(C, x(1000L, 0) >> 4), 1e-9);              // lap-0 vanilla lead
        assertFalse(LostCityStructures.allowedAt(SEED, C, x(1000L, 0) >> 4, 0));
    }

    /** The small buildings: DT's copy is the original with only the biomes widened. */
    private static final String[] SMALL = {"house_1", "house_2", "house_3", "store_1", "ferriswheel"};

    /** The big buildings: DT's structure starts from a DT pool that rolls one of several looks. */
    private static final String[] VARIED = {"blackskyscraper", "redskyscraper", "ruindedredskyscraper",
            "ruinedblackskyscraper", "ruinedskyscraper", "tallskyscraper", "powerplant", "warehouse"};

    /** The mod's unplaced big models, placed by DT only. */
    private static final String[] ADDED = {"parking_garage", "warship"};

    private static java.util.Map<String, Integer> setWeights() throws Exception {
        com.google.gson.JsonObject set = json("/data/dungeontrain/worldgen/structure_set/lost_city.json");
        java.util.Map<String, Integer> weights = new java.util.HashMap<>();
        for (com.google.gson.JsonElement e : set.getAsJsonArray("structures")) {
            com.google.gson.JsonObject o = e.getAsJsonObject();
            weights.put(o.get("structure").getAsString(), o.get("weight").getAsInt());
        }
        return weights;
    }

    private static void assertTracksideShape(com.google.gson.JsonObject copy, String name) {
        assertEquals("beard_thin", copy.get("terrain_adaptation").getAsString(), name);
        assertEquals("big_lost_city", copy.getAsJsonArray("neoforge:conditions").get(0).getAsJsonObject()
                .get("modid").getAsString(), name);
    }

    @Test
    @DisplayName("every small trackside copy is its original with only the biomes widened, weighted like it in the set")
    void copiesMatchTheirOriginals() throws Exception {
        String dir = "/data/dungeontrain/worldgen/structure/lost_city/";
        java.util.Map<String, Integer> weights = setWeights();
        for (String name : SMALL) {
            com.google.gson.JsonObject copy = json(dir + name + ".json");
            assertEquals("big_lost_city:" + name, copy.get("start_pool").getAsString(), name);
            assertEquals("#dungeontrain:lost_city_trackside", copy.get("biomes").getAsString(), name);
            assertTracksideShape(copy, name);
            assertEquals(weights.get("big_lost_city:" + name), weights.get("dungeontrain:lost_city/" + name), name);
        }
        for (String name : VARIED) {
            assertEquals(weights.get("big_lost_city:" + name), weights.get("dungeontrain:lost_city/" + name), name);
        }
        assertTrue(weights.get("dungeontrain:lost_city/parking_garage") > 0);
        assertTrue(weights.get("dungeontrain:lost_city/warship") > 0);
        assertEquals(42 + SMALL.length + VARIED.length + ADDED.length, weights.size());
    }

    @Test
    @DisplayName("every big building starts from a DT pool whose looks all place the mod's template through a DT processor list")
    void variantPoolsAreSound() throws Exception {
        String dir = "/data/dungeontrain/worldgen/structure/lost_city/";
        for (String name : concat(VARIED, ADDED)) {
            com.google.gson.JsonObject structure = json(dir + name + ".json");
            assertEquals("dungeontrain:lost_city/" + name, structure.get("start_pool").getAsString(), name);
            assertEquals(name.equals("warship") ? "#dungeontrain:lost_city_water" : "#dungeontrain:lost_city_trackside",
                    structure.get("biomes").getAsString(), name);
            assertTracksideShape(structure, name);
            com.google.gson.JsonObject pool = json("/data/dungeontrain/worldgen/template_pool/lost_city/" + name + ".json");
            assertEquals("dungeontrain:lost_city/" + name, pool.get("name").getAsString());
            com.google.gson.JsonArray elements = pool.getAsJsonArray("elements");
            assertTrue(elements.size() >= 3, name + " has several looks");
            java.util.Set<String> lists = new java.util.HashSet<>();
            for (com.google.gson.JsonElement e : elements) {
                com.google.gson.JsonObject el = e.getAsJsonObject().getAsJsonObject("element");
                assertTrue(e.getAsJsonObject().get("weight").getAsInt() > 0);
                assertEquals("minecraft:single_pool_element", el.get("element_type").getAsString());
                assertTrue(el.get("location").getAsString().startsWith("big_lost_city:"), name + " places the mod's template");
                String list = el.get("processors").getAsString();
                assertTrue(list.startsWith("dungeontrain:lost_city/"), list);
                assertTrue(lists.add(list), name + " lists each look once: " + list);
                com.google.gson.JsonObject processors = json("/data/dungeontrain/worldgen/processor_list/lost_city/"
                        + list.substring("dungeontrain:lost_city/".length()) + ".json");
                com.google.gson.JsonArray procs = processors.getAsJsonArray("processors");
                assertEquals("minecraft:block_ignore", procs.get(0).getAsJsonObject().get("processor_type").getAsString(),
                        list + " keeps the mod's structure-block ignore");
                for (int i = 1; i < procs.size(); i++) {
                    String type = procs.get(i).getAsJsonObject().get("processor_type").getAsString();
                    assertTrue(type.equals("dungeontrain:lost_city_swap") || type.equals("dungeontrain:lost_city_truncate")
                            || type.equals("dungeontrain:lost_city_bite"), type);
                }
            }
            assertTrue(lists.contains("dungeontrain:lost_city/shipped"), name + " keeps the as-shipped look");
        }
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static com.google.gson.JsonObject json(String path) throws Exception {
        try (var in = LostCityStructuresTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "missing " + path);
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
