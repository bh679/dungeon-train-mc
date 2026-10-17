package games.brennan.dungeontrain.client.live.life;

import games.brennan.dungeontrain.client.live.life.LiveDeathStory.Pitch;
import games.brennan.dungeontrain.client.live.life.LiveDeathStory.Story;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveDeathStoryTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 86_400_000L;

    private static LiveLifeStats watched() {
        return LiveLifeStats.empty("life").stintStarted(NOW - 3_600_000, 10).heartbeat(NOW - 60_000, 41);
    }

    private static LiveRewards on(String... ids) {
        return new LiveRewards(java.util.Arrays.stream(ids)
            .map(id -> new LiveRewards.Reward(id, NOW + DAY, "Kestrel", List.of())).toList());
    }

    @Test
    void diedOnAirAsksForQuickerStreams() {
        LiveLifeStats s = watched().died();
        assertEquals(Story.TO_THE_END, LiveDeathStory.story(s));
        assertEquals(Pitch.QUICKER_ASK, LiveDeathStory.pitch(s, LiveRewards.NONE, NOW));
    }

    @Test
    void endedEarlyAsksForReplaysUnlessTheyAreOn() {
        LiveLifeStats s = watched().stintEnded(NOW - 30_000, "left");
        assertEquals(Story.ENDED_EARLY, LiveDeathStory.story(s));
        assertEquals(Pitch.REPLAYS_ASK, LiveDeathStory.pitch(s, LiveRewards.NONE, NOW));
        assertEquals(Pitch.QUICKER_ASK, LiveDeathStory.pitch(s, on(LiveRewards.REPLAYS_KEPT), NOW));
    }

    @Test
    void takenMidLifeAsksForTwoStreams() {
        LiveLifeStats s = watched().taken("Rook", true).stintEnded(NOW, "removed");
        assertEquals(Story.TAKEN, LiveDeathStory.story(s));
        assertEquals(Pitch.TWO_STREAMS_ASK, LiveDeathStory.pitch(s, on(LiveRewards.QUICKER_STREAMS), NOW));
    }

    @Test
    void takenAfterTheDeathIsStillToTheEnd() {
        LiveLifeStats s = watched().died().taken("Rook", false);
        assertEquals(Story.TO_THE_END, LiveDeathStory.story(s));
    }

    @Test
    void streamedToNobodyMakesNoMoneyAsk() {
        LiveLifeStats s = LiveLifeStats.empty("life").stintStarted(NOW, 10).died();
        assertEquals(Story.NOBODY, LiveDeathStory.story(s));
        assertEquals(Pitch.NOBODY, LiveDeathStory.pitch(s, on(LiveRewards.QUICKER_STREAMS), NOW));
    }

    @Test
    void quickerStreamsOnThanksTheBuyerUntilItLapses() {
        LiveLifeStats s = watched().died();
        assertEquals(Pitch.QUICKER_THANKS, LiveDeathStory.pitch(s, on(LiveRewards.QUICKER_STREAMS), NOW));
        assertEquals(Pitch.QUICKER_ASK, LiveDeathStory.pitch(s, on(LiveRewards.QUICKER_STREAMS), NOW + 2 * DAY));
    }

    @Test
    void theDelayFollowsTheSegmentLength() {
        assertEquals(31, LiveDeathStory.delaySeconds(10, false));
        assertEquals(7, LiveDeathStory.delaySeconds(2, false));
        assertEquals(31, LiveDeathStory.delaySeconds(0, false));
        assertEquals(LiveDeathStory.QUICKER_DELAY_S, LiveDeathStory.delaySeconds(10, true));
    }
}
