package games.brennan.dungeontrain.client.live.life;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveLifeTrackerTest {

    private static final long T0 = 1_800_000_000_000L;

    @BeforeEach
    void reset() {
        LiveLifeTracker.clearForTest();
    }

    @Test
    void eachLifeKeepsItsOwnCountersAndANewLifeStartsEmpty() {
        LiveLifeTracker.update("a", s -> s.stintStarted(T0, 10), true, T0);
        LiveLifeTracker.update("a", s -> s.moment(true), false, T0);
        assertEquals(1, LiveLifeTracker.get("a").stints());
        assertEquals(1, LiveLifeTracker.get("a").photos());
        assertFalse(LiveLifeTracker.get("b").broadcast());
    }

    @Test
    void onlyTheLatestLivesAreKept() {
        for (int i = 0; i < LiveLifeTracker.KEEP_LIVES + 2; i++) {
            LiveLifeTracker.update("life" + i, s -> s.stintStarted(T0, 10), false, T0);
        }
        assertFalse(LiveLifeTracker.get("life0").broadcast());
        assertTrue(LiveLifeTracker.get("life" + (LiveLifeTracker.KEEP_LIVES + 1)).broadcast());
    }

    @Test
    void theSaveFileRoundTripsAndAMalformedOneReadsAsEmpty() {
        LiveLifeStats s = LiveLifeStats.empty("life").stintStarted(T0, 10).heartbeat(T0 + 5000, 7)
            .chat(T0, "Rook", "hi").reacted(4, 2).voteClosed("p", true)
            .crowd(List.of(new LiveLifeStats.Watcher("Moth", null, "game", 60)));
        List<LiveLifeStats> back = LiveLifeTracker.parse(LiveLifeTracker.serialize(List.of(s)));
        assertEquals(List.of(s), back);
        assertEquals(List.of(), LiveLifeTracker.parse("{not json"));
        assertEquals(List.of(), LiveLifeTracker.parse("null"));
    }
}
