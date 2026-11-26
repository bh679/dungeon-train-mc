package games.brennan.dungeontrain.client.farcopies;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarCopiesLayoutTest {

    private static final int SX = 10;
    private static final int SY = 5;
    private static final int SZ = 8;

    private static RoomBoxModel floorOnly() {
        TileVoxels.Builder b = new TileVoxels.Builder(SX, SY, SZ);
        for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) b.setRgb(x, 0, z, 0x707070);
        return RoomBoxModel.of(b.build());
    }

    private static FarCopiesLayout.Input input(Set<Long> stamped, List<FarCopiesLayout.TileRect> blocked,
                                               int near, int mid, int far, int max) {
        return new FarCopiesLayout.Input(floorOnly(), 0, 0, stamped, blocked, near, mid, far, max);
    }

    private static boolean covers(List<FarBox> boxes, float x, float z) {
        for (FarBox b : boxes) {
            if (x >= b.minX() && x < b.maxX() && z >= b.minZ() && z < b.maxZ()) return true;
        }
        return false;
    }

    @Test
    void nearRingFillsOnlyTilesWithNoRealCopy() {
        Set<Long> stamped = Set.of(FarCopiesLayout.key(0, 0), FarCopiesLayout.key(1, 0));
        FarCopiesLayout.Result r = FarCopiesLayout.build(input(stamped, List.of(), 1, 1, 1, 100_000));
        assertEquals(9 - 2, r.near().size());
        assertFalse(covers(r.near(), 5, 4));
        assertFalse(covers(r.near(), 15, 4));
        assertTrue(covers(r.near(), -5, 4));
    }

    @Test
    void blockedTilesAreNeverDrawnIntoInAnyRing() {
        List<FarCopiesLayout.TileRect> blocked = List.of(new FarCopiesLayout.TileRect(3, -1, 4, 1));
        FarCopiesLayout.Result r = FarCopiesLayout.build(input(Set.of(), blocked, 1, 2, 10, 100_000));
        List<FarBox> all = new java.util.ArrayList<>(r.near());
        all.addAll(r.outer());
        assertFalse(covers(all, 3 * SX + 1, 1));
        assertFalse(covers(all, 4 * SX + 1, 1));
        assertTrue(covers(all, 5 * SX + 1, 1), "the row carries on past the cut");
        assertTrue(covers(all, 3 * SX + 1, 2 * SZ + 1), "other rows are untouched");
    }

    @Test
    void anUnblockedFarRectangleIsOneBoxPerPlane() {
        FarCopiesLayout.Result r = FarCopiesLayout.build(input(Set.of(), List.of(), 0, 0, 50, 100_000));
        // near: the centre tile; outer: four rectangles of one plane each.
        assertEquals(1, r.near().size());
        assertEquals(4, r.outer().size());
        assertTrue(covers(r.outer(), 50 * SX + 1, -50 * SZ + 1));
    }

    @Test
    void farRingShrinksToFitTheBudget() {
        List<FarCopiesLayout.TileRect> blocked = List.of(new FarCopiesLayout.TileRect(40, -500, 40, 500));
        FarCopiesLayout.Result wide = FarCopiesLayout.build(input(Set.of(), blocked, 0, 0, 400, 100_000));
        FarCopiesLayout.Result tight = FarCopiesLayout.build(input(Set.of(), blocked, 0, 0, 400, 300));
        assertTrue(tight.size() <= 300, "size " + tight.size());
        assertTrue(tight.farRadius() < wide.farRadius());
    }

    @Test
    void segmentsCutOnlyTheirOwnLine() {
        List<FarCopiesLayout.TileRect> cuts = List.of(new FarCopiesLayout.TileRect(2, 0, 3, 0));
        List<int[]> row0 = FarCopiesLayout.segments(0, 9, cuts, 0, true);
        assertEquals(2, row0.size());
        assertEquals(1, row0.get(0)[1]);
        assertEquals(4, row0.get(1)[0]);
        assertEquals(1, FarCopiesLayout.segments(0, 9, cuts, 1, true).size());
    }

    @Test
    void tilesOfMapsWorldBoxesAndSkipsOtherHeights() {
        assertEquals(new FarCopiesLayout.TileRect(-1, 0, 2, 0),
            FarCopiesLayout.tilesOf(95, 60, 200, 125, 62, 207, 100, 60, 200, SX, SY, SZ));
        assertEquals(null, FarCopiesLayout.tilesOf(0, 80, 0, 5, 90, 5, 100, 60, 200, SX, SY, SZ));
    }
}
