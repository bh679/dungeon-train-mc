package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A relay "live" answer counts only while fresh; stale or never-fetched reads as not live. */
final class ServerLiveStatusTest {

    @Test
    @DisplayName("live only while the last relay answer is fresh")
    void freshness() {
        long at = 1_000_000L;
        assertTrue(ServerLiveStatus.isFresh(true, at, at + 1_000L));
        assertFalse(ServerLiveStatus.isFresh(true, at, at + ServerLiveStatus.STALE_MS));
        assertFalse(ServerLiveStatus.isFresh(false, at, at + 1_000L));
        assertFalse(ServerLiveStatus.isFresh(true, 0L, 1_000L));
    }

    @Test
    @DisplayName("someone else is live: another local streamer, or a fresh relay answer not naming you")
    void someoneElse() {
        java.util.UUID me = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        assertTrue(ServerLiveStatus.someoneElseLive(other, me, false, null, false, null, "Dev"), "another streamer on this server");
        assertFalse(ServerLiveStatus.someoneElseLive(me, me, false, null, false, null, "Dev"), "only you, locally");
        assertTrue(ServerLiveStatus.someoneElseLive(null, me, true, "Alex", false, null, "Dev"), "relay names someone else");
        assertTrue(ServerLiveStatus.someoneElseLive(null, me, true, null, false, null, "Dev"), "relay gives no name");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, true, "dev", false, null, "Dev"), "relay names you");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, false, "Alex", false, null, "Dev"), "stale relay answer");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, false, null, false, null, "Dev"), "nobody live");
    }

    @Test
    @DisplayName("nobody live only when known: no local streamer and a fresh not-live relay answer")
    void nobodyLive() {
        long at = 1_000_000L;
        assertTrue(ServerLiveStatus.nobodyLive(null, false, at, at + 1_000L));
        assertFalse(ServerLiveStatus.nobodyLive(java.util.UUID.randomUUID(), false, at, at + 1_000L), "a streamer here");
        assertFalse(ServerLiveStatus.nobodyLive(null, true, at, at + 1_000L), "relay says live");
        assertFalse(ServerLiveStatus.nobodyLive(null, false, at, at + ServerLiveStatus.STALE_MS), "stale answer");
        assertFalse(ServerLiveStatus.nobodyLive(null, false, 0L, 1_000L), "never asked");
    }

    @Test
    void secondStreamCountsAsSomeoneElse() {
        java.util.UUID me = java.util.UUID.randomUUID();
        assertTrue(ServerLiveStatus.someoneElseLive(null, me, true, "Dev", true, "Alex", "Dev"), "you hold the first, Alex the second");
        assertTrue(ServerLiveStatus.someoneElseLive(null, me, true, "Dev", true, null, "Dev"), "nameless second");
        assertTrue(ServerLiveStatus.someoneElseLive(null, me, true, "Alex", true, "dev", "Dev"), "first is Alex");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, false, "Alex", true, "Dev", "Dev"), "only your own second stream");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, true, "Dev", false, "Alex", "Dev"), "stale second");
    }

    @Test
    void secondStreamLiveNeedsAFreshRelayAnswer() {
        long at = 1_000_000L, now = at + 1_000L, stale = at + ServerLiveStatus.STALE_MS;
        long asked = at - 200L, both = at - 5_000L;
        assertTrue(ServerLiveStatus.secondStreamLive(0, 0L, true, true, at, asked, now), "relay says second");
        assertFalse(ServerLiveStatus.secondStreamLive(0, 0L, true, false, at, asked, now), "relay says no second");
        assertFalse(ServerLiveStatus.secondStreamLive(2, both, false, false, 0L, 0L, now), "two here, relay never answered");
        assertFalse(ServerLiveStatus.secondStreamLive(2, at + 500L, true, false, at, asked, stale), "two here, relay answer stale");
        assertFalse(ServerLiveStatus.secondStreamLive(2, at + 500L, false, false, at, asked, now), "two here, relay says nobody live");
        assertFalse(ServerLiveStatus.secondStreamLive(1, 0L, true, true, at, asked, stale), "failed polls: stale after STALE_MS");
    }

    @Test
    @DisplayName("two local streamers bridge only until the relay answers without a second stream")
    void localPairBridgesOnlyUntilTheRelayAnswers() {
        long at = 1_000_000L, now = at + 1_000L;
        // The second claim landed after the poll behind the last answer was sent: the relay cannot know yet.
        assertTrue(ServerLiveStatus.secondStreamLive(2, at - 100L, true, false, at, at - 200L, now), "poll sent before the claim");
        // A poll sent after both started came back with no second: the pair is stale, Stream 2 goes.
        assertFalse(ServerLiveStatus.secondStreamLive(2, at - 5_000L, true, false, at, at - 200L, now), "answered since, no second");
        assertFalse(ServerLiveStatus.secondStreamLive(2, at - 200L, true, false, at, at - 200L, now), "asked the same instant");
        // Fewer than two streamers here: no start time for a pair, never bridged.
        assertFalse(ServerLiveStatus.secondStreamLive(1, 0L, true, false, at, at - 200L, now), "one streamer");
        // The relay's own second always counts, whatever the local pair.
        assertTrue(ServerLiveStatus.secondStreamLive(2, at - 5_000L, true, true, at, at - 200L, now), "relay names the second");
    }
}
