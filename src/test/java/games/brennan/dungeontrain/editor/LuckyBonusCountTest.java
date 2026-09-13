package games.brennan.dungeontrain.editor;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the Luck → bonus-count rule behind {@link LuckyBonusRoller#bonusCountFor}:
 * no bonus without Luck, Luck I rolls 3–7, Luck II and up rolls 5–10. The roll itself
 * ({@code preRoll}) needs the item registry and is covered by the Gate 2 in-game flow.
 */
final class LuckyBonusCountTest {

    private static final int SAMPLES = 2000;

    private static Set<Integer> sample(float luck) {
        RandomSource random = RandomSource.create(1234L);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) seen.add(LuckyBonusRoller.bonusCountFor(luck, random));
        return seen;
    }

    @Test
    @DisplayName("no luck, or Bad Luck, never adds anything")
    void noLuck_zero() {
        assertEquals(Set.of(0), sample(0f));
        assertEquals(Set.of(0), sample(-1f));
    }

    @Test
    @DisplayName("Luck I rolls 3–7 and reaches every value")
    void luckOne_threeToSeven() {
        assertEquals(Set.of(3, 4, 5, 6, 7), sample(1f));
    }

    @Test
    @DisplayName("Luck II and up rolls 5–10 and reaches every value")
    void luckTwo_fiveToTen() {
        assertEquals(Set.of(5, 6, 7, 8, 9, 10), sample(2f));
        assertEquals(Set.of(5, 6, 7, 8, 9, 10), sample(5f));
    }

    @Test
    @DisplayName("never exceeds the pre-rolled candidate count")
    void neverAboveMax() {
        RandomSource random = RandomSource.create(99L);
        for (int i = 0; i < SAMPLES; i++) {
            assertTrue(LuckyBonusRoller.bonusCountFor(10f, random) <= LuckyBonusRoller.MAX_LUCKY_BONUS);
        }
    }
}
