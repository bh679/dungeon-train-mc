package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.ReverseSlide.Sample;
import games.brennan.dungeontrain.worldgen.ReverseSlide.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The frontier/slide rule: only on-train world-X progress brings the reversed bands closer. */
final class ReverseSlideTest {

    private static final long START = 0L;
    private static final long UNSET = ReverseSlide.UNSET;

    private static State next(long origin, long frontier, long slide, Sample... players) {
        return ReverseSlide.next(START, origin, frontier, slide, List.of(players));
    }

    private static Sample on(double x) {
        return new Sample(x, true, true);
    }

    private static Sample off(double x) {
        return new Sample(x, false, true);
    }

    @Test
    @DisplayName("the origin is the first on-train position; the frontier starts there and follows riders back")
    void originAndFrontier() {
        assertEquals(new State(UNSET, 0L, 0L), next(UNSET, UNSET, 0L, off(40.0)));        // nobody aboard yet
        assertEquals(new State(-15L, -15L, 0L), next(UNSET, UNSET, 0L, on(-14.6)));
        assertEquals(new State(-15L, -1200L, 0L), next(-15L, -15L, 0L, on(-1199.5)));
        assertEquals(new State(-15L, -1200L, 0L), next(-15L, -1200L, 0L, on(-300.0)));   // never forward
        assertEquals(new State(-15L, -15L, 0L), next(-15L, UNSET, 0L, on(500.0)));
    }

    @Test
    @DisplayName("boarding ahead of the anchor starts the slide at the origin's lead, so the gap is measured from it")
    void originLead() {
        assertEquals(new State(40L, 40L, 64L), next(UNSET, UNSET, 0L, on(40.0)));
        assertEquals(new State(200L, 200L, 256L), next(200L, 200L, 0L, on(900.0)));
    }

    @Test
    @DisplayName("walking off-train past the frontier slides the bands back by that far, in 64-block steps")
    void slide() {
        assertEquals(new State(-15L, -15L, 1024L), next(-15L, -15L, 0L, off(-1000.0)));
        assertEquals(new State(-15L, -2000L, 64L), next(-15L, -2000L, 0L, off(-2001.0)));
        assertEquals(new State(-15L, -2000L, 0L), next(-15L, -2000L, 0L, off(-1500.0)));   // not past the frontier
        assertEquals(new State(-15L, -2000L, 0L), next(-15L, -2000L, 0L, off(-2000.0)));
    }

    @Test
    @DisplayName("creative players earn on the train but never push the bands back")
    void creative() {
        assertEquals(new State(-15L, -500L, 0L), next(-15L, -15L, 0L, new Sample(-500.0, true, false)));
        assertEquals(new State(-15L, -15L, 0L), next(-15L, -15L, 0L, new Sample(-3000.0, false, false)));
    }

    @Test
    @DisplayName("the slide never shrinks, and riders move the frontier before off-train players are measured")
    void monotone() {
        assertEquals(new State(-15L, -2000L, 1024L), next(-15L, -2000L, 1024L, off(-2100.0)));
        assertEquals(new State(-15L, -3000L, 0L), next(-15L, -2000L, 0L, on(-3000.0), off(-2800.0)));
        assertEquals(new State(-15L, -3000L, 256L), next(-15L, -2000L, 0L, on(-3000.0), off(-3200.0)));
    }

    @Test
    @DisplayName("with a slide, the whole reversed cycle sits that much further back; the forward side is untouched")
    void cycleShifts() {
        CycleLayout layout = CycleLayoutTest.shipped();
        WorldGenCycle c = new WorldGenCycle(10_000L, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
                CycleLayoutTest.eraDefaults(), layout, 0);
        long slide = 1024L;
        int[] probes = {-1, -7_000, -40_000, -130_000, -300_000};
        int[][] before = new int[probes.length][];
        for (int j = 0; j < probes.length; j++) {
            int x = 10_000 - (int) layout.length(0) + probes[j];
            before[j] = new int[] {c.slotIndexAt(x), (int) c.slotLocal(x), (int) c.cycleIndex(x)};
        }
        int fwdSlot = c.slotIndexAt(60_000);
        try {
            WorldGenCycle.setReverseSlide(slide);
            for (int j = 0; j < probes.length; j++) {
                int x = 10_000 - (int) layout.length(0) + probes[j] - (int) slide;
                assertEquals(before[j][0], c.slotIndexAt(x));
                assertEquals(before[j][1], (int) c.slotLocal(x));
                assertEquals(before[j][2], (int) c.cycleIndex(x));
            }
            assertEquals(-1, c.slotIndexAt(10_000 - (int) layout.length(0) - 500));   // now overworld buffer
            assertEquals(fwdSlot, c.slotIndexAt(60_000));
        } finally {
            WorldGenCycle.setReverseSlide(0L);
        }
    }
}
