package games.brennan.dungeontrain.client.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PageClockTest {

    @Test
    @DisplayName("with no pause the page time is the whole span")
    void noPause() {
        assertEquals(5_000, PageClock.open(1_000).elapsedMs(6_000));
    }

    @Test
    @DisplayName("a side trip is left out of the page time")
    void pauseAndResume() {
        PageClock c = PageClock.open(1_000).pause(3_000).resume(8_000);
        assertEquals(4_000, c.elapsedMs(10_000), "2 s before, 5 s away, 2 s after");
        assertEquals(6_000, c.pause(11_000).resume(15_000).elapsedMs(16_000), "a second trip adds up: 15 s less 5 s and 4 s away");
    }

    @Test
    @DisplayName("closing while still paused counts only the time before the trip")
    void closeWhilePaused() {
        assertEquals(2_000, PageClock.open(1_000).pause(3_000).elapsedMs(9_000));
    }

    @Test
    @DisplayName("a double pause or a stray resume changes nothing")
    void idempotent() {
        PageClock paused = PageClock.open(0).pause(10);
        assertSame(paused, paused.pause(20));
        PageClock running = PageClock.open(0);
        assertSame(running, running.resume(20));
    }
}
