package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LiveWatchTallyTest {

    @Test
    @DisplayName("a watch report earns what it says, capped at the relay's gap and by real time since the last")
    void creditIsBounded() {
        long t = 1_000_000L;
        assertEquals(15, LiveWatchTally.credit(null, 15, t), "first report: as stated");
        assertEquals(30, LiveWatchTally.credit(null, 500, t), "never past the gap cap");
        assertEquals(0, LiveWatchTally.credit(null, -4, t), "nothing negative");
        assertEquals(15, LiveWatchTally.credit(t - 15_000, 15, t), "a real 15 s poll gap");
        assertEquals(2, LiveWatchTally.credit(t - 2_000, 15, t), "a burst earns only the time that passed");
        assertEquals(0, LiveWatchTally.credit(t, 15, t), "a duplicate earns nothing");
    }
}
