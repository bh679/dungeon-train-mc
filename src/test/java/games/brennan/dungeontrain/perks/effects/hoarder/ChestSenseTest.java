package games.brennan.dungeontrain.perks.effects.hoarder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chest Sense's ladder (design 2026-10-29, interval 2026-11-28); the scan needs a level (and Sable), so it is covered in-game. */
final class ChestSenseTest {

    @Test
    @DisplayName("nothing at 0 points")
    void offAtZero() {
        assertEquals(0.0, ChestSense.range(0));
        assertEquals(0.0, ChestSense.everySeconds(0));
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
    @DisplayName("interval hits the anchors: 180 s at 1, 40 s at 10, 10 s at 40")
    void intervalAnchors() {
        assertEquals(180.0, ChestSense.everySeconds(1));
        assertEquals(40.0, ChestSense.everySeconds(10));
        assertEquals(10.0, ChestSense.everySeconds(40));
    }

    @Test
    @DisplayName("linear between the anchors, in whole seconds")
    void intervalBetween() {
        double[] want = {180, 164, 149, 133, 118, 102, 87, 71, 56, 40};
        for (int r = 1; r <= want.length; r++) assertEquals(want[r - 1], ChestSense.everySeconds(r), "point " + r);
        assertEquals(35.0, ChestSense.everySeconds(15));
        assertEquals(30.0, ChestSense.everySeconds(20));
        assertEquals(11.0, ChestSense.everySeconds(39));
    }

    @Test
    @DisplayName("never longer as points go up, and 10 s from 40 on")
    void intervalFloor() {
        for (int r = 2; r <= 200; r++) assertTrue(ChestSense.everySeconds(r) <= ChestSense.everySeconds(r - 1), "point " + r);
        assertEquals(10.0, ChestSense.everySeconds(41));
        assertEquals(10.0, ChestSense.everySeconds(200));
    }

    @Test
    @DisplayName("the glow lasts 15 s at every point; ticks follow the seconds")
    void glow() {
        assertEquals(15 * 20, ChestSense.glowTicks());
        assertEquals(180 * 20, ChestSense.intervalTicks(1));
        assertEquals(10 * 20, ChestSense.intervalTicks(40));
    }
}
