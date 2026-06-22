package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for the Nether transition band ({@link NetherTransition}) — the
 * Overworld → mega-mountain → Nether → Overworld cycle that <b>repeats forever</b>,
 * anchored to a block distance from spawn. No NeoForge bootstrap.
 *
 * <p>Test geometry: {@code startX=1000}, fade {@code F=100}, mountainHold {@code Mh=60},
 * coreFade {@code Cf=50}, coreHold {@code Ch=200}, overworldHold {@code OW=80}. Each
 * cycle starts with the overworld phase {@code [0,80)}, then the active band
 * {@code 2F+2Mh+2Cf+Ch=620}; full period {@code 80+620=700}. World-X of band offset
 * {@code dd} is {@code 1080 + dd}.</p>
 */
final class NetherTransitionTest {

    private static final long START_X = 1000L;
    private static final int F = 100;   // fade (mountain rise/fall)
    private static final int MH = 60;   // mountain hold (plateau either side of the core)
    private static final int CF = 50;   // core fade (netherrack crossfade)
    private static final int CH = 200;  // core hold (real Nether)
    private static final int OW = 80;   // overworld hold
    private static final int PERIOD = 700;
    private static final double EPS = 1e-9;

    // ---- band geometry -------------------------------------------------------

    @Test
    @DisplayName("bandLength = 2·fade + 2·mountainHold + 2·coreFade + coreHold; cyclePeriod adds owHold")
    void bandGeometry() {
        assertEquals(620L, NetherTransition.bandLength(F, MH, CF, CH));
        assertEquals(700L, NetherTransition.cyclePeriod(F, MH, CF, CH, OW));
    }

    // ---- heightRamp (mountain envelope) -------------------------------------

    @Test
    @DisplayName("heightRamp is 0 before the anchor and across the overworld phase that starts each cycle")
    void height_overworldPhase() {
        assertEquals(0.0, NetherTransition.heightRamp(999, START_X, F, MH, CF, CH, OW), EPS);   // before anchor
        assertEquals(0.0, NetherTransition.heightRamp(-5000, START_X, F, MH, CF, CH, OW), EPS);
        assertEquals(0.0, NetherTransition.heightRamp(1000, START_X, F, MH, CF, CH, OW), EPS);  // d=0 overworld
        assertEquals(0.0, NetherTransition.heightRamp(1079, START_X, F, MH, CF, CH, OW), EPS);  // d=79 overworld
    }

    @Test
    @DisplayName("after the overworld phase: 0→1 rise, flat 1 across the interior, 1→0 fall")
    void height_band() {
        assertEquals(0.0, NetherTransition.heightRamp(1080, START_X, F, MH, CF, CH, OW), EPS); // dd=0 rise start
        assertEquals(0.5, NetherTransition.heightRamp(1130, START_X, F, MH, CF, CH, OW), EPS); // dd=50
        assertEquals(1.0, NetherTransition.heightRamp(1180, START_X, F, MH, CF, CH, OW), EPS); // dd=100 plateau start
        assertEquals(1.0, NetherTransition.heightRamp(1400, START_X, F, MH, CF, CH, OW), EPS); // dd=320 nether core
        assertEquals(1.0, NetherTransition.heightRamp(1600, START_X, F, MH, CF, CH, OW), EPS); // dd=520 fall start
        assertEquals(0.5, NetherTransition.heightRamp(1650, START_X, F, MH, CF, CH, OW), EPS); // dd=570
    }

    // ---- netherRamp (netherrack → real Nether core) -------------------------

    @Test
    @DisplayName("netherRamp is 0 through the mountain plateau, 0→1 crossfade, holds 1 across the core, 1→0 back")
    void nether_trapezoid() {
        assertEquals(0.0, NetherTransition.netherRamp(1080, START_X, F, MH, CF, CH, OW), EPS); // dd=0 rising mountain
        assertEquals(0.0, NetherTransition.netherRamp(1180, START_X, F, MH, CF, CH, OW), EPS); // dd=100 plateau, no nether
        assertEquals(0.0, NetherTransition.netherRamp(1240, START_X, F, MH, CF, CH, OW), EPS); // dd=160 crossfade start
        assertEquals(0.5, NetherTransition.netherRamp(1265, START_X, F, MH, CF, CH, OW), EPS); // dd=185 crossfade
        assertEquals(1.0, NetherTransition.netherRamp(1290, START_X, F, MH, CF, CH, OW), EPS); // dd=210 core start
        assertEquals(1.0, NetherTransition.netherRamp(1400, START_X, F, MH, CF, CH, OW), EPS); // dd=320 core
        assertEquals(1.0, NetherTransition.netherRamp(1490, START_X, F, MH, CF, CH, OW), EPS); // dd=410 core end
        assertEquals(0.5, NetherTransition.netherRamp(1515, START_X, F, MH, CF, CH, OW), EPS); // dd=435 crossfade out
        assertEquals(0.0, NetherTransition.netherRamp(1540, START_X, F, MH, CF, CH, OW), EPS); // dd=460 back to mountain
    }

    @Test
    @DisplayName("the 5 visual stages map to the expected (H, N) pairs")
    void stage_mapping() {
        // stage 3 mega-mountain (tunnel): H=1, N=0 on the plateau
        assertEquals(1.0, NetherTransition.heightRamp(1180, START_X, F, MH, CF, CH, OW), EPS);
        assertEquals(0.0, NetherTransition.netherRamp(1180, START_X, F, MH, CF, CH, OW), EPS);
        // stage 4 netherrack crossfade: H=1, 0<N<1
        assertEquals(1.0, NetherTransition.heightRamp(1265, START_X, F, MH, CF, CH, OW), EPS);
        assertTrue(NetherTransition.netherRamp(1265, START_X, F, MH, CF, CH, OW) > 0.0
                && NetherTransition.netherRamp(1265, START_X, F, MH, CF, CH, OW) < 1.0);
        // stage 5 real Nether: H=1, N=1
        assertEquals(1.0, NetherTransition.heightRamp(1400, START_X, F, MH, CF, CH, OW), EPS);
        assertEquals(1.0, NetherTransition.netherRamp(1400, START_X, F, MH, CF, CH, OW), EPS);
    }

    // ---- repeats forever ----------------------------------------------------

    @Test
    @DisplayName("both ramps are periodic with period owHold + 2·fade + 2·mountainHold + 2·coreFade + coreHold")
    void cycle_repeatsForever() {
        assertEquals(PERIOD, NetherTransition.cyclePeriod(F, MH, CF, CH, OW));
        for (int d : new int[] {0, 50, 100, 185, 320, 435, 570, 690}) {
            assertEquals(NetherTransition.heightRamp((int) START_X + d, START_X, F, MH, CF, CH, OW),
                    NetherTransition.heightRamp((int) START_X + d + PERIOD, START_X, F, MH, CF, CH, OW), EPS,
                    "heightRamp not periodic at offset " + d);
            assertEquals(NetherTransition.netherRamp((int) START_X + d, START_X, F, MH, CF, CH, OW),
                    NetherTransition.netherRamp((int) START_X + d + PERIOD, START_X, F, MH, CF, CH, OW), EPS,
                    "netherRamp not periodic at offset " + d);
        }
        assertEquals(0.0, NetherTransition.heightRamp(1000 + PERIOD, START_X, F, MH, CF, CH, OW), EPS); // overworld again
        assertEquals(1.0, NetherTransition.netherRamp(1400 + PERIOD, START_X, F, MH, CF, CH, OW), EPS); // core again
    }

    // ---- degenerate spans ---------------------------------------------------

    @Test
    @DisplayName("ramps survive fade=0 and coreFade=0 (hard edges) without dividing by zero")
    void degenerate_fades() {
        // fade=0: mountain jumps straight to full height at the band start
        assertEquals(0.0, NetherTransition.heightRamp(1000, START_X, 0, MH, CF, CH, OW), EPS);       // overworld phase
        assertEquals(1.0, NetherTransition.heightRamp(1000 + OW, START_X, 0, MH, CF, CH, OW), EPS);  // band start, H=1
        // coreFade=0: nether intensity steps 0→1 at the core edge (core starts at dd = F+MH)
        assertEquals(1.0, NetherTransition.netherRamp(1000 + OW + F + MH, START_X, F, MH, 0, CH, OW), EPS); // core start, N=1
        assertEquals(0.0, NetherTransition.netherRamp(1000 + OW, START_X, F, MH, 0, CH, OW), EPS);         // rising mountain, N=0
    }

    // ---- mountain top height -------------------------------------------------

    @Test
    @DisplayName("mountainTopY scales bedY by the height ramp and clamps to the world top")
    void mountainTop_scaleAndClamp() {
        int bedY = 76, maxHeight = 250, worldTop = 319;
        assertEquals(76, NetherTransition.mountainTopY(0.0, bedY, maxHeight, worldTop));   // no mountain
        assertEquals(201, NetherTransition.mountainTopY(0.5, bedY, maxHeight, worldTop));  // half height
        assertEquals(319, NetherTransition.mountainTopY(1.0, bedY, maxHeight, worldTop));  // 76+250=326 → clamped
        assertEquals(319, NetherTransition.mountainTopY(2.0, bedY, maxHeight, worldTop));  // ramp clamped to 1 first
    }
}
