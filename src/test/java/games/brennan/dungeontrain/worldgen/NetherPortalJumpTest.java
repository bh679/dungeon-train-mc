package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NetherPortalJump} over the shipped cycle: outside a Nether band the portal goes forward to
 * the next band's netherrack entry (+ inset); inside one it goes back to the previous band; the
 * first band has no previous one.
 */
final class NetherPortalJumpTest {

    private static final WorldGenCycle C = ShippedCycles.CYCLE;

    private static long entry(int pass) {
        OptionalLong e = NetherPortalJump.netherrackEntry(C, pass);
        assertTrue(e.isPresent(), "pass " + pass + " has a netherrack entry");
        return e.getAsLong();
    }

    private static long target(long fromX) {
        OptionalLong t = NetherPortalJump.targetX(C, (int) fromX);
        assertTrue(t.isPresent(), "a target from x=" + fromX);
        return t.getAsLong();
    }

    @Test
    @DisplayName("the netherrack entry is the first column of the pass with netherrack, inside the pass range")
    void entryIsFirstNetherrackColumn() {
        long[] r0 = C.netherPassRange(0);
        long e0 = entry(0);
        assertTrue(e0 >= r0[0] && e0 < r0[1], "entry inside pass 0's range");
        assertTrue(C.netherRamp((int) e0) > 0.0);
        assertEquals(0.0, C.netherRamp((int) e0 - 1));
    }

    @Test
    @DisplayName("before the first band → the first band's entry + inset")
    void beforeFirstBandGoesForward() {
        assertEquals(entry(0) + NetherPortalJump.ENTRY_INSET, target(ShippedCycles.START - 100));
    }

    @Test
    @DisplayName("between band 0 and band 1 → band 1's entry + inset")
    void betweenBandsGoesToTheNextOne() {
        long after0 = C.netherPassRange(0)[1] + 50;
        assertFalse(NetherBand.isInNetherBand(C, (int) after0));
        assertEquals(entry(1) + NetherPortalJump.ENTRY_INSET, target(after0));
    }

    @Test
    @DisplayName("in a band's mountain approach (no netherrack yet) → that band's entry, not the one after")
    void approachCountsAsOutsideAndJumpsIntoThatBand() {
        long approach = C.netherPassRange(1)[0] + 1;
        assertFalse(NetherBand.isInNetherBand(C, (int) approach));
        assertEquals(entry(1) + NetherPortalJump.ENTRY_INSET, target(approach));
    }

    @Test
    @DisplayName("inside band 1 → band 0's entry + inset")
    void insideGoesBack() {
        long inside1 = entry(1) + 200;
        assertTrue(NetherBand.isInNetherBand(C, (int) inside1));
        assertEquals(entry(0) + NetherPortalJump.ENTRY_INSET, target(inside1));
    }

    @Test
    @DisplayName("inside the first band there is no previous band → empty")
    void insideFirstBandHasNowhereToGo() {
        long inside0 = entry(0) + 200;
        assertTrue(NetherBand.isInNetherBand(C, (int) inside0));
        assertTrue(NetherPortalJump.targetX(C, (int) inside0).isEmpty());
    }
}
