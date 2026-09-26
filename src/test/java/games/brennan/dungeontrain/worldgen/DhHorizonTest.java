package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DhHorizon} on the shipped {@link CycleLayout}: the End voids hide what lies past them from
 * either side, the legacy run shows one band ahead and one behind, and a run later every distance
 * doubles.
 */
final class DhHorizonTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

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

    private static long cap(long worldX) {
        OptionalLong c = DhHorizon.capBlocks(C, worldX, true, true);
        assertTrue(c.isPresent(), "expected a cap at X=" + worldX);
        return c.getAsLong();
    }

    private static long eraCoreStart(int e) {
        return x(LAYOUT.start(LEGACY) + LAYOUT.eraCoreStart(e));
    }

    private static long eraCoreEnd(int e) {
        return eraCoreStart(e) + LAYOUT.eraCoreLen(e);
    }

    @Test
    @DisplayName("approaching the End void: see to the far side of the hold, never the islands past it")
    void approachingVoid() {
        long at = x(END1) - 500;
        assertEquals(x(END1 + F + VH) - at, cap(at));
    }

    @Test
    @DisplayName("inside a void both sides are past it: the nearer edge caps the view")
    void insideVoid() {
        assertEquals(100L, cap(x(END1 + F + 100)));
        assertEquals(100L, cap(x(END1 + F + VH - 100)));
    }

    @Test
    @DisplayName("on the End islands the overworld behind the first void is hidden")
    void islandsLookBack() {
        long at = x(END1 + 2L * F + VH + 100);
        assertEquals(at - x(END1 + F), cap(at));
    }

    @Test
    @DisplayName("the trailing hold stops at the upside-down entry lead, which already shows mirrored terrain")
    void trailingHoldStopsAtUpsideDownLead() {
        long slotEnd = END1 + LAYOUT.length(3);
        long holdFrom = slotEnd - F - VH;                 // second hold start
        long at = x(holdFrom) - 10;                        // End→void fade, just before the hold
        assertEquals(x(slotEnd - C.udEntryLeadLen()) - at, cap(at));
    }

    @Test
    @DisplayName("in a legacy era's core: the neighbouring eras' cores are visible, the ones beyond are not")
    void legacyCore() {
        int e = 2;
        long at = (eraCoreStart(e) + eraCoreEnd(e)) / 2L;
        assertEquals(Math.min(at - eraCoreStart(e - 1), eraCoreEnd(e + 1) - at), cap(at));
    }

    @Test
    @DisplayName("in a legacy crossfade: both bands it joins are visible, nothing past either")
    void legacyCrossfade() {
        int e = 3;
        long at = eraCoreEnd(e) + 10;                      // crossfade e → e+1
        assertEquals(Math.min(at - eraCoreStart(e), eraCoreEnd(e + 1) - at), cap(at));
    }

    @Test
    @DisplayName("the legacy VOID era is a void: the overworld past it stays hidden from the era before")
    void legacyVoidEra() {
        int v = -1;
        for (int e = 0; e < LAYOUT.eras().length; e++) {
            if (LAYOUT.eras()[e].kind() == LegacyBandKind.VOID) v = e;
        }
        assertTrue(v > 0);
        long at = eraCoreEnd(v - 1) - 50;                  // late in the era before the void
        assertTrue(cap(at) <= eraCoreEnd(v) - at);
    }

    @Test
    @DisplayName("a lap later every distance doubles with the run")
    void secondLapDoubles() {
        assertEquals(200L, cap(x(END1 + F + 100, 1)));
    }

    @Test
    @DisplayName("both rules off: no cap at all")
    void rulesOff() {
        assertTrue(DhHorizon.capBlocks(C, x(END1 + F + 100), false, false).isEmpty());
    }

    @Test
    @DisplayName("legacy rule alone ignores the End void")
    void legacyOnlyIgnoresVoid() {
        OptionalLong c = DhHorizon.capBlocks(C, x(END1 + F + 100), false, true);
        assertTrue(c.isEmpty() || c.getAsLong() > 10_000L);
    }

    @Test
    @DisplayName("the window approaching the End void ends at the void's far side and is open behind")
    void windowApproachingVoid() {
        DhHorizon.XWindow w = DhHorizon.window(C, x(END1) - 500, true, true);
        assertEquals(x(END1 + F + VH), w.hi(), 1e-9);
        assertTrue(Double.isInfinite(w.lo()));
        assertTrue(w.contains(x(END1), x(END1 + F + VH)));             // the void itself: drawn
        assertFalse(w.contains(x(END1 + F + VH) - 64, x(END1 + F + VH) + 64)); // straddles the far side: cut
    }

    @Test
    @DisplayName("inside a void the window is just the void")
    void windowInsideVoid() {
        DhHorizon.XWindow w = DhHorizon.window(C, x(END1 + F + 100), true, true);
        assertEquals(x(END1 + F), w.lo(), 1e-9);
        assertEquals(x(END1 + F + VH), w.hi(), 1e-9);
    }

    @Test
    @DisplayName("nothing narrows the view with both rules off: the window is open")
    void windowOpen() {
        assertTrue(DhHorizon.window(C, x(END1 + F + 100), false, false).isOpen());
    }

    @Test
    @DisplayName("buffer: entering the void tightens at once; backing out relaxes only a buffer past the edge")
    void bufferHoldsUntilBackPastTheEdge() {
        double b = 64;
        long edge = x(END1 + F);                           // void start: past it, the overworld behind is cut
        DhHorizon.XWindow w = DhHorizon.XWindow.OPEN;
        w = DhHorizon.buffered(w, C, edge - 200, b, true, true);
        assertTrue(Double.isInfinite(w.lo()), "well before the void the overworld behind is open");
        w = DhHorizon.buffered(w, C, edge + 10, b, true, true);
        assertEquals(edge, w.lo(), 1e-9, "tightens the moment the camera enters the void");
        w = DhHorizon.buffered(w, C, edge - 10, b, true, true);
        assertEquals(edge, w.lo(), 1e-9, "10 blocks back out: still held");
        w = DhHorizon.buffered(w, C, edge - 63, b, true, true);
        assertEquals(edge, w.lo(), 1e-9, "63 blocks back: still inside the buffer");
        w = DhHorizon.buffered(w, C, edge - 65, b, true, true);
        assertTrue(Double.isInfinite(w.lo()), "a buffer past the edge: relaxed");
        w = DhHorizon.buffered(w, C, edge + 1, b, true, true);
        assertEquals(edge, w.lo(), 1e-9, "and tightens again straight away on the way back in");
    }

    @Test
    @DisplayName("buffer: a tighter edge always wins at once, whatever was applied before")
    void bufferNeverDelaysTightening() {
        DhHorizon.XWindow prev = new DhHorizon.XWindow(0, 10_000);
        DhHorizon.XWindow here = new DhHorizon.XWindow(100, 900);
        DhHorizon.XWindow open = DhHorizon.XWindow.OPEN;
        assertEquals(here, DhHorizon.buffer(prev, here, open, open));
    }

    @Test
    @DisplayName("buffer of 0 is the plain window")
    void zeroBufferIsPlain() {
        long at = x(END1 + F) - 10;
        DhHorizon.XWindow tight = new DhHorizon.XWindow(x(END1 + F), x(END1 + F + VH));
        assertEquals(DhHorizon.window(C, at, true, true), DhHorizon.buffered(tight, C, at, 0, true, true));
    }

    @Test
    @DisplayName("far from any void or legacy run the cap is far beyond any DH render distance")
    void farOverworld() {
        assertTrue(cap(START + 100) > 10_000L);
    }
}
