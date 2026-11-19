package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapSlot#matches}: a nap only wakes from the slot its own run belongs to. */
final class NapSlotTest {

    private static NapSnapshot nap(boolean cheated, String difficulty) {
        return new NapSnapshot(UUID.randomUUID(), 1L, "0", "World 1", new CompoundTag(),
            0, 0, "", 1, 0, cheated, difficulty);
    }

    @Test
    @DisplayName("a nap matches the slot it was saved to")
    void matchesOwnSlot() {
        assertTrue(NapSlot.matches(NapSlot.key(false, Difficulty.EASY), nap(false, "easy")));
        assertTrue(NapSlot.matches(NapSlot.key(true, Difficulty.HARD), nap(true, "hard")));
    }

    @Test
    @DisplayName("an Easy nap renamed into the Hard slot is refused")
    void renamedDifficultyRefused() {
        assertFalse(NapSlot.matches(NapSlot.key(false, Difficulty.HARD), nap(false, "easy")));
    }

    @Test
    @DisplayName("a Free Play nap renamed into a Survival slot is refused, and the other way")
    void freePlayMismatchRefused() {
        assertFalse(NapSlot.matches(NapSlot.key(false, Difficulty.HARD), nap(true, "hard")));
        assertFalse(NapSlot.matches(NapSlot.key(true, Difficulty.HARD), nap(false, "hard")));
    }

    @Test
    @DisplayName("an old nap without a difficulty is judged on its Free Play flag; a garbled one is refused")
    void emptyAndUnknownDifficulty() {
        assertTrue(NapSlot.matches(NapSlot.key(false, Difficulty.HARD), nap(false, "")));
        assertFalse(NapSlot.matches(NapSlot.key(true, Difficulty.HARD), nap(false, "")));
        assertFalse(NapSlot.matches(NapSlot.key(false, Difficulty.HARD), nap(false, "nightmare")));
    }
}
