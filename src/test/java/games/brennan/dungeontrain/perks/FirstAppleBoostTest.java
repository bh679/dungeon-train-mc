package games.brennan.dungeontrain.perks;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstAppleBoostTest {

    @Test
    void groupHoldsTwoApplesCountingTheNaturalOne() {
        assertEquals(2, FirstAppleBoost.extrasFor(false));
        assertEquals(1, FirstAppleBoost.extrasFor(true));
    }

    @Test
    void leftBehindOnlyAfterAWholeGroupEitherWay() {
        assertFalse(FirstAppleBoost.leftBehind(10, 10, 3));
        assertFalse(FirstAppleBoost.leftBehind(12, 10, 3));
        assertTrue(FirstAppleBoost.leftBehind(13, 10, 3));
        assertFalse(FirstAppleBoost.leftBehind(8, 10, 3));
        assertTrue(FirstAppleBoost.leftBehind(7, 10, 3));
    }

    @Test
    void leftBehindTreatsAnEmptyGroupSizeAsOne() {
        assertFalse(FirstAppleBoost.leftBehind(5, 5, 0));
        assertTrue(FirstAppleBoost.leftBehind(6, 5, 0));
    }

    @Test
    void takenWhenTheChestHoldsFewerApples() {
        assertFalse(FirstAppleBoost.taken(1, 1));
        assertFalse(FirstAppleBoost.taken(2, 1));
        assertTrue(FirstAppleBoost.taken(0, 1));
        assertTrue(FirstAppleBoost.taken(1, 2));
    }

    @Test
    void profileFlagIsSetWithoutTouchingTheRest() {
        CompoundTag root = new CompoundTag();
        root.putInt("Other", 7);
        assertFalse(PerkProfileStore.firstAppleFoundIn(root));
        CompoundTag next = PerkProfileStore.withFirstAppleFound(root);
        assertTrue(PerkProfileStore.firstAppleFoundIn(next));
        assertFalse(PerkProfileStore.firstAppleFoundIn(root), "the old root is unchanged");
        assertEquals(7, next.getInt("Other"));
    }

    @Test
    void owedAddsWhatEarlierGroupsCarriedUpToTheCap() {
        assertEquals(2, FirstAppleBoost.owedWith(false, 0));
        assertEquals(3, FirstAppleBoost.owedWith(true, 2));
        assertEquals(FirstAppleBoost.MAX_EXTRAS, FirstAppleBoost.owedWith(false, 9));
        assertEquals(2, FirstAppleBoost.owedWith(false, -3), "a bad saved carry counts as none");
    }

    @Test
    void whatDoesNotFitCarriesOn() {
        assertEquals(2, FirstAppleBoost.carryAfter(2, 0), "a group with nowhere to put them passes both on");
        assertEquals(1, FirstAppleBoost.carryAfter(2, 1));
        assertEquals(0, FirstAppleBoost.carryAfter(2, 2));
        assertEquals(0, FirstAppleBoost.carryAfter(1, 3));
    }

    @Test
    void naturalPotPickIsSeededAndInRange() {
        for (int pIdx = -20; pIdx < 20; pIdx++) {
            int pick = CosmicAppleDrops.potIndex(12345L, pIdx, 3);
            assertTrue(pick >= 0 && pick < 3);
            assertEquals(pick, CosmicAppleDrops.potIndex(12345L, pIdx, 3));
        }
    }
}
