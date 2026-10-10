package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.NetherPortalJump.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NetherPortalJump} over the shipped cycle with a cycle-derived band labeller: segments are found
 * edge-exact, the player's fraction through the segment they left carries over to the destination Nether
 * band, and the pass choice is next-ahead from outside, previous from inside, next from the first band.
 */
final class NetherPortalJumpTest {

    private static final WorldGenCycle C = ShippedCycles.CYCLE;

    /** Nether / End / Overworld by the cycle's own band predicates — the same split the F3+4 label makes. */
    private static final IntFunction<String> LABEL = x ->
        NetherBand.isInNetherBand(C, x) ? "Nether" : (C.endMiddleRamp(x) > 0.0 ? "End" : "Overworld");

    private static long entry(int pass) {
        OptionalLong e = NetherPortalJump.netherrackEntry(C, pass);
        assertTrue(e.isPresent(), "pass " + pass + " has a netherrack entry");
        return e.getAsLong();
    }

    private static Segment netherSegment(int pass) {
        long e = entry(pass);
        return NetherPortalJump.segmentAround(LABEL, (int) e, e, e + 200_000L);
    }

    private static long target(long fromX) {
        OptionalLong t = NetherPortalJump.targetX(C, LABEL, (int) fromX);
        assertTrue(t.isPresent(), "a target from x=" + fromX);
        return t.getAsLong();
    }

    private static double fraction(Segment s, long x) {
        return (double) (x - s.start()) / (double) s.length();
    }

    @Test
    @DisplayName("segmentAround finds both edges exactly on a synthetic label strip")
    void segmentEdgesAreExact() {
        IntFunction<String> strip = x -> x < 100 ? "A" : (x < 1237 ? "B" : "C");
        assertEquals(new Segment(100, 1237), NetherPortalJump.segmentAround(strip, 500, -10_000, 10_000));
        assertEquals(new Segment(100, 1237), NetherPortalJump.segmentAround(strip, 100, -10_000, 10_000));
        assertEquals(new Segment(100, 1237), NetherPortalJump.segmentAround(strip, 1236, -10_000, 10_000));
        // Clamped by the search window when the run outlives it.
        assertEquals(new Segment(1237, 2001), NetherPortalJump.segmentAround(strip, 1500, 0, 2000));
    }

    @Test
    @DisplayName("mapFraction keeps the proportion across segments of different lengths and stays inside the target")
    void fractionMapping() {
        Segment from = new Segment(1000, 1400);   // 400 long
        Segment to = new Segment(50_000, 51_000); // 1000 long
        assertEquals(50_000, NetherPortalJump.mapFraction(from, 1000, to));
        assertEquals(50_500, NetherPortalJump.mapFraction(from, 1200, to));
        assertEquals(50_997, NetherPortalJump.mapFraction(from, 1399, to));
        assertEquals(50_999, NetherPortalJump.mapFraction(from, 1400, to), "past the end clamps to the last column");
        assertEquals(50_000, NetherPortalJump.mapFraction(new Segment(5, 5), 5, to), "an empty 'from' maps to the start");
    }

    @Test
    @DisplayName("the Nether segment starts at the netherrack entry")
    void netherSegmentStartsAtEntry() {
        Segment s = netherSegment(0);
        assertEquals(entry(0), s.start());
        long[] r0 = C.netherPassRange(0);
        assertTrue(s.end() <= r0[1] + 1, "Nether segment ends within pass 0's slot");
        assertTrue(s.length() > 1000, "a real band, not a sliver");
    }

    @Test
    @DisplayName("outside → the next Nether band, at the same fraction through the segment left")
    void outsideGoesForwardProportionally() {
        // The first stretch runs from the ride's start (x=0, before the anchor) to the first netherrack.
        Segment ow = NetherPortalJump.segmentAround(LABEL, (int) ShippedCycles.START + 500, 0L, ShippedCycles.START + 50_000);
        assertEquals(0L, ow.start());
        assertEquals(entry(0), ow.end());
        long fromX = ow.start() + ow.length() / 4;          // 25 % through the first overworld stretch
        long t = target(fromX);
        Segment to = netherSegment(0);
        assertTrue(to.contains(t), "lands inside Nether pass 0 (" + to + "), got " + t);
        assertEquals(fraction(ow, fromX), fraction(to, t), 0.002);
    }

    @Test
    @DisplayName("in a band's mountain approach (no netherrack yet) → that band, not the one after")
    void approachCountsAsOutside() {
        long approach = C.netherPassRange(1)[0] + 1;
        assertFalse(NetherBand.isInNetherBand(C, (int) approach));
        assertEquals(OptionalInt.of(1), NetherPortalJump.destinationPass(C, (int) approach));
        assertTrue(netherSegment(1).contains(target(approach)));
    }

    @Test
    @DisplayName("inside pass 1 at 40 % → pass 0 at 40 %")
    void insideGoesBackProportionally() {
        Segment s1 = netherSegment(1);
        long fromX = s1.start() + (long) (s1.length() * 0.4);
        assertTrue(NetherBand.isInNetherBand(C, (int) fromX));
        long t = target(fromX);
        Segment s0 = netherSegment(0);
        assertTrue(s0.contains(t), "lands inside pass 0 (" + s0 + "), got " + t);
        assertEquals(0.4, fraction(s0, t), 0.002);
    }

    @Test
    @DisplayName("inside the first band there is no previous one → the next band ahead")
    void firstBandGoesForward() {
        long inside0 = entry(0) + 200;
        assertTrue(NetherBand.isInNetherBand(C, (int) inside0));
        assertEquals(OptionalInt.of(1), NetherPortalJump.destinationPass(C, (int) inside0));
        assertTrue(netherSegment(1).contains(target(inside0)));
    }
}
