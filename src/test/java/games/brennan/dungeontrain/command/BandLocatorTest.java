package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.CycleLayout.Style;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BandLocator} against pure {@link WorldGenCycle}s: every band of the shipped layout is reached
 * from the anchor and from deep inside later (doubled) runs, and a reshuffled layout is still reached —
 * nothing in the search is a hard-coded band distance.
 */
final class BandLocatorTest {

    @org.junit.jupiter.api.BeforeEach
    void publishThemes() {
        games.brennan.dungeontrain.worldgen.LapThemes.publish(games.brennan.dungeontrain.worldgen.TestLapPlans.bopThenBetter());
    }

    @org.junit.jupiter.api.AfterEach
    void clearThemes() {
        games.brennan.dungeontrain.worldgen.LapThemes.clear();
    }

    private static final long START = 10_000L;
    private static final CycleLayout.Fades FADES = new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);

    private static LegacySpan[] eraDefaults() {
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) out.add(new LegacySpan(k, 3000, 480, 6000));
        return out.toArray(new LegacySpan[0]);
    }

    private static WorldGenCycle cycle(String order) {
        List<String> warnings = new ArrayList<>();
        CycleLayout layout = CycleLayout.parse(order, FADES, eraDefaults(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
                eraDefaults(), layout, 0);
    }

    /** One pure column test per band the shipped layout contains — the same classifiers /dtp's targets read. */
    private static Map<String, IntPredicate> bands(WorldGenCycle c) {
        Map<String, IntPredicate> m = new LinkedHashMap<>();
        m.put("nether", c::isNetherCore);
        m.put("better_nether", x -> c.isNetherCore(x) && c.netherStyleAt(x) == Style.BETTER);
        m.put("end", x -> c.endStyleAt(x) != null);
        m.put("better_end", x -> c.endStyleAt(x) == Style.BETTER);
        m.put("wwoo", x -> c.overworldStyleAt(x) == Style.WWOO);
        m.put("bop", x -> c.overworldStyleAt(x) == Style.BOP);
        if (c.themeGroupsPerRun() > 0) {
            m.put("bop_nether", x -> c.isNetherCore(x) && c.netherStyleAt(x) == Style.BOP);
            m.put("bop_end", x -> c.endStyleAt(x) == Style.BOP);
        }
        m.put("upside_down", c::isInUpsideDownBand);
        m.put("reassembly", c::isInUpsideDownExitFade);
        m.put("spheres", c::isInSpheresBand);
        m.put("chuncks", c::isInChuncksBand);
        m.put("stacks", c::isInStacksBand);
        for (LegacyBandKind kind : LegacyBandKind.values()) {
            if (c.legacyLen(kind) <= 0L) continue;               // era not in this order (e.g. Large Biomes: built, not shipped)
            m.put(kind.token(), x -> c.isInLegacyBand(kind, x));
        }
        return m;
    }

    private static void assertEntryAhead(WorldGenCycle c, IntPredicate band, int fromX, String name) {
        OptionalInt entry = BandLocator.nextBandStartX(c, band, fromX);
        assertTrue(entry.isPresent(), name + " not found from " + fromX);
        int x = entry.getAsInt();
        assertTrue(x > fromX, name + " entry " + x + " not ahead of " + fromX);
        assertTrue(band.test(x), name + " entry " + x + " not in band");
        assertFalse(band.test(x - 1), name + " entry " + x + " is not the first column");
    }

    private static void assertAllFound(WorldGenCycle c, int fromX) {
        for (Map.Entry<String, IntPredicate> band : bands(c).entrySet()) {
            assertEntryAhead(c, band.getValue(), fromX, band.getKey());
        }
    }

    @Test
    @DisplayName("every band of the shipped layout is found from before the anchor and from the anchor")
    void shippedRunZero() {
        WorldGenCycle c = cycle(CycleLayout.DEFAULT_ORDER);
        assertAllFound(c, 0);
        assertAllFound(c, (int) START);
    }

    @Test
    @DisplayName("every band is still found from deep inside doubled runs 1 and 2")
    void laterRuns() {
        WorldGenCycle c = cycle(CycleLayout.DEFAULT_ORDER);
        long p = c.period();
        for (int k = 1; k <= 2; k++) {
            long runStart = START + CycleLayout.runStart(k, p);
            // Near the end of the run: every band's next occurrence is in the next, twice-as-long run.
            assertAllFound(c, (int) (runStart + ((p - 100L) << k)));
            assertAllFound(c, (int) (runStart + ((p / 2L) << k)));
        }
    }

    @Test
    @DisplayName("a reshuffled, resized layout is still found — the search follows the config")
    void customOrder() {
        WorldGenCycle c = cycle("stacks:3000, ow:1000, legacy:classic=2000:beta=2500, nether:better:2000, "
                + "ow:wwoo:1500, end:3000, ow:bop:1200, spheres:4000");
        long p = c.period();
        Map<String, IntPredicate> all = bands(c);
        for (String name : List.of("stacks", "classic", "beta", "better_nether", "nether", "wwoo", "end", "bop", "spheres")) {
            assertEntryAhead(c, all.get(name), 0, name);
            assertEntryAhead(c, all.get(name), (int) (START + CycleLayout.runStart(1, p) + ((p - 50L) << 1)), name);
        }
        assertFalse(all.containsKey("alpha"));
        assertFalse(BandLocator.nextBandStartX(c, x -> c.isInLegacyBand(LegacyBandKind.ALPHA, x), 0).isPresent(),
                "absent band reports not found");
    }

    @Test
    @DisplayName("/dtp <band> <distance> <lap>: every band is found inside the lap asked for, laps 0-3")
    void bandInLap() {
        WorldGenCycle c = cycle(CycleLayout.DEFAULT_ORDER);
        for (int lap = 0; lap <= 3; lap++) {
            for (Map.Entry<String, IntPredicate> band : bands(c).entrySet()) {
                OptionalInt entry = BandLocator.bandStartXInLap(c, band.getValue(), lap);
                assertTrue(entry.isPresent(), band.getKey() + " not found in lap " + lap);
                int x = entry.getAsInt();
                assertEquals(lap, c.cycleIndex(x), band.getKey() + " entry " + x + " is not in lap " + lap);
                assertTrue(band.getValue().test(x), band.getKey() + " entry " + x + " not in band");
                assertFalse(band.getValue().test(x - 1), band.getKey() + " entry " + x + " is not the first column");
            }
        }
    }

    @Test
    @DisplayName("lapStartX inverts cycleIndex — layout runs and a phase-shifted classic cycle")
    void lapStartInvertsCycleIndex() {
        WorldGenCycle layout = cycle(CycleLayout.DEFAULT_ORDER);
        WorldGenCycle classic = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 120);
        for (WorldGenCycle c : List.of(layout, classic)) {
            assertEquals(c.startX(), c.lapStartX(0));
            for (int lap = 1; lap <= 4; lap++) {
                int x = (int) c.lapStartX(lap);
                assertEquals(lap, c.cycleIndex(x), "lap " + lap + " start " + x);
                assertEquals(lap - 1, c.cycleIndex(x - 1), "column before lap " + lap + " start");
            }
            assertEquals(-1L, c.lapStartX(-1));
        }
    }

    @Test
    @DisplayName("a band a lap doesn't carry reports not found — BetterNether on the classic cycle's even laps")
    void bandAbsentFromLap() {
        WorldGenCycle c = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 120);
        IntPredicate nether = c::isNetherCore;
        IntPredicate betterNether = x -> c.isNetherCore(x) && c.isBetterNetherAt(x);
        for (int lap = 0; lap <= 3; lap++) {
            assertTrue(BandLocator.bandStartXInLap(c, nether, lap).isPresent(), "nether lap " + lap);
            assertEquals(c.isBetterNetherPass(lap), BandLocator.bandStartXInLap(c, betterNether, lap).isPresent(),
                    "better_nether lap " + lap);
        }
    }

    @Test
    @DisplayName("/dtp registers every phase token and alias once, plus the styled targets")
    void targetTokens() {
        Set<String> tokens = new HashSet<>();
        for (DtpTarget t : DtpTarget.all()) assertTrue(tokens.add(t.token()), "duplicate /dtp token " + t.token());
        for (TrainPhase p : TrainPhase.values()) assertTrue(tokens.contains(p.token()), p.token());
        for (String alias : TrainPhase.aliases().keySet()) assertTrue(tokens.contains(alias), alias);
        for (String styled : List.of("better_nether", "better_end", "bop_nether", "bop_end", "wwoo", "bop", "reassembly")) {
            assertTrue(tokens.contains(styled), styled);
        }
        assertEquals(TrainPhase.CAVES_OF_CHAOS, TrainPhase.byToken("chaos"));
    }
}
