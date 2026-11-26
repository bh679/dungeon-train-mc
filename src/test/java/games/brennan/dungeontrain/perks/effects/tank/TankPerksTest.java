package games.brennan.dungeontrain.perks.effects.tank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TankPerksTest {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("rank 0 does nothing")
    void rankZeroIsInert() {
        assertEquals(0.0, TankPerks.armourBonus(0), EPS);
        assertEquals(0.0, TankPerks.hungerRefundFraction(0), EPS);
        assertEquals(0, TankPerks.healIntervalTicks(0));
        assertEquals(0L, TankPerks.totemCooldownTicks(0));
    }

    @Test
    @DisplayName("Armour: +2 per point, uncapped")
    void armour() {
        assertEquals(2.0, TankPerks.armourBonus(1), EPS);
        assertEquals(20.0, TankPerks.armourBonus(10), EPS);
    }

    @Test
    @DisplayName("Hunger: drain x0.95 per point (-5%, -10%, ... -40% at 10), never zero")
    void hunger() {
        assertEquals(0.05, TankPerks.hungerRefundFraction(1), EPS);
        assertEquals(0.0975, TankPerks.hungerRefundFraction(2), EPS);
        assertEquals(0.40, TankPerks.hungerRefundFraction(10), 0.005);
        assertTrue(TankPerks.hungerRefundFraction(200) < 1.0);
    }

    @Test
    @DisplayName("Healing: 120 s at 1 point, x0.85 per point after, no floor")
    void healing() {
        assertEquals(120.0, TankPerks.healIntervalSeconds(1), EPS);
        assertEquals(102.0, TankPerks.healIntervalSeconds(2), EPS);
        assertEquals(63, Math.round(TankPerks.healIntervalSeconds(5)));
        assertEquals(28, Math.round(TankPerks.healIntervalSeconds(10)));
        assertEquals(2400, TankPerks.healIntervalTicks(1));
        assertTrue(TankPerks.healIntervalTicks(200) >= 1);
    }

    @Test
    @DisplayName("Totem: 30 min x0.93 per point (27.9 at 1, 14.5 at 10)")
    void totem() {
        assertEquals(27.9, TankPerks.totemCooldownMinutes(1), EPS);
        assertEquals(14.5, TankPerks.totemCooldownMinutes(10), 0.05);
        assertEquals(Math.round(27.9 * 60 * 20), TankPerks.totemCooldownTicks(1));
    }

    @Test
    @DisplayName("exhaustion spent reads vanilla's wrap at 4.0 and ignores outside resets")
    void exhaustionSpent() {
        assertEquals(0.5F, TankPerks.exhaustionSpent(1.0F, 1.5F), EPS);
        assertEquals(0.5F, TankPerks.exhaustionSpent(3.8F, 0.3F), EPS);
        assertEquals(0.0F, TankPerks.exhaustionSpent(3.8F, -1.0F), EPS);
        assertEquals(0.0F, TankPerks.exhaustionSpent(Float.NaN, 1.0F), EPS);
    }
}
