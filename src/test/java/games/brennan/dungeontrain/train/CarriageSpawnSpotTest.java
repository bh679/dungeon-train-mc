package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CarriageSpawnSpot} — the search order over a carriage floor and the
 * clear-cell pick, driven through predicates so no level is needed.
 */
final class CarriageSpawnSpotTest {

    private static final BlockPos ORIGIN = new BlockPos(100, 64, -20);

    @Test
    void preferredCellComesFirst() {
        List<BlockPos> order = CarriageSpawnSpot.candidateOrder(new Vec3i(7, 3, 5), 3, 2);
        assertEquals(new BlockPos(3, 0, 2), order.get(0));
    }

    @Test
    void coversEveryCellExactlyOnce() {
        for (Vec3i size : List.of(new Vec3i(7, 3, 5), new Vec3i(8, 3, 4), new Vec3i(1, 3, 1))) {
            List<BlockPos> order = CarriageSpawnSpot.candidateOrder(size, size.getX() / 2, size.getZ() / 2);
            Set<BlockPos> unique = new HashSet<>(order);
            assertEquals(size.getX() * size.getZ(), order.size(), "size " + size);
            assertEquals(order.size(), unique.size(), "duplicates for size " + size);
            for (BlockPos p : order) {
                assertTrue(p.getX() >= 0 && p.getX() < size.getX() && p.getY() == 0
                    && p.getZ() >= 0 && p.getZ() < size.getZ(), "out of range " + p);
            }
        }
    }

    @Test
    void ringsNeverMoveFurtherThenCloser() {
        List<BlockPos> order = CarriageSpawnSpot.candidateOrder(new Vec3i(9, 3, 5), 4, 2);
        int last = 0;
        for (BlockPos p : order) {
            int ring = Math.max(Math.abs(p.getX() - 4), Math.abs(p.getZ() - 2));
            assertTrue(ring >= last, "ring went back at " + p);
            last = ring;
        }
    }

    @Test
    void edgePreferenceStillReachesTheFarSide() {
        List<BlockPos> order = CarriageSpawnSpot.candidateOrder(new Vec3i(6, 3, 3), 0, 0);
        assertEquals(new BlockPos(0, 0, 0), order.get(0));
        assertEquals(18, order.size());
        assertTrue(order.contains(new BlockPos(5, 0, 2)));
    }

    @Test
    void zeroSizeFloorHasNoCandidates() {
        assertTrue(CarriageSpawnSpot.candidateOrder(new Vec3i(0, 3, 5), 0, 2).isEmpty());
    }

    @Test
    void openCentreIsUsed() {
        Optional<BlockPos> spot = CarriageSpawnSpot.find(ORIGIN, new Vec3i(7, 3, 5), p -> true, p -> false);
        assertEquals(Optional.of(ORIGIN.offset(3, 0, 2)), spot);
    }

    @Test
    void blockedCentreGivesAnAdjacentCell() {
        BlockPos centre = ORIGIN.offset(3, 0, 2);
        Optional<BlockPos> spot = CarriageSpawnSpot.find(ORIGIN, new Vec3i(7, 3, 5),
            p -> !p.equals(centre) && !p.equals(centre.above()), p -> false);
        assertTrue(spot.isPresent());
        BlockPos s = spot.get();
        assertEquals(ORIGIN.getY(), s.getY());
        assertEquals(1, Math.max(Math.abs(s.getX() - centre.getX()), Math.abs(s.getZ() - centre.getZ())));
    }

    @Test
    void standsOnALowBlockWhenTheFloorCellIsSolid() {
        BlockPos centre = ORIGIN.offset(3, 0, 2);
        // Centre floor cell holds a carpet: feet there collide, the cell above is free.
        Optional<BlockPos> spot = CarriageSpawnSpot.find(ORIGIN, new Vec3i(7, 3, 5),
            p -> !p.equals(centre), p -> p.equals(centre));
        assertEquals(Optional.of(centre.above()), spot);
    }

    @Test
    void noStepUpWithoutSomethingToStandOn() {
        BlockPos centre = ORIGIN.offset(3, 0, 2);
        // Centre feet blocked (e.g. a hanging block at head height) but the floor cell is not solid,
        // so floating one block up is not a stand.
        Optional<BlockPos> spot = CarriageSpawnSpot.find(ORIGIN, new Vec3i(7, 3, 5),
            p -> !p.equals(centre), p -> false);
        assertTrue(spot.isPresent());
        assertTrue(!spot.get().equals(centre.above()));
        assertEquals(ORIGIN.getY(), spot.get().getY());
    }

    @Test
    void fullFloorFindsNothing() {
        assertEquals(Optional.empty(),
            CarriageSpawnSpot.find(ORIGIN, new Vec3i(7, 3, 5), p -> false, p -> true));
    }
}
