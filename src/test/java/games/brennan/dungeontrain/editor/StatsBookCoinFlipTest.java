package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flip behind {@code dungeontrain:stats_book}: half a tall leaderboard board, half a Faulthurst
 * note about the finder's own run.
 *
 * <p>Two properties matter and neither needs a server. It must be <b>stable</b> — a chest at a given
 * world seed always holds the same kind, so reloading to re-roll a chest gets you what you got — and
 * it must be <b>even</b>, because a loot table placing one entry is relying on it to be two.</p>
 *
 * <p>Exercises {@link ContainerContentsRoller#rollDoubleChance} at the same probability and salt the
 * real path uses, which is the whole of the decision; what each branch then bakes is book-building,
 * covered by the factories' own tests and the Gate 2 walk.</p>
 */
final class StatsBookCoinFlipTest {

    private static final long WORLD_SEED = 0x0DDBA11L;
    private static final long SALT = ContainerContentsRoller.SALT_STATS_BOOK_KIND;

    /** The flip for one slot, exactly as {@code bakeStatsBook} makes it. */
    private static boolean leaderboard(int carriageIndex, int slot) {
        return ContainerContentsRoller.rollDoubleChance(
            0.5, new BlockPos(slot, -60, carriageIndex), WORLD_SEED, carriageIndex, slot, SALT);
    }

    @Test
    @DisplayName("The same slot always flips the same way")
    void flipIsStablePerSlot() {
        for (int carriage = 0; carriage < 8; carriage++) {
            for (int slot = 0; slot < 27; slot++) {
                assertEquals(leaderboard(carriage, slot), leaderboard(carriage, slot),
                    "carriage " + carriage + " slot " + slot + " re-rolled differently");
            }
        }
    }

    @Test
    @DisplayName("Across many slots it lands near even — the entry really is two entries")
    void flipIsEvenAcrossSlots() {
        int total = 0;
        int leaderboards = 0;
        for (int carriage = -60; carriage < 60; carriage++) {
            for (int slot = 0; slot < 27; slot++) {
                total++;
                if (leaderboard(carriage, slot)) leaderboards++;
            }
        }
        double share = (double) leaderboards / total;
        assertTrue(share > 0.45 && share < 0.55,
            "expected roughly half, got " + leaderboards + "/" + total + " (" + share + ")");
    }

    @Test
    @DisplayName("Neighbouring slots do not all flip alike — a chest holds a mix, not a run")
    void neighbouringSlotsDiffer() {
        boolean sawBoth = false;
        boolean first = leaderboard(3, 0);
        for (int slot = 1; slot < 27; slot++) {
            if (leaderboard(3, slot) != first) {
                sawBoth = true;
                break;
            }
        }
        assertTrue(sawBoth, "every slot in one container flipped the same way");
    }

    @Test
    @DisplayName("A probability of 0 or 1 is honoured exactly, so the flip could be tuned")
    void degenerateProbabilitiesShortCircuit() {
        BlockPos at = new BlockPos(1, -60, 1);
        assertEquals(false, ContainerContentsRoller.rollDoubleChance(0.0, at, WORLD_SEED, 1, 1, SALT));
        assertEquals(true, ContainerContentsRoller.rollDoubleChance(1.0, at, WORLD_SEED, 1, 1, SALT));
    }
}
