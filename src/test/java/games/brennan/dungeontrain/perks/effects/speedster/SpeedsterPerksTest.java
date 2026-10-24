package games.brennan.dungeontrain.perks.effects.speedster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Speedster perk maths: rank 0 is vanilla, the tree's numbers hold at low ranks, and nothing goes negative. */
final class SpeedsterPerksTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("rank 0 (and negative ranks) leave everything at vanilla")
    void rankZeroIsVanilla() {
        for (int r : new int[] {0, -1, -7}) {
            assertEquals(1.0, SpeedsterPerks.hasteMultiplier(r), EPS);
            assertEquals(1.0, SpeedsterPerks.sprintHungerFactor(r), EPS);
            assertEquals(0.0, SpeedsterPerks.sprintSpeedBonus(r), EPS);
            assertEquals(0.0, SpeedsterPerks.sprintJumpImpulse(r), EPS);
            assertEquals(1.0, SpeedsterPerks.windFactor(r), EPS);
        }
    }

    @Test
    @DisplayName("Haste: +20% mining speed per rank")
    void haste() {
        assertEquals(1.2, SpeedsterPerks.hasteMultiplier(1), EPS);
        assertEquals(1.6, SpeedsterPerks.hasteMultiplier(3), EPS);
    }

    @Test
    @DisplayName("Sprint Hunger: 10% less at rank 1, 5% more per further rank, floored at free")
    void sprintHunger() {
        assertEquals(0.90, SpeedsterPerks.sprintHungerFactor(1), EPS);
        assertEquals(0.85, SpeedsterPerks.sprintHungerFactor(2), EPS);
        assertEquals(0.80, SpeedsterPerks.sprintHungerFactor(3), EPS);
        assertEquals(0.0, SpeedsterPerks.sprintHungerFactor(19), EPS);
        assertEquals(0.0, SpeedsterPerks.sprintHungerFactor(50), EPS);
    }

    @Test
    @DisplayName("Sprint Speed: +5% per rank as a total multiplier")
    void sprintSpeed() {
        assertEquals(0.05, SpeedsterPerks.sprintSpeedBonus(1), EPS);
        assertEquals(0.15, SpeedsterPerks.sprintSpeedBonus(3), EPS);
    }

    @Test
    @DisplayName("Sprint Jump: the impulse carries 0.1 block further per rank under air drag")
    void sprintJump() {
        double impulse = SpeedsterPerks.sprintJumpImpulse(1);
        assertTrue(impulse > 0.0);
        // Distance covered by a horizontal impulse over a 12-tick jump with 0.91 air drag per tick.
        double carried = 0.0;
        double v = impulse;
        for (int tick = 0; tick < 12; tick++) {
            v *= 0.91;
            carried += v;
        }
        assertEquals(0.1, carried, 0.005);
        assertEquals(3 * impulse, SpeedsterPerks.sprintJumpImpulse(3), EPS);
    }

    @Test
    @DisplayName("Wind Resistance: 10% less push per rank, immune from rank 10")
    void windResistance() {
        assertEquals(0.9, SpeedsterPerks.windFactor(1), EPS);
        assertEquals(0.5, SpeedsterPerks.windFactor(5), EPS);
        assertEquals(0.0, SpeedsterPerks.windFactor(10), EPS);
        assertEquals(0.0, SpeedsterPerks.windFactor(12), EPS);
    }
}
