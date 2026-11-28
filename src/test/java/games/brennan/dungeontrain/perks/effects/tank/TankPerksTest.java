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
        assertEquals(1, TankPerks.soupStackSize(0));
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
    @DisplayName("Stack Soups: +2 per point (3, 5, 7 ...), capped at the game's 99")
    void stackSoups() {
        assertEquals(3, TankPerks.soupStackSize(1));
        assertEquals(5, TankPerks.soupStackSize(2));
        assertEquals(21, TankPerks.soupStackSize(10));
        assertEquals(99, TankPerks.soupStackSize(49));
        assertEquals(99, TankPerks.soupStackSize(Integer.MAX_VALUE));
        assertEquals(1, TankPerks.soupStackSize(-3));
    }

    @Test
    @DisplayName("Stack Soups stamp: never below the stack's count or the item's own default")
    void soupStamp() {
        assertEquals(1, TankPerks.soupStamp(0, 1, 1));
        assertEquals(3, TankPerks.soupStamp(1, 1, 1));
        assertEquals(5, TankPerks.soupStamp(0, 5, 1));
        assertEquals(16, TankPerks.soupStamp(2, 1, 16));
        assertEquals(21, TankPerks.soupStamp(10, 1, 16));
        assertEquals(99, TankPerks.soupStamp(200, 1, 1));
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
}
