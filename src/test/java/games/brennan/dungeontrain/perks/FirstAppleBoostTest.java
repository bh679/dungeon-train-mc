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
}
