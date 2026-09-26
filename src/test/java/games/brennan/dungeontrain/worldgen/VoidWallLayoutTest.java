package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoidWallLayout} on the shipped {@link CycleLayout}: a wall at a void's far side while the
 * camera is before it, handing over to one at its near side as the camera crosses the fade (the one
 * fading out as the other fades in), and legacy eras showing one era ahead — with every distance
 * doubling a run later.
 */
final class VoidWallLayoutTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static final double FADE = 0.2;
    private static final int F = 120;
    private static final int VH = 500;
    /** The first End slot (lap 1: the upside-down band follows it). */
    private static final long END1 = LAYOUT.start(3);
    private static final int LEGACY = LAYOUT.firstIndexOf(CycleLayout.Type.LEGACY_RUN);

    private static long x(long u, int k) {
        return START + CycleLayout.runStart(k, P) + (u << k);
    }

    private static long x(long u) {
        return x(u, 0);
    }

    private static VoidWallLayout.Result voidsOnly(double at) {
        return VoidWallLayout.wallAt(C, at, FADE, 0, true, false);
    }

    private static VoidWallLayout.Result both(double at) {
        return VoidWallLayout.wallAt(C, at, FADE, 0, true, true);
    }

    private static long eraCoreStart(int e) {
        return x(LAYOUT.start(LEGACY) + LAYOUT.eraCoreStart(e));
    }

    private static long eraCoreEnd(int e) {
        return eraCoreStart(e) + LAYOUT.eraCoreLen(e);
    }

    @Test
    @DisplayName("before the End void: the wall stands at the void's far side, at full strength")
    void beforeVoid() {
        VoidWallLayout.Result r = voidsOnly(x(END1) - 500);
        assertEquals(x(END1 + F + VH), r.cullX(), 1e-9);
        assertFalse(r.hasVeil());
    }

    @Test
    @DisplayName("entering the void: the wall stops culling and fades as a veil over the first 20%")
    void fadesOverFirstFifth() {
        long from = x(END1 + F);
        long wall = x(END1 + F + VH);
        double fadeLen = FADE * VH;

        VoidWallLayout.Result early = voidsOnly(from + 1);
        assertFalse(early.hasCull() && early.cullX() <= wall, "a fading wall is never culled");
        assertTrue(early.hasVeil());
        assertEquals(wall, early.veilX(), 1e-9);
        assertTrue(early.veilStrength() > 0.95);

        VoidWallLayout.Result mid = voidsOnly(from + fadeLen / 2);
        assertEquals(0.5, mid.veilStrength(), 1e-9);

        VoidWallLayout.Result done = voidsOnly(from + fadeLen + 1);
        assertFalse(done.hasVeil());
        assertTrue(done.cullX() > wall, "the void's own wall is gone");
    }

    @Test
    @DisplayName("with the End sky lagging the terrain, the wall holds until the void sky has fully risen")
    void waitsForVoidToFadeIn() {
        int sky = 120;                                        // sky full at slot + sky + F = hold start + 120
        long from = x(END1 + F);
        long skyFull = from + sky;
        long wall = x(END1 + F + VH);
        double fadeLen = FADE * VH;

        VoidWallLayout.Result early = VoidWallLayout.wallAt(C, from + 60, FADE, sky, true, false);
        assertEquals(wall, early.cullX(), 1e-9, "still culling while the void sky rises");
        assertFalse(early.hasVeil());

        VoidWallLayout.Result mid = VoidWallLayout.wallAt(C, skyFull + fadeLen / 2, FADE, sky, true, false);
        assertEquals(wall, mid.veilX(), 1e-9);
        assertEquals(0.5, mid.veilStrength(), 1e-9);

        VoidWallLayout.Result done = VoidWallLayout.wallAt(C, skyFull + fadeLen + 1, FADE, sky, true, false);
        assertFalse(done.hasVeil());
        assertTrue(done.cullX() > wall);
    }

    @Test
    @DisplayName("leaving the End: the wall holds until the overworld sky is back, then fades past the hold")
    void waitsForOverworldSkyOnTheWayOut() {
        int sky = 120;
        long end2 = LAYOUT.start(8);                           // end:better — spheres follows, no upside-down lead
        assertEquals(CycleLayout.Type.END, LAYOUT.slot(8).type());
        long eh = LAYOUT.slot(8).core();
        long band = Disintegration.bandLength(F, VH, (int) eh);
        long skyBack = x(end2 + band - sky);                  // overworld sky fully back
        long fadeLen = (long) Math.ceil(FADE * VH);
        long wall = skyBack + fadeLen;

        VoidWallLayout.Result inHold = VoidWallLayout.wallAt(C, skyBack - 60, FADE, sky, true, false);
        assertEquals(wall, inHold.cullX(), 1e-9, "still standing while the End sky gives way");
        assertFalse(inHold.hasVeil());

        VoidWallLayout.Result mid = VoidWallLayout.wallAt(C, skyBack + fadeLen / 2.0, FADE, sky, true, false);
        assertEquals(wall, mid.veilX(), 1e-9);
        assertEquals(0.5, mid.veilStrength(), 1e-9);

        VoidWallLayout.Result past = VoidWallLayout.wallAt(C, wall + 1, FADE, sky, true, false);
        assertFalse(past.hasVeil());
        assertTrue(past.cullX() > wall);
    }

    @Test
    @DisplayName("the veil only ever thins as the camera rides on")
    void veilIsMonotonic() {
        long from = x(END1 + F);
        double prev = 1.0;
        for (int d = 1; d < FADE * VH; d += 5) {
            double s = voidsOnly(from + d).veilStrength();
            assertTrue(s <= prev, "strength rose at +" + d);
            prev = s;
        }
    }

    @Test
    @DisplayName("deep in the void: clear ahead, and the overworld behind is walled off at the void's near edge")
    void insideVoid() {
        VoidWallLayout.Result r = voidsOnly(x(END1 + F + VH - 50));
        assertFalse(r.hasVeil());
        assertTrue(r.cullX() > x(END1 + F + VH));
        assertEquals(x(END1 + F), r.backCullX(), 1e-9);
        assertFalse(r.hasBackVeil());
    }

    @Test
    @DisplayName("on the End islands past the first void: the view back stops at that void's near edge")
    void pastVoidWallsBehind() {
        long at = x(END1 + 2L * F + VH + 100);
        VoidWallLayout.Result r = voidsOnly(at);
        assertTrue(r.cullX() > at, "the next wall is ahead");
        assertEquals(x(END1 + F), r.backCullX(), 1e-9);
    }

    @Test
    @DisplayName("crossing the fade, the wall ahead fades out exactly as the wall behind fades in")
    void handOver() {
        long from = x(END1 + F);
        for (int d = 1; d < FADE * VH; d += 7) {
            VoidWallLayout.Result r = voidsOnly(from + d);
            assertTrue(r.hasVeil() && r.hasBackVeil(), "both fading at +" + d);
            assertEquals(from, r.backVeilX(), 1e-9);
            assertEquals(1.0, r.veilStrength() + r.backStrength(), 1e-9);
        }
        VoidWallLayout.Result before = voidsOnly(from - 1);
        assertFalse(before.hasBackVeil() || before.backCullX() >= from, "no wall behind before the void");
    }

    @Test
    @DisplayName("before the upside-down band the hand-over starts 65% of the way through the hold")
    void upsideDownHandsOverAt65Percent() {
        long slotEnd = END1 + LAYOUT.length(3);
        long holdFrom = slotEnd - F - VH;
        long holdEnd = slotEnd - C.udEntryLeadLen();
        long len = holdEnd - holdFrom;
        long fadeAt = holdFrom + Math.round(0.65 * len);
        double fadeLen = Math.ceil(FADE * len);
        int sky = 120;

        VoidWallLayout.Result early = VoidWallLayout.wallAt(C, x(fadeAt) - 5, FADE, sky, true, false);
        assertEquals(x(holdEnd), early.cullX(), 1e-9, "still standing at 64%");
        VoidWallLayout.Result mid = VoidWallLayout.wallAt(C, x(fadeAt) + fadeLen / 2, FADE, sky, true, false);
        assertEquals(0.5, mid.veilStrength(), 1e-9);
        assertEquals(0.5, mid.backStrength(), 1e-9);
    }

    @Test
    @DisplayName("the trailing hold's wall stops at the upside-down entry lead")
    void trailingHoldStopsAtUpsideDownLead() {
        long slotEnd = END1 + LAYOUT.length(3);
        long holdFrom = slotEnd - F - VH;
        long at = x(holdFrom) - 10;
        assertEquals(x(slotEnd - C.udEntryLeadLen()), voidsOnly(at).cullX(), 1e-9);
    }

    @Test
    @DisplayName("a lap later the wall and its fade double with the run")
    void secondLapDoubles() {
        long from = x(END1 + F, 1);
        long wall = x(END1 + F + VH, 1);
        assertEquals(wall, voidsOnly(from - 10).cullX(), 1e-9);
        assertEquals(0.5, voidsOnly(from + FADE * VH).veilStrength(), 1e-9); // half of a doubled fade
    }

    @Test
    @DisplayName("in a legacy era's core: the next era is visible, the one after it is walled")
    void legacyCore() {
        int e = 2;
        long at = (eraCoreStart(e) + eraCoreEnd(e)) / 2L;
        assertEquals(eraCoreEnd(e + 1), both(at).cullX(), 1e-9);
    }

    @Test
    @DisplayName("in a legacy crossfade: the band ahead is visible, nothing past it")
    void legacyCrossfade() {
        int e = 3;
        assertEquals(eraCoreEnd(e + 1), both(eraCoreEnd(e) + 10).cullX(), 1e-9);
    }

    @Test
    @DisplayName("entering an era fades the wall at its far side, while the era beyond stays culled")
    void legacyFadeKeepsNextWall() {
        int e = 2;
        long at = eraCoreStart(e) + 5;
        VoidWallLayout.Result r = both(at);
        assertTrue(r.hasVeil());
        assertEquals(eraCoreEnd(e), r.veilX(), 1e-9);
        assertEquals(eraCoreEnd(e + 1), r.cullX(), 1e-9);
        assertEquals(eraCoreStart(e), r.backVeilX(), 1e-9, "the era behind fades out as this one opens");
    }

    @Test
    @DisplayName("the legacy VOID era is a void even with the era rule off")
    void legacyVoidEra() {
        int v = -1;
        for (int e = 0; e < LAYOUT.eras().length; e++) {
            if (LAYOUT.eras()[e].kind() == LegacyBandKind.VOID) v = e;
        }
        assertTrue(v > 0);
        assertEquals(eraCoreEnd(v), voidsOnly(eraCoreEnd(v - 1) - 50).cullX(), 1e-9);
    }

    @Test
    @DisplayName("both rules off: no wall at all")
    void rulesOff() {
        assertTrue(VoidWallLayout.wallAt(C, x(END1) - 500, FADE, 0, false, false).isNone());
    }

    @Test
    @DisplayName("a fade of 0 drops the wall the moment the camera enters")
    void zeroFade() {
        VoidWallLayout.Result r = VoidWallLayout.wallAt(C, x(END1 + F) + 1, 0.0, 0, true, false);
        assertFalse(r.hasVeil());
        assertTrue(r.cullX() > x(END1 + F + VH));
    }

    @Test
    @DisplayName("strength: 1 before the edge, smoothstep down to 0 over the fade")
    void strengthCurve() {
        assertEquals(1.0, VoidWallLayout.strength(-5, 100), 1e-9);
        assertEquals(1.0, VoidWallLayout.strength(0, 100), 1e-9);
        assertEquals(0.5, VoidWallLayout.strength(50, 100), 1e-9);
        assertEquals(0.0, VoidWallLayout.strength(100, 100), 1e-9);
        assertEquals(0.0, VoidWallLayout.strength(5, 0), 1e-9);
    }
}
