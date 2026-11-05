package games.brennan.dungeontrain.compat.vista;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching a screen off from any of its blocks: Vista keeps a screen on while any block holds its own
 * signal, so every other such block lets go first, and the clicked one carries the flip across the grid.
 */
class TvPowerToggleTest {

    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(1, 64, 0);
    private static final BlockPos C = new BlockPos(0, 65, 0);
    private static final BlockPos D = new BlockPos(1, 65, 0);

    @Test
    void theBlockThatWasClickedOnLetsGoWhenAnotherIsClickedOff() {
        // The screen came on by a click on A (A holds the signal); the click off lands on D.
        List<BlockPos> clear = TvPowerToggle.othersToClear(Set.of(A, B, C, D), D, p -> p.equals(A));
        assertEquals(List.of(A), clear);
    }

    @Test
    void everyOtherSignalHolderLetsGo() {
        // Placed switched on one by one, then clicked off at A: B and D each held a signal of their own.
        List<BlockPos> clear = TvPowerToggle.othersToClear(Set.of(A, B, C, D), A, p -> p.equals(B) || p.equals(D));
        assertEquals(2, clear.size());
        assertTrue(clear.containsAll(List.of(B, D)));
    }

    @Test
    void theClickedBlockIsNeverClearedAhead() {
        // Vista's own handler flips it; clearing it first would read as already off and flip it back on.
        assertEquals(List.of(), TvPowerToggle.othersToClear(Set.of(A, B, C, D), A, p -> p.equals(A)));
    }

    @Test
    void aLoneTvHasNothingToClear() {
        assertEquals(List.of(), TvPowerToggle.othersToClear(Set.of(A), A, p -> true));
    }
}
