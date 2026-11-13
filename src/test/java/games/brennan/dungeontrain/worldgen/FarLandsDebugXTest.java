package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.client.FarLandsDebugX;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FarLandsDebugX} on the shipped {@link CycleLayout}: the debug screen's X climbs to the Far Lands edge on
 * the approach, holds through the islands, centres each old era after them on 0, reads the negative edge across
 * the Void, and tells the truth again in the overworld.
 */
final class FarLandsDebugXTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final WorldGenCycle C = cycle(LAYOUT);
    private static final int SLOT = LAYOUT.legacySlotOf(LegacyBandKind.FAR_LANDS);

    private static WorldGenCycle cycle(CycleLayout layout) {
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
                CycleLayoutTest.eraDefaults(), layout, 0);
    }

    /** World X of base coordinate {@code u} on run {@code k}. */
    private static int x(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    /** A point inside the legacy run on run {@code k}, for reading its layout. */
    private static WorldGenCycle.LegacyRun run(int k) {
        WorldGenCycle.LegacyRun run = C.legacyRunAt(x(LAYOUT.start(SLOT) + 1, k));
        assertNotNull(run);
        return run;
    }

    private static WorldGenCycle.LegacyRunEra era(WorldGenCycle.LegacyRun run, LegacyBandKind kind) {
        return run.eras().stream().filter(e -> e.kind() == kind).findFirst().orElseThrow();
    }

    private static long wall(WorldGenCycle.LegacyRun run) {
        WorldGenCycle.LegacyRunEra far = era(run, LegacyBandKind.FAR_LANDS);
        long hold = far.coreEndX() - far.coreStartX();
        return far.coreStartX() + Math.round((double) FarLandsShift.APPROACH * hold / FarLandsShift.SCRIPT_LEN);
    }

    private static long shown(long worldX) {
        return worldX + FarLandsDebugX.offsetAt(C, (int) worldX);
    }

    private static long mid(WorldGenCycle.LegacyRunEra era) {
        return Math.floorDiv(era.coreStartX() + era.coreEndX(), 2L);
    }

    private static long switchX(WorldGenCycle.LegacyRunEra era) {
        return era.fadeStartX() + (era.coreStartX() - era.fadeStartX()) / 2L;
    }

    @Test
    @DisplayName("the truth outside the legacy run and before the Beta core")
    void trueBeforeTheApproach() {
        WorldGenCycle.LegacyRun run = run(0);
        assertNull(C.legacyRunAt(x(0, 0)));
        assertEquals(0L, FarLandsDebugX.offsetAt(C, x(0, 0)));
        assertEquals(0L, FarLandsDebugX.offsetAt(C, (int) era(run, LegacyBandKind.AMPLIFIED).coreStartX() + 10));
        assertEquals(0L, FarLandsDebugX.offsetAt(C, (int) era(run, LegacyBandKind.BETA).coreStartX() - 1));
    }

    @Test
    @DisplayName("climbs without ever going backwards, and reads the edge exactly at the entry wall")
    void climbsToTheEdge() {
        WorldGenCycle.LegacyRun run = run(0);
        long from = era(run, LegacyBandKind.BETA).coreStartX();
        long wall = wall(run);
        long prev = Long.MIN_VALUE;
        for (long x = from; x <= wall; x++) {
            long s = shown(x);
            assertTrue(s > prev, "went backwards at " + x);
            prev = s;
        }
        assertEquals(FarLandsShift.EDGE, shown(wall));
        assertTrue(shown(from + (wall - from) / 10) - (from + (wall - from) / 10) < 1000,
                "the first tenth of the approach barely moves the readout");
    }

    @Test
    @DisplayName("keeps counting up from the edge through the Far Lands and the islands")
    void holdsThroughTheIslands() {
        WorldGenCycle.LegacyRun run = run(0);
        long wall = wall(run);
        long end = switchX(era(run, LegacyBandKind.ALPHA));
        for (long x = wall; x < end; x += 97) {
            assertEquals(FarLandsShift.EDGE + (x - wall), shown(x), "x=" + x);
        }
        assertEquals(FarLandsShift.EDGE + (end - 1 - wall), shown(end - 1));
    }

    @Test
    @DisplayName("Alpha, Infdev, Classic and Superflat each read X 0 at their own centre")
    void centredEras() {
        WorldGenCycle.LegacyRun run = run(0);
        for (LegacyBandKind kind : new LegacyBandKind[] {
                LegacyBandKind.ALPHA, LegacyBandKind.INFDEV, LegacyBandKind.CLASSIC, LegacyBandKind.SUPERFLAT}) {
            WorldGenCycle.LegacyRunEra e = era(run, kind);
            assertEquals(0L, shown(mid(e)), kind + " centre");
            assertEquals(switchX(e) - mid(e), shown(switchX(e)), kind + " start");
        }
    }

    @Test
    @DisplayName("the Void counts up at normal speed to the negative edge on the last block before the overworld")
    void voidThenTruth() {
        WorldGenCycle.LegacyRun run = run(0);
        WorldGenCycle.LegacyRunEra v = era(run, LegacyBandKind.VOID);
        long from = switchX(v);
        long exit = run.endX();
        for (long x = from; x < exit; x++) {
            assertEquals(-FarLandsShift.EDGE - (exit - 1 - x), shown(x), "x=" + x);
        }
        assertEquals(0L, FarLandsDebugX.offsetAt(C, (int) exit));
        assertEquals(0L, FarLandsDebugX.offsetAt(C, (int) run.endX() + 5));
    }

    @Test
    @DisplayName("run 1 lays the same readout over its doubled run")
    void secondRun() {
        WorldGenCycle.LegacyRun run = run(1);
        assertEquals(FarLandsShift.EDGE, shown(wall(run)));
        assertEquals(0L, shown(mid(era(run, LegacyBandKind.CLASSIC))));
        assertEquals(-FarLandsShift.EDGE, shown(run.endX() - 1));
    }

    @Test
    @DisplayName("the truth everywhere when the Far Lands are not in the order, and behind spawn")
    void offWithoutFarLands() {
        String order = CycleLayout.DEFAULT_ORDER.replace(":far_lands=4320", "");
        CycleLayout layout = CycleLayout.parse(order, CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, w -> {});
        WorldGenCycle c = cycle(layout);
        for (long u = 0; u < layout.period(); u += 997) {
            assertEquals(0L, FarLandsDebugX.offsetAt(c, x(u, 0)), "u=" + u);
        }
        assertEquals(0L, FarLandsDebugX.offsetAt(C, (int) (START - 10L * P + LAYOUT.start(SLOT))));
    }
}
