package games.brennan.dungeontrain.perks;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedPotAppleTest {

    private static final int PER = TimedPotApple.TICKS_PER_APPLE;

    @Test
    void tenMinutesOnTheTrainOwesOneApple() {
        assertEquals(12000, PER);
        TimedPotApple.Progress p = TimedPotApple.advance(PER - 20, 0, 19, PER);
        assertEquals(0, p.owed());
        p = TimedPotApple.advance(p.ticks(), p.owed(), 1, PER);
        assertEquals(1, p.owed());
        assertEquals(0, p.ticks(), "the next ten minutes start from zero");
    }

    @Test
    void owedApplesStopAtTheCap() {
        TimedPotApple.Progress p = TimedPotApple.advance(0, 0, PER * 5, PER);
        assertEquals(TimedPotApple.MAX_OWED, p.owed());
        assertEquals(0, p.ticks());
    }

    @Test
    void theClockKeepsRunningWhileAnAppleIsOwed() {
        TimedPotApple.Progress p = TimedPotApple.advance(100, 1, 20, PER);
        assertEquals(120, p.ticks());
        assertEquals(1, p.owed());
    }

    @Test
    void lookedAtOnlyInsideTheConeAndRange() {
        Vec3 look = new Vec3(1, 0, 0);
        double cos = TimedPotApple.SEE_COS_HALF_ANGLE;
        assertTrue(TimedPotApple.inCone(look, new Vec3(5, 0, 1), 12, cos));
        assertFalse(TimedPotApple.inCone(look, new Vec3(5, 0, 6), 12, cos), "50 degrees off");
        assertFalse(TimedPotApple.inCone(look, new Vec3(-5, 0, 0), 12, cos), "behind");
        assertFalse(TimedPotApple.inCone(look, new Vec3(13, 0, 0), 12, cos), "too far");
        assertTrue(TimedPotApple.inCone(look, Vec3.ZERO, 12, cos), "standing on it");
    }
}
