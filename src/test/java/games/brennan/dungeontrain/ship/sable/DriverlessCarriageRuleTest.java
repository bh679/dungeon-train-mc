package games.brennan.dungeontrain.ship.sable;

import static games.brennan.dungeontrain.ship.sable.DriverlessCarriageRule.Verdict.KEEP;
import static games.brennan.dungeontrain.ship.sable.DriverlessCarriageRule.Verdict.REAP_MARKED;
import static games.brennan.dungeontrain.ship.sable.DriverlessCarriageRule.Verdict.REAP_OLD_SAVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DriverlessCarriageRule}, at the default 9x7x7 carriage in groups of 3. */
final class DriverlessCarriageRuleTest {

    private static DriverlessCarriageRule.Verdict judge(boolean driver, boolean marked, boolean trainWorld,
                                                        int x, int y, int z) {
        return DriverlessCarriageRule.judge(driver, marked, trainWorld, x, y, z, 9, 7, 7, 3, 5);
    }

    @Test
    @DisplayName("a live group is never reaped, whatever else is true of it")
    void driverWins() {
        assertEquals(KEEP, judge(true, true, true, 37, 7, 7));
    }

    @Test
    @DisplayName("the marker alone is proof, at any size")
    void markedIsReaped() {
        assertEquals(REAP_MARKED, judge(false, true, true, 200, 20, 20));
    }

    @Test
    @DisplayName("an unmarked group of the measured size is reaped: 37x7x7 from a real old save")
    void oldSaveSize() {
        assertEquals(REAP_OLD_SAVE, judge(false, false, true, 37, 7, 7));
        assertEquals(REAP_OLD_SAVE, judge(false, false, true, 27, 7, 7));
    }

    @Test
    @DisplayName("another mod's sub-level is left alone unless its size is exact")
    void otherSizesKept() {
        assertEquals(KEEP, judge(false, false, true, 38, 7, 7));
        assertEquals(KEEP, judge(false, false, true, 26, 7, 7));
        assertEquals(KEEP, judge(false, false, true, 37, 8, 7));
        assertEquals(KEEP, judge(false, false, true, 37, 7, 6));
        assertEquals(KEEP, judge(false, false, true, 1, 1, 1));
    }

    @Test
    @DisplayName("a world with no train keeps every unmarked sub-level, even at carriage size")
    void noTrainWorld() {
        assertEquals(KEEP, judge(false, false, false, 37, 7, 7));
    }

    @Test
    @DisplayName("marking keeps what another mod stored and does not change the original tag")
    void markCopies() {
        CompoundTag original = new CompoundTag();
        original.putInt("other", 3);

        CompoundTag marked = DriverlessCarriageRule.withMark(original);

        assertTrue(DriverlessCarriageRule.isMarked(marked));
        assertEquals(3, marked.getInt("other"));
        assertFalse(DriverlessCarriageRule.isMarked(original));
    }

    @Test
    @DisplayName("a sub-level with no user data is unmarked, and can be marked")
    void nullUserData() {
        assertFalse(DriverlessCarriageRule.isMarked(null));
        assertTrue(DriverlessCarriageRule.isMarked(DriverlessCarriageRule.withMark(null)));
    }
}
