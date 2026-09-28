package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.ReverseSlide.Sample;
import games.brennan.dungeontrain.worldgen.ReverseSlide.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The earned-distance rule: only walking back along the train into new ground brings the reversed bands
 * closer; every other way of getting further back pushes them back by as much.
 */
final class ReverseSlideTest {

    private static final long START = 0L;
    private static final long UNSET = ReverseSlide.UNSET;
    private static final double NONE = Double.NaN;

    /** Origin -15 (boarded just behind the anchor): the bands start their lead gap behind it. */
    private static final State BOARDED = new State(-15L, -15L, 0L, 64L);

    private static State step(State prev, Sample... players) {
        return ReverseSlide.next(START, prev, List.of(players)).state();
    }

    private static long[] earnedBy(State prev, Sample... players) {
        return ReverseSlide.next(START, prev, List.of(players)).earnedBy();
    }

    /** On the train now, and at {@code prev} last scan ({@code NONE} = just got on). */
    private static Sample on(double x, double prev) {
        return new Sample(x, true, true, prev);
    }

    private static Sample off(double x) {
        return new Sample(x, false, true, NONE);
    }

    @Test
    @DisplayName("the origin is the first on-train position, and boarding earns nothing")
    void origin() {
        State fresh = new State(UNSET, UNSET, 0L, 0L);
        assertEquals(fresh, step(fresh, off(40.0)));                                       // nobody aboard yet
        assertEquals(BOARDED, step(fresh, on(-14.6, NONE)));
        assertEquals(new State(40L, 40L, 0L, 0L), step(fresh, on(40.0, NONE)));         // ahead of the anchor: no slide
    }

    @Test
    @DisplayName("walking back along the train earns the new ground, and the bands stay put")
    void walkingEarns() {
        State s = step(BOARDED, on(-25.0, -15.0));
        assertEquals(new State(-15L, -25L, 10L, 64L), s);
        s = step(s, on(-20.0, -25.0));                                                     // forward: nothing
        s = step(s, on(-30.0, -20.0));                                                     // back over old ground: only the new 5
        assertEquals(new State(-15L, -30L, 15L, 64L), s);
        assertArrayEquals(new long[] {5L}, earnedBy(new State(-15L, -25L, 10L, 64L), on(-30.0, -20.0)));
    }

    @Test
    @DisplayName("flying over the train and landing further back earns nothing — the bands slide back instead")
    void flyingOverTheTrain() {
        State s = step(BOARDED, on(-115.0, -15.0 - 0.0) /* a 100-block jump: not a walk */);
        assertEquals(0L, s.earned());
        State flown = step(new State(-15L, -25L, 10L, 64L), off(-400.0));                // off-train, behind the reach
        assertEquals(10L, flown.earned());
        assertEquals(-400L, flown.reachX());
        assertEquals(448L, flown.slide());                                                 // 400 − 10 rounded up to 64
        State landed = step(flown, on(-402.0, NONE));                                     // lands: first scan aboard
        assertEquals(10L, landed.earned());
        State walked = step(landed, on(-410.0, -402.0));                                  // then walking back earns again
        assertEquals(18L, walked.earned());
        assertEquals(448L, walked.slide());
    }

    @Test
    @DisplayName("a non-sliding player (creative on a server) earns on the train but never pushes the bands back")
    void nonSliding() {
        State s = step(BOARDED, new Sample(-25.0, true, false, -15.0));
        assertEquals(10L, s.earned());
        assertEquals(s, step(s, new Sample(-3000.0, false, false, NONE)));
    }

    @Test
    @DisplayName("the slide never shrinks")
    void monotone() {
        State s = new State(-15L, -2000L, 100L, 2048L);
        assertEquals(2048L, step(s, on(-2001.0, -2000.0)).slide());
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
