package games.brennan.dungeontrain.perks.effects.hoarder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Chest Sense's ladder (design 2026-10-29); the scan needs a level (and Sable), so it is covered in-game. */
final class ChestSenseTest {

    @Test
    @DisplayName("nothing at 0 points")
    void offAtZero() {
        assertEquals(0.0, ChestSense.range(0));
        assertEquals(0.0, ChestSense.everyMinutes(0));
    }

    @Test
    @DisplayName("range 5 at 1 point, +1 a point, uncapped")
    void range() {
        assertEquals(5.0, ChestSense.range(1));
        assertEquals(13.0, ChestSense.range(9));
        assertEquals(14.0, ChestSense.range(10));
        assertEquals(54.0, ChestSense.range(50));
    }

    @Test
    @DisplayName("interval follows the hand-set table to 9 points")
    void intervalTable() {
        double[] want = {6, 5, 4.8, 4.6, 4.5, 4.3, 4.2, 4.1, 4};
        for (int r = 1; r <= want.length; r++) assertEquals(want[r - 1], ChestSense.everyMinutes(r), 1e-9, "point " + r);
    }

    @Test
    @DisplayName("then 0.1 min less a point, reaching the 1 min floor at 39 and staying there")
    void intervalTail() {
        assertEquals(3.9, ChestSense.everyMinutes(10), 1e-9);
        assertEquals(1.1, ChestSense.everyMinutes(38), 1e-9);
        assertEquals(1.0, ChestSense.everyMinutes(39), 1e-9);
        assertEquals(1.0, ChestSense.everyMinutes(200), 1e-9);
    }

    @Test
    @DisplayName("the glow lasts 15 s at every point")
    void glow() {
        assertEquals(15 * 20, ChestSense.glowTicks());
        assertEquals(6 * 60 * 20, ChestSense.intervalTicks(1));
    }
}
