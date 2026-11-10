package games.brennan.dungeontrain.util;

import games.brennan.dungeontrain.util.DiskSpace.Alarm;
import games.brennan.dungeontrain.util.DiskSpace.Level;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.util.DiskSpace.GB;
import static games.brennan.dungeontrain.util.DiskSpace.MB;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link DiskSpace}'s thresholds, hysteresis, formatting and warning cadence. */
class DiskSpaceTest {

    @Test
    void levelsFromOk() {
        assertEquals(Level.OK, DiskSpace.level(Level.OK, 10 * GB));
        assertEquals(Level.OK, DiskSpace.level(Level.OK, 5 * GB));
        assertEquals(Level.LOW, DiskSpace.level(Level.OK, 5 * GB - 1));
        assertEquals(Level.LOW, DiskSpace.level(Level.OK, 1 * GB));
        assertEquals(Level.CRITICAL, DiskSpace.level(Level.OK, 1 * GB - 1));
    }

    @Test
    void lowHoldsUntilSixGigabytes() {
        assertEquals(Level.LOW, DiskSpace.level(Level.LOW, 5 * GB + 500 * MB));
        assertEquals(Level.LOW, DiskSpace.level(Level.LOW, 6 * GB));
        assertEquals(Level.OK, DiskSpace.level(Level.LOW, 6 * GB + 1));
    }

    @Test
    void criticalHoldsUntilOneAndAHalfGigabytes() {
        assertEquals(Level.CRITICAL, DiskSpace.level(Level.CRITICAL, 1200 * MB));
        assertEquals(Level.LOW, DiskSpace.level(Level.CRITICAL, 2 * GB));
        assertEquals(Level.LOW, DiskSpace.level(Level.CRITICAL, 5800 * MB));
        assertEquals(Level.OK, DiskSpace.level(Level.CRITICAL, 7 * GB));
    }

    @Test
    void formats() {
        assertEquals("3.2 GB", DiskSpace.format((long) (3.2 * GB)));
        assertEquals("1.0 GB", DiskSpace.format(GB));
        assertEquals("412 MB", DiskSpace.format(412 * MB + 5));
        assertEquals("0 MB", DiskSpace.format(-5));
    }

    @Test
    void lowIsAnnouncedOncePerDrop() {
        Alarm.Step s = Alarm.START.next(4 * GB, 0);
        assertEquals(Level.LOW, s.announce());
        s = s.alarm().next(3 * GB, 60_000);
        assertEquals(Level.OK, s.announce());
        s = s.alarm().next(8 * GB, 120_000);
        assertEquals(Level.OK, s.announce());
        s = s.alarm().next(4 * GB, 180_000);
        assertEquals(Level.LOW, s.announce());
    }

    @Test
    void criticalRepeatsEveryTwoMinutes() {
        Alarm.Step s = Alarm.START.next(500 * MB, 1_000);
        assertEquals(Level.CRITICAL, s.announce());
        s = s.alarm().next(500 * MB, 1_000 + 119_999);
        assertEquals(Level.OK, s.announce());
        s = s.alarm().next(500 * MB, 1_000 + 120_000);
        assertEquals(Level.CRITICAL, s.announce());
    }

    @Test
    void recoveringFromCriticalToLowSaysNothing() {
        Alarm.Step s = Alarm.START.next(500 * MB, 0);
        s = s.alarm().next(3 * GB, 30_000);
        assertEquals(Level.LOW, s.alarm().level());
        assertEquals(Level.OK, s.announce());
    }
}
