package games.brennan.dungeontrain.perks.effects.tank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for {@link TankPerks}: the Tank class's numbers, their caps, and rank 0 meaning no effect. */
final class TankPerksTest {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("rank 0 and negative ranks give no effect")
    void inactiveRanks() {
        for (int rank : new int[] {0, -1, -50}) {
            assertEquals(0.0, TankPerks.armourBonus(rank), EPS);
            assertEquals(0.0, TankPerks.hungerRefundFraction(rank), EPS);
            assertEquals(0, TankPerks.healIntervalTicks(rank));
            assertEquals(0L, TankPerks.totemCooldownTicks(rank));
        }
    }

    @Test
    @DisplayName("Armour: +2 points per rank, uncapped")
    void armour() {
        assertEquals(2.0, TankPerks.armourBonus(1), EPS);
        assertEquals(6.0, TankPerks.armourBonus(3), EPS);
        assertEquals(200.0, TankPerks.armourBonus(100), EPS);
    }

    @Test
    @DisplayName("Hunger: 5% per rank, capped at 50%")
    void hunger() {
        assertEquals(0.05, TankPerks.hungerRefundFraction(1), EPS);
        assertEquals(0.25, TankPerks.hungerRefundFraction(5), EPS);
        assertEquals(0.50, TankPerks.hungerRefundFraction(10), EPS);
        assertEquals(0.50, TankPerks.hungerRefundFraction(40), EPS);
    }

    @Test
    @DisplayName("Hunger: spent exhaustion is the rise, or the rise across vanilla's 4.0 wrap")
    void exhaustionSpent() {
        assertEquals(0.1F, TankPerks.exhaustionSpent(1.0F, 1.1F), EPS);
        assertEquals(0.0F, TankPerks.exhaustionSpent(1.0F, 1.0F), EPS);
        // 3.9 + 0.2 = 4.1, vanilla subtracts 4.0 -> reads 0.1; spent is still 0.2
        assertEquals(0.2F, TankPerks.exhaustionSpent(3.9F, 0.1F), EPS);
        // a drop bigger than one wrap is an outside reset, not spending
        assertEquals(0.0F, TankPerks.exhaustionSpent(3.0F, -2.0F), EPS);
        assertEquals(0.0F, TankPerks.exhaustionSpent(Float.NaN, 1.0F), EPS);
    }

    @Test
    @DisplayName("Healing: 60 - 5 * rank seconds, floor 5 s")
    void healing() {
        assertEquals(55, TankPerks.healIntervalSeconds(1));
        assertEquals(30, TankPerks.healIntervalSeconds(6));
        assertEquals(10, TankPerks.healIntervalSeconds(10));
        assertEquals(5, TankPerks.healIntervalSeconds(11));
        assertEquals(5, TankPerks.healIntervalSeconds(99));
        assertEquals(55 * 20, TankPerks.healIntervalTicks(1));
    }

    @Test
    @DisplayName("Totem: 20 - rank minutes, floor 10 min")
    void totem() {
        assertEquals(19, TankPerks.totemCooldownMinutes(1));
        assertEquals(15, TankPerks.totemCooldownMinutes(5));
        assertEquals(10, TankPerks.totemCooldownMinutes(10));
        assertEquals(10, TankPerks.totemCooldownMinutes(50));
        assertEquals(19L * 60L * 20L, TankPerks.totemCooldownTicks(1));
    }
}
