package games.brennan.dungeontrain.ship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link CarriageBoxIndex}, the per-tick snapshot the fluid barrier consults
 * before it reads any live carriage box. No NeoForge bootstrap.
 */
final class CarriageBoxIndexTest {

    /** A carriage box as loose bounds; the handle the index hands back. */
    private record Box(String name, double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {}

    private static final CarriageBoxIndex.BoxReader<Box> READ = (b, out) -> {
        out[0] = b.minX(); out[1] = b.minY(); out[2] = b.minZ();
        out[3] = b.maxX(); out[4] = b.maxY(); out[5] = b.maxZ();
    };

    /** Two carriage groups out along the track, 9 long, 6 tall, 7 wide, with a gap between. */
    private static final Box FRONT = new Box("front", 581.0, 77.0, 0.0, 590.0, 83.0, 7.0);
    private static final Box BACK = new Box("back", 570.0, 77.0, 0.0, 579.0, 83.0, 7.0);

    private static CarriageBoxIndex<Box> index(double margin, Box... boxes) {
        return CarriageBoxIndex.of(List.of(boxes), READ, margin);
    }

    private static List<Box> candidates(CarriageBoxIndex<Box> idx, double x, double y, double z) {
        List<Box> out = new ArrayList<>();
        for (int i = idx.nextCandidate(x, y, z, 0); i >= 0; i = idx.nextCandidate(x, y, z, i + 1)) {
            out.add(idx.handle(i));
        }
        return out;
    }

    @Test
    @DisplayName("an empty index holds nothing and never matches")
    void emptyIndexNeverMatches() {
        CarriageBoxIndex<Box> idx = index(2.0);
        assertEquals(0, idx.size());
        assertFalse(idx.mayContain(585.5, 80.5, 3.5));
        assertEquals(-1, idx.nextCandidate(585.5, 80.5, 3.5, 0));
        assertSame(CarriageBoxIndex.empty(), idx, "no boxes shares the one empty instance");
    }

    @Test
    @DisplayName("a point inside a carriage is a candidate for that carriage only")
    void pointInsideBoxIsCandidate() {
        CarriageBoxIndex<Box> idx = index(2.0, FRONT, BACK);
        assertTrue(idx.mayContain(585.5, 80.5, 3.5));
        assertEquals(List.of(FRONT), candidates(idx, 585.5, 80.5, 3.5));
    }

    @Test
    @DisplayName("a point inside the margin but outside the box is still a candidate: the live test decides")
    void pointInMarginIsCandidate() {
        CarriageBoxIndex<Box> idx = index(2.0, FRONT);
        // 1.5 blocks past the front face: a carriage doing 20 blocks/s moves 1 block per tick.
        assertEquals(List.of(FRONT), candidates(idx, 591.5, 80.5, 3.5));
        // and 2.5 blocks past it is outside the grown box
        assertTrue(candidates(idx, 592.5, 80.5, 3.5).isEmpty());
    }

    @Test
    @DisplayName("a point outside every grown box is rejected by the union test alone")
    void pointOutsideUnionIsRejected() {
        CarriageBoxIndex<Box> idx = index(2.0, FRONT, BACK);
        assertFalse(idx.mayContain(400.5, 80.5, 3.5), "far down the track");
        assertFalse(idx.mayContain(585.5, 120.5, 3.5), "high above the train");
        assertFalse(idx.mayContain(585.5, 80.5, 20.5), "off to the side");
    }

    @Test
    @DisplayName("the gap between two carriages is inside the union but a candidate of neither")
    void gapBetweenCarriagesHasNoCandidate() {
        CarriageBoxIndex<Box> idx = index(0.0, FRONT, BACK);
        // x = 580 lies in the one-block gap between BACK (…579) and FRONT (581…).
        assertTrue(idx.mayContain(580.0, 80.5, 3.5), "the union spans the gap");
        assertTrue(candidates(idx, 580.0, 80.5, 3.5).isEmpty(), "but no carriage grown by 0 covers it");
    }

    @Test
    @DisplayName("a point in two overlapping grown boxes names both, in index order")
    void overlappingBoxesNameBothCandidates() {
        CarriageBoxIndex<Box> idx = index(2.0, FRONT, BACK);
        // x = 580 is within 2 of both BACK's max (579) and FRONT's min (581).
        assertEquals(List.of(FRONT, BACK), candidates(idx, 580.0, 80.5, 3.5));
    }

    @Test
    @DisplayName("a degenerate box is left out of the index")
    void degenerateBoxIsSkipped() {
        Box unticked = new Box("unticked", 0, 0, 0, 0, 0, 0);
        CarriageBoxIndex<Box> idx = index(2.0, unticked, FRONT);
        assertEquals(1, idx.size());
        assertEquals(List.of(FRONT), candidates(idx, 585.5, 80.5, 3.5));
        assertFalse(idx.mayContain(0.5, 0.5, 0.5), "the unticked box contributes nothing to the union");
    }

    @Test
    @DisplayName("only degenerate boxes is the empty index")
    void allDegenerateIsEmpty() {
        Box unticked = new Box("unticked", 0, 0, 0, 0, 0, 0);
        assertSame(CarriageBoxIndex.empty(), index(2.0, unticked));
    }

    @Test
    @DisplayName("a negative or NaN margin is refused")
    void badMarginIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> index(-1.0, FRONT));
        assertThrows(IllegalArgumentException.class, () -> index(Double.NaN, FRONT));
    }

    @Test
    @DisplayName("nextCandidate from past the end answers -1, so the iteration idiom terminates")
    void nextCandidatePastEndIsMinusOne() {
        CarriageBoxIndex<Box> idx = index(2.0, FRONT);
        assertEquals(-1, idx.nextCandidate(585.5, 80.5, 3.5, idx.size()));
        assertEquals(0, idx.nextCandidate(585.5, 80.5, 3.5, -5), "a negative start is clamped to 0");
    }
}
