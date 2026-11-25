package games.brennan.dungeontrain.ship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CarriageBoxIndex#nearestTwo} and {@link CarriageBoxIndex#firstContaining}: mob AI's
 * "nearest two carriages" and "carriage at this point" lookups. The index is built with the same
 * non-zero coarse margin as production, and the live boxes may differ from the snapshot, so these
 * tests catch an answer taken from the grown boxes instead of the live ones.
 */
final class CarriageBoxIndexNearestTest {

    private record Box(String name, double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {
        Box shifted(double dx) {
            return new Box(name, minX + dx, minY, minZ, maxX + dx, maxY, maxZ);
        }
    }

    private static final CarriageBoxIndex.BoxReader<Box> READ = (b, out) -> {
        out[0] = b.minX(); out[1] = b.minY(); out[2] = b.minZ();
        out[3] = b.maxX(); out[4] = b.maxY(); out[5] = b.maxZ();
    };

    /** As production: boxes grown by the coarse margin at snapshot time. */
    private static final double MARGIN = 2.0;
    /** {@code MobDigGoal.CARRIAGE_PAD}. */
    private static final double PAD = 2.0;

    /** Carriage groups along X, 6 tall, 7 wide, one-block gaps. */
    private static final Box A = new Box("A", 570.0, 77.0, 0.0, 579.0, 83.0, 7.0);
    private static final Box B = new Box("B", 580.0, 77.0, 0.0, 583.0, 83.0, 7.0);
    private static final Box C = new Box("C", 584.0, 77.0, 0.0, 593.0, 83.0, 7.0);

    private static CarriageBoxIndex<Box> index(Box... boxes) {
        return CarriageBoxIndex.of(List.of(boxes), READ, MARGIN);
    }

    private static List<String> nearest(CarriageBoxIndex<Box> idx, double pad, CarriageBoxIndex.BoxReader<Box> live,
                                        double x, double y, double z) {
        int[] out = new int[2];
        int n = idx.nearestTwo(x, y, z, pad, live, new double[6], out);
        List<String> names = new ArrayList<>();
        for (int k = 0; k < n; k++) names.add(idx.handle(out[k]).name());
        return names;
    }

    private static List<String> nearest(CarriageBoxIndex<Box> idx, double x, double y, double z) {
        return nearest(idx, PAD, READ, x, y, z);
    }

    @Test
    @DisplayName("a mob inside one carriage, the others far, gets that carriage alone")
    void insideOneCarriage() {
        assertEquals(List.of("A"), nearest(index(A, C), 574.5, 78.0, 3.5));
    }

    @Test
    @DisplayName("a mob in the gap between two carriages gets both, nearest first")
    void betweenTwoCarriages() {
        assertEquals(List.of("A", "B"), nearest(index(A, B), 579.4, 78.0, 3.5));
        assertEquals(List.of("B", "A"), nearest(index(A, B), 579.7, 78.0, 3.5));
    }

    @Test
    @DisplayName("standing on one with two neighbours in reach: the stood-on one, then the closer neighbour")
    void stoodOnThenCloserNeighbour() {
        // In B, 2.2 from A and 2.8 from C: all three within a 3-block pad, only two come back.
        assertEquals(List.of("B", "A"), nearest(index(A, B, C), 3.0, READ, 581.2, 78.0, 3.5));
        assertEquals(List.of("B", "C"), nearest(index(A, B, C), 3.0, READ, 582.0, 78.0, 3.5));
    }

    @Test
    @DisplayName("a mob off the train, beyond the pad, gets nothing")
    void offTheTrain() {
        CarriageBoxIndex<Box> idx = index(A, B, C);
        assertEquals(List.of(), nearest(idx, 600.0, 78.0, 3.5), "past the front");
        assertEquals(List.of(), nearest(idx, 575.0, 78.0, 12.0), "beside the train, 5 blocks out");
        assertEquals(List.of(), nearest(idx, 575.0, 50.0, 3.5), "under it");
    }

    @Test
    @DisplayName("a mob just outside the live box but inside the grown one is measured live")
    void grownBoxIsNotLive() {
        // 2.5 from A's live box: inside the coarse box (2 + pad 2) but out of reach.
        assertEquals(List.of(), nearest(index(A), 581.5, 78.0, 3.5));
    }

    @Test
    @DisplayName("the stood-on carriage is kept even when it is the third coarse candidate")
    void stoodOnKeptWhenThirdCandidate() {
        // All three grown boxes hold the point; only the live boxes show it stands in the middle one.
        Box left = new Box("left", 570.0, 77.0, 0.0, 579.0, 83.0, 7.0);
        Box right = new Box("right", 581.0, 77.0, 0.0, 590.0, 83.0, 7.0);
        Box middle = new Box("middle", 579.2, 77.0, 0.0, 580.8, 83.0, 7.0);
        assertEquals(List.of("middle", "left"), nearest(index(left, right, middle), 580.0, 78.0, 3.5),
            "ties (left and right both 1 away) keep index order");
    }

    @Test
    @DisplayName("a carriage that moved since the snapshot is judged where it is now")
    void movedSinceSnapshot() {
        // Live boxes are 1.5 further along X than the snapshot.
        Map<String, Box> live = Map.of("A", A.shifted(1.5), "C", C.shifted(1.5));
        CarriageBoxIndex.BoxReader<Box> moved = (b, out) -> READ.read(live.get(b.name()), out);
        CarriageBoxIndex<Box> idx = index(A, C);
        // Snapshot: 1 past A. Live: inside A, 5.5 from C.
        assertEquals(List.of("A"), nearest(idx, PAD, moved, 580.0, 78.0, 3.5));
    }

    @Test
    @DisplayName("a carriage culled since the snapshot (empty live box) is skipped")
    void culledSinceSnapshot() {
        CarriageBoxIndex.BoxReader<Box> culled = (b, out) -> {
            if (b == A) java.util.Arrays.fill(out, 0.0);
            else READ.read(b, out);
        };
        assertEquals(List.of("B"), nearest(index(A, B), PAD, culled, 579.4, 78.0, 3.5));
    }

    @Test
    @DisplayName("an empty index gives nothing")
    void emptyIndex() {
        assertEquals(List.of(), nearest(index(), 575.0, 78.0, 3.5));
        assertEquals(-1, index().firstContaining(575.0, 78.0, 3.5, 1.0, READ, new double[6]));
    }

    @Test
    @DisplayName("firstContaining keeps the first match in index order where margins overlap")
    void firstContainingTieBreak() {
        CarriageBoxIndex<Box> idx = index(B, A);
        // 579.5: within 1 of both A (0.5) and B (0.5); B is first in the index.
        assertEquals("B", idx.handle(idx.firstContaining(579.5, 78.0, 3.5, 1.0, READ, new double[6])).name());
        assertEquals("A", idx.handle(idx.firstContaining(574.0, 78.0, 3.5, 1.0, READ, new double[6])).name());
        assertEquals(-1, idx.firstContaining(574.0, 78.0, 9.0, 1.0, READ, new double[6]), "2 blocks off the side");
    }

    @Test
    @DisplayName("firstContaining reads the live box: a moved or culled carriage answers where it is now")
    void firstContainingLive() {
        CarriageBoxIndex<Box> idx = index(A);
        CarriageBoxIndex.BoxReader<Box> moved = (b, out) -> READ.read(b.shifted(1.5), out);
        assertEquals(-1, idx.firstContaining(571.0, 78.0, 3.5, 0.0, moved, new double[6]),
            "the carriage moved off this point");
        assertEquals(0, idx.firstContaining(580.0, 78.0, 3.5, 0.0, moved, new double[6]));
        CarriageBoxIndex.BoxReader<Box> culled = (b, out) -> java.util.Arrays.fill(out, 0.0);
        assertEquals(-1, idx.firstContaining(574.0, 78.0, 3.5, 1.0, culled, new double[6]));
    }

    @Test
    @DisplayName("nextWithin walks only boxes within the pad, and stops at once far from the union")
    void nextWithin() {
        CarriageBoxIndex<Box> idx = index(A, B, C);
        assertEquals(-1, idx.nextWithin(1000.0, 78.0, 3.5, 2.0, 0));
        // Grown boxes: A to 581, B 578..585, C from 582.
        assertEquals(1, idx.nextWithin(583.5, 78.0, 3.5, 0.0, 0));
        assertEquals(2, idx.nextWithin(583.5, 78.0, 3.5, 0.0, 2));
        assertEquals(-1, idx.nextWithin(583.5, 78.0, 3.5, 0.0, 3));
    }
}
