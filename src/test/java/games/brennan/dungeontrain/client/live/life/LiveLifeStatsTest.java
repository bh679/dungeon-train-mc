package games.brennan.dungeontrain.client.live.life;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveLifeStatsTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    @Test
    void onAirTimeSumsEveryStintOfTheLifeAndCountsTheRunningOne() {
        LiveLifeStats s = LiveLifeStats.empty("life")
            .stintStarted(T0, 10).stintEnded(T0 + 30 * MIN, "left")
            .stintStarted(T0 + 60 * MIN, 10);
        assertEquals(2, s.stints());
        assertTrue(s.broadcast());
        assertTrue(s.live());
        assertEquals(42 * MIN, s.onAirMs(T0 + 72 * MIN));
        LiveLifeStats ended = s.stintEnded(T0 + 75 * MIN, "died");
        assertEquals(45 * MIN, ended.onAirMs(T0 + 999 * MIN));
        assertEquals("died", ended.lastStopReason());
        assertEquals(T0 + 75 * MIN, ended.lastStopMs());
    }

    @Test
    void aStintACrashLeftRunningClosesAtItsLastHeartbeat() {
        LiveLifeStats s = LiveLifeStats.empty("life").stintStarted(T0, 10).heartbeat(T0 + 5 * MIN, 3);
        LiveLifeStats closed = s.closeStale();
        assertFalse(closed.live());
        assertEquals(5 * MIN, closed.onAirMs(T0 + 99 * MIN));
        assertEquals(3, closed.peakViewers());
    }

    @Test
    void peakKeepsTheMostAndIgnoresAMissingCount() {
        LiveLifeStats s = LiveLifeStats.empty("life").stintStarted(T0, 10)
            .heartbeat(T0 + 1, 4).heartbeat(T0 + 2, 9).heartbeat(T0 + 3, -1).heartbeat(T0 + 4, 2);
        assertEquals(9, s.peakViewers());
    }

    @Test
    void chatCountsLinesWhoSaidMostAndTheFinalMinute() {
        LiveLifeStats s = LiveLifeStats.empty("life")
            .chat(T0, "Rook", "hi").chat(T0 + MIN, "Moth", "careful")
            .chat(T0 + 2 * MIN, "Rook", "the bridge").chat(T0 + 3 * MIN, "Kestrel", "gg");
        assertEquals(4, s.chatLines());
        assertEquals("Rook", s.mostSaid());
        List<LiveLifeStats.ChatMoment> last = s.finalMinute(T0 + 3 * MIN + 10_000, 2);
        assertEquals(1, last.size());
        assertEquals("Kestrel", last.get(0).author());
        assertEquals(2, s.finalMinute(T0 + 3 * MIN, 2).size(), "two lines inside the minute before the end");
        assertEquals("", LiveLifeStats.empty("x").mostSaid());
    }

    @Test
    void recentChatIsBounded() {
        LiveLifeStats s = LiveLifeStats.empty("life");
        for (int i = 0; i < 20; i++) s = s.chat(T0 + i, "a" + i, "line " + i);
        assertEquals(LiveLifeStats.RECENT_CHAT, s.recentChat().size());
        assertEquals("line 19", s.recentChat().get(LiveLifeStats.RECENT_CHAT - 1).text());
    }

    @Test
    void votesCountOncePerPollAndReactionsKeepTheLatestTally() {
        LiveLifeStats s = LiveLifeStats.empty("life")
            .voteClosed("p1", true).voteClosed("p1", true).voteClosed("p2", false);
        assertEquals(1, s.votesWon());
        assertEquals(2, s.votesClosed());
        s = s.reacted(10, 2).reacted(10, 5).reacted(11, 1);
        assertEquals(6, s.reactionTotal());
    }

    @Test
    void dyingOnlyCountsOnAirAndTheTakerIsKept() {
        LiveLifeStats off = LiveLifeStats.empty("life").stintStarted(T0, 10).stintEnded(T0 + 1, "left");
        assertSame(off, off.died());
        LiveLifeStats on = LiveLifeStats.empty("life").stintStarted(T0, 10).died().taken("Rook", false);
        assertTrue(on.diedOnAir());
        assertEquals("Rook", on.takenBy());
        assertFalse(on.takenWhileAlive());
        assertSame(on, on.taken(" ", true));
    }
}
