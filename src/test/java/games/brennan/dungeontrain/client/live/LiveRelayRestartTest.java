package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.RestartNotice;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRelayRestartTest {

    private static final long RELAY_TS = 1_800_000_000_000L; // the relay's clock
    private static final long NOW = 50_000L;                // this client's, deliberately unrelated
    private static final long MIN = 60_000L;

    @AfterEach
    void clear() {
        LiveRelayRestart.reset();
    }

    private static RestartNotice notice(long minutes) {
        return new RestartNotice(RELAY_TS + minutes * MIN, 0, false);
    }

    @Test
    @DisplayName("the window runs from the notice to the relay's estimate plus the grace, on this client's clock")
    void window() {
        assertFalse(LiveRelayRestart.inWindow(NOW));
        LiveRelayRestart.note(notice(5), RELAY_TS, NOW);
        assertTrue(LiveRelayRestart.inWindow(NOW + 1));
        long end = NOW + 5 * MIN + LiveRelayRestart.GRACE_MS;
        assertTrue(LiveRelayRestart.inWindow(end - 1));
        assertFalse(LiveRelayRestart.inWindow(end));
    }

    @Test
    @DisplayName("the 'back' line closes the window and the fast poll")
    void resumedCloses() {
        LiveRelayRestart.note(notice(5), RELAY_TS, NOW);
        LiveRelayRestart.note(new RestartNotice(0, 0, true), RELAY_TS, NOW + 3 * MIN);
        assertFalse(LiveRelayRestart.inWindow(NOW + 3 * MIN + 1));
        assertEquals(3000L, LiveRelayRestart.chatPollMs(NOW + 3 * MIN + 1, 3000L));
    }

    @Test
    @DisplayName("chat polls every second through the countdown, back to normal after the 1")
    void fastPoll() {
        assertEquals(3000L, LiveRelayRestart.chatPollMs(NOW, 3000L));
        LiveRelayRestart.note(notice(5), RELAY_TS, NOW);
        assertEquals(LiveRelayRestart.FAST_POLL_MS, LiveRelayRestart.chatPollMs(NOW + 1000, 3000L));
        LiveRelayRestart.note(new RestartNotice(0, 3, false), RELAY_TS, NOW + 6000);
        assertEquals(LiveRelayRestart.FAST_POLL_MS, LiveRelayRestart.chatPollMs(NOW + 6500, 3000L));
        LiveRelayRestart.note(new RestartNotice(0, 1, false), RELAY_TS, NOW + 8000);
        assertEquals(3000L, LiveRelayRestart.chatPollMs(NOW + 8500, 3000L));
        assertTrue(LiveRelayRestart.inWindow(NOW + 8500), "the window outlives the countdown");
        assertEquals(3000L, LiveRelayRestart.chatPollMs(NOW + LiveRelayRestart.FAST_MS + 1, 3000L));
    }

    @Test
    @DisplayName("joining mid-countdown still opens a window (the default estimate)")
    void countdownAlone() {
        LiveRelayRestart.note(new RestartNotice(0, 2, false), RELAY_TS, NOW);
        assertTrue(LiveRelayRestart.inWindow(NOW + LiveRelayRestart.DEFAULT_ESTIMATE_MS));
    }

    @Test
    @DisplayName("the minutes shown are the relay's own estimate, at least 1")
    void minutes() {
        assertEquals(5L, LiveRelayRestart.minutes(notice(5), RELAY_TS));
        assertEquals(12L, LiveRelayRestart.minutes(notice(12), RELAY_TS));
        assertEquals(1L, LiveRelayRestart.minutes(new RestartNotice(RELAY_TS + 1000, 0, false), RELAY_TS));
        assertEquals(5L, LiveRelayRestart.minutes(notice(7), 0L), "no line timestamp: the default");
    }

    @Test
    @DisplayName("each line becomes the player's language: notice, 'Restarting in 3', then plain 2 and 1, then back")
    void text() {
        TranslatableContents n = (TranslatableContents) LiveRelayRestart.body(notice(5), RELAY_TS).getContents();
        assertEquals(LiveRelayRestart.NOTICE_KEY, n.getKey());
        assertArrayEquals(new Object[] {5L}, n.getArgs());
        TranslatableContents three = (TranslatableContents) LiveRelayRestart.body(new RestartNotice(0, 3, false), RELAY_TS).getContents();
        assertEquals(LiveRelayRestart.COUNTDOWN_KEY, three.getKey());
        assertArrayEquals(new Object[] {3}, three.getArgs());
        assertEquals("2", ((PlainTextContents) LiveRelayRestart.body(new RestartNotice(0, 2, false), RELAY_TS).getContents()).text());
        assertEquals("1", ((PlainTextContents) LiveRelayRestart.body(new RestartNotice(0, 1, false), RELAY_TS).getContents()).text());
        TranslatableContents back = (TranslatableContents) LiveRelayRestart.body(new RestartNotice(0, 0, true), RELAY_TS).getContents();
        assertEquals(LiveRelayRestart.RESUMED_KEY, back.getKey());
    }
}
