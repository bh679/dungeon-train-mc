package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EjectLogThrottleTest {

    private static final long W = EjectLogThrottle.WINDOW_TICKS;

    @Test
    void firstEjectionLogsWithNoRepeats() {
        EjectLogThrottle t = new EjectLogThrottle();
        assertEquals(0, t.record(7, 1000));
    }

    @Test
    void repeatsInsideTheWindowAreSuppressedThenCounted() {
        EjectLogThrottle t = new EjectLogThrottle();
        t.record(7, 1000);
        for (long tick = 1020; tick < 1000 + W; tick += 20) {
            assertEquals(-1, t.record(7, tick));
        }
        assertEquals(29, t.record(7, 1000 + W)); // 1020..1580 every 20 ticks
        assertEquals(-1, t.record(7, 1000 + W + 20));
    }

    @Test
    void otherMobsAreThrottledSeparately() {
        EjectLogThrottle t = new EjectLogThrottle();
        t.record(7, 1000);
        assertEquals(0, t.record(8, 1001));
        assertEquals(-1, t.record(7, 1002));
    }

    @Test
    void mobsThatStopComingBackArePruned() {
        EjectLogThrottle t = new EjectLogThrottle();
        t.record(7, 1000);
        t.record(8, 1000);
        t.record(9, 1000 + 2 * W);
        assertEquals(1, t.size());
        assertEquals(0, t.record(7, 1000 + 2 * W + 1)); // logs as new again
    }
}
