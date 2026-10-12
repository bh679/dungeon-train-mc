package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.NetherPortalJump.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NetherPortalJump} over the shipped cycle with a cycle-derived band labeller: cores are found
 * edge-exact, the player's fraction through the segment they left carries over to the destination core,
 * and the destination follows the rules — first overworld → first Nether core, a later non-Nether band →
 * the previous Nether core, inside a Nether → the core of the band before it.
 */
final class NetherPortalJumpTest {

    private static final WorldGenCycle C = ShippedCycles.CYCLE;
    private static final long BOUND = 400_000L;

    /** Nether / End / Overworld by the cycle's own band predicates — the same split the F3+4 label makes. */
    private static final IntFunction<String> LABEL = x ->
        NetherBand.isInNetherBand(C, x) ? "Nether" : (C.endMiddleRamp(x) > 0.0 ? "End" : "Overworld");

    private static Segment core(int pass) {
        Optional<Segment> c = NetherPortalJump.netherCore(C, pass);
        assertTrue(c.isPresent(), "pass " + pass + " has a core");
        return c.get();
    }

    private static long target(long fromX) {
        OptionalLong t = NetherPortalJump.targetX(C, LABEL, (int) fromX);
        assertTrue(t.isPresent(), "a target from x=" + fromX);
        return t.getAsLong();
    }

    private static double fraction(Segment s, long x) {
        return (double) (x - s.start()) / (double) s.length();
    }

    private static Segment segment(long x) {
        return NetherPortalJump.segmentAround(LABEL, (int) x, Math.max(0L, x - BOUND), x + BOUND);
    }

    @Test
    @DisplayName("segmentAround finds both edges exactly on a synthetic label strip")
    void segmentEdgesAreExact() {
        IntFunction<String> strip = x -> x < 100 ? "A" : (x < 1237 ? "B" : "C");
        assertEquals(new Segment(100, 1237), NetherPortalJump.segmentAround(strip, 500, -10_000, 10_000));
        assertEquals(new Segment(100, 1237), NetherPortalJump.segmentAround(strip, 100, -10_000, 10_000));
        assertEquals(new Segment(100, 1237), NetherPortalJump.segmentAround(strip, 1236, -10_000, 10_000));
        assertEquals(new Segment(1237, 2001), NetherPortalJump.segmentAround(strip, 1500, 0, 2000));
    }

    @Test
    @DisplayName("mapFraction keeps the proportion across segments of different lengths and stays inside the target")
    void fractionMapping() {
        Segment from = new Segment(1000, 1400);
        Segment to = new Segment(50_000, 51_000);
        assertEquals(50_000, NetherPortalJump.mapFraction(from, 1000, to));
        assertEquals(50_500, NetherPortalJump.mapFraction(from, 1200, to));
        assertEquals(50_997, NetherPortalJump.mapFraction(from, 1399, to));
        assertEquals(50_999, NetherPortalJump.mapFraction(from, 1400, to));
        assertEquals(50_000, NetherPortalJump.mapFraction(new Segment(5, 5), 5, to));
    }

    @Test
    @DisplayName("the Nether core is exactly the run of isNetherCore columns inside the pass")
    void netherCoreIsEdgeExact() {
        Segment c0 = core(0);
        long[] r0 = C.netherPassRange(0);
        assertTrue(c0.start() > r0[0] && c0.end() < r0[1], "core strictly inside the slot " + c0);
        assertTrue(C.isNetherCore((int) c0.start()));
        assertFalse(C.isNetherCore((int) c0.start() - 1));
        assertTrue(C.isNetherCore((int) c0.end() - 1));
        assertFalse(C.isNetherCore((int) c0.end()));
        assertTrue(c0.length() > 1000, "a real core, not a sliver");
    }

    @Test
    @DisplayName("a non-Nether core trims the Nether approach from both ends, capped at a third, never empty")
    void coreOfTrims() {
        long approach = C.netherApproachLength();
        Segment wide = new Segment(0, 10 * approach);
        assertEquals(new Segment(approach, 10 * approach - approach), NetherPortalJump.coreOf(wide, C));
        Segment narrow = new Segment(0, 30);
        assertEquals(new Segment(10, 20), NetherPortalJump.coreOf(narrow, C));
        assertEquals(new Segment(0, 2), NetherPortalJump.coreOf(new Segment(0, 2), C));
    }

    @Test
    @DisplayName("first overworld → the first Nether core, at the same fraction through the stretch")
    void firstOverworldGoesToFirstNetherCore() {
        Segment ow = segment(ShippedCycles.START + 500);
        assertEquals(0L, ow.start());
        long fromX = ow.start() + ow.length() / 4;
        long t = target(fromX);
        Segment c0 = core(0);
        assertTrue(c0.contains(t), "lands in pass 0's core " + c0 + ", got " + t);
        assertEquals(fraction(ow, fromX), fraction(c0, t), 0.002);
    }

    @Test
    @DisplayName("in a Nether band's mountain approach (before its core) counts as the band before it")
    void approachBeforeFirstCoreGoesToFirstCore() {
        Segment c0 = core(0);
        long approach = C.netherPassRange(0)[0] + 1;          // mountain rise: no netherrack yet
        assertFalse(NetherBand.isInNetherBand(C, (int) approach));
        assertTrue(c0.contains(target(approach)));
        // Already in the netherrack crossfade = "in the Nether": back to the band before it.
        long crossfade = c0.start() - 10;
        assertTrue(NetherBand.isInNetherBand(C, (int) crossfade));
        assertFalse(c0.contains(target(crossfade)));
    }

    @Test
    @DisplayName("an overworld stretch after the first Nether → back to the previous Nether core, not forward")
    void laterOverworldGoesBackToPreviousNether() {
        long afterPass0 = C.netherPassRange(0)[1] + 200;
        assertFalse(NetherBand.isInNetherBand(C, (int) afterPass0));
        long t = target(afterPass0);
        assertTrue(core(0).contains(t), "back into pass 0's core, got " + t);
        assertFalse(core(1).contains(t));
    }

    @Test
    @DisplayName("inside pass 1 → the core of the band just before it, at the same fraction")
    void insideNetherGoesToPreviousBandCore() {
        Segment c1 = core(1);
        long fromX = c1.start() + (long) (c1.length() * 0.4);
        assertTrue(NetherBand.isInNetherBand(C, (int) fromX));
        Segment nether = segment(fromX);
        Segment before = segment(nether.start() - 1);
        assertFalse(NetherBand.isInNetherBand(C, (int) before.start()), "the band before pass 1 is not Nether");
        Segment beforeCore = NetherPortalJump.coreOf(before, C);
        long t = target(fromX);
        assertTrue(beforeCore.contains(t), "lands in " + beforeCore + ", got " + t);
        assertEquals(fraction(nether, fromX), fraction(beforeCore, t), 0.002);
    }

    @Test
    @DisplayName("inside the first Nether → the first overworld's core")
    void firstNetherGoesBackToFirstOverworld() {
        Segment c0 = core(0);
        long fromX = c0.start() + 100;
        Segment ow = segment(0);
        long t = target(fromX);
        assertTrue(NetherPortalJump.coreOf(ow, C).contains(t), "lands in the first overworld core, got " + t);
    }
}
