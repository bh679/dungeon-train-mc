package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage for the UUID-keyed core of {@link PlayerPlacedTrainBlocks}. */
class PlayerPlacedTrainBlocksTest {

    private static final UUID CARRIAGE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @AfterEach
    void reset() {
        PlayerPlacedTrainBlocks.clear();
    }

    @Test
    @DisplayName("a marked cell reads back as player-placed, on that carriage only")
    void markIsPerCarriage() {
        PlayerPlacedTrainBlocks.mark(CARRIAGE, 42L);

        assertTrue(PlayerPlacedTrainBlocks.isMarked(CARRIAGE, 42L));
        assertFalse(PlayerPlacedTrainBlocks.isMarked(CARRIAGE, 43L), "another cell");
        assertFalse(PlayerPlacedTrainBlocks.isMarked(OTHER, 42L), "same cell, other carriage");
        assertTrue(PlayerPlacedTrainBlocks.hasAny(CARRIAGE));
        assertFalse(PlayerPlacedTrainBlocks.hasAny(OTHER));
    }

    @Test
    @DisplayName("unmarking the last cell drops the carriage entry, so hasAny goes cheap again")
    void unmarkDropsEmptyEntries() {
        PlayerPlacedTrainBlocks.mark(CARRIAGE, 1L);
        PlayerPlacedTrainBlocks.mark(CARRIAGE, 2L);

        PlayerPlacedTrainBlocks.unmark(CARRIAGE, 1L);
        assertTrue(PlayerPlacedTrainBlocks.hasAny(CARRIAGE));

        PlayerPlacedTrainBlocks.unmark(CARRIAGE, 2L);
        assertFalse(PlayerPlacedTrainBlocks.hasAny(CARRIAGE));

        PlayerPlacedTrainBlocks.unmark(OTHER, 7L); // unknown carriage: a no-op, not an error
        assertFalse(PlayerPlacedTrainBlocks.hasAny(OTHER));
    }

    @Test
    @DisplayName("deleting a carriage and stopping the server both forget its marks")
    void removeAndClear() {
        PlayerPlacedTrainBlocks.mark(CARRIAGE, 1L);
        PlayerPlacedTrainBlocks.mark(OTHER, 1L);

        PlayerPlacedTrainBlocks.removeSubLevel(CARRIAGE);
        assertFalse(PlayerPlacedTrainBlocks.hasAny(CARRIAGE));
        assertTrue(PlayerPlacedTrainBlocks.hasAny(OTHER));

        PlayerPlacedTrainBlocks.clear();
        assertFalse(PlayerPlacedTrainBlocks.hasAny(OTHER));
    }

    @Test
    @DisplayName("keys are relative to the plot origin, so a relocated plot keeps its marks")
    void relKeyIsPlotRelative() {
        BlockPos originA = new BlockPos(16_000, 0, -32_000);
        BlockPos originB = new BlockPos(48_000, 0, 64_000);
        BlockPos offset = new BlockPos(5, 97, 3);

        long keyA = PlayerPlacedTrainBlocks.relKey(originA, originA.offset(offset));
        long keyB = PlayerPlacedTrainBlocks.relKey(originB, originB.offset(offset));

        assertEquals(keyA, keyB, "same block, different plot location");
        assertEquals(offset, BlockPos.of(keyA));
        assertNotEquals(keyA, PlayerPlacedTrainBlocks.relKey(originA, originA.offset(offset).above()));
    }
}
