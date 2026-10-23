package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckLandingCellTest {

    private static final BlockPos CENTRE = new BlockPos(10, 0, 5);

    @Test
    void picksCellInTargetColumnNearestPreferredY() {
        Set<BlockPos> ok = Set.of(new BlockPos(10, 1, 5), new BlockPos(10, 7, 5));
        assertEquals(Optional.of(new BlockPos(10, 7, 5)), DeckLandingCell.find(CENTRE, 0, 8, 9, ok::contains));
        assertEquals(Optional.of(new BlockPos(10, 1, 5)), DeckLandingCell.find(CENTRE, 0, 8, 2, ok::contains));
    }

    @Test
    void targetColumnBeatsNeighboursEvenAtWorseY() {
        Set<BlockPos> ok = Set.of(new BlockPos(10, 1, 5), new BlockPos(11, 7, 5));
        assertEquals(Optional.of(new BlockPos(10, 1, 5)), DeckLandingCell.find(CENTRE, 0, 8, 7, ok::contains));
    }

    @Test
    void fallsBackToNearestRing() {
        Set<BlockPos> ok = Set.of(new BlockPos(12, 1, 5), new BlockPos(11, 1, 6));
        assertEquals(Optional.of(new BlockPos(11, 1, 6)), DeckLandingCell.find(CENTRE, 0, 8, 1, ok::contains));
    }

    @Test
    void emptyWhenNothingStandableInRange() {
        Set<BlockPos> ok = Set.of(new BlockPos(13, 1, 5), new BlockPos(10, 9, 5));
        assertTrue(DeckLandingCell.find(CENTRE, 0, 8, 1, ok::contains).isEmpty());
    }
}
