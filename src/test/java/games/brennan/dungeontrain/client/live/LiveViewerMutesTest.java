package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.LiveViewerMutes.Muted;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveViewerMutesTest {

    private static final String ALEX_UUID = "0123456789abcdef0123456789abcdef";
    private static final String MAIN_URL = "https://cdn.example/main/s1/live.m3u8";
    private static final String SECOND_URL = "https://cdn.example/second/s2/live.m3u8";

    @Test
    void mutingAStreamSilencesOnlyThatStream() {
        Set<String> streams = Set.of(MAIN_URL);
        assertFalse(LiveViewerMutes.audible(true, streams, List.of(), MAIN_URL, "Alex", null));
        assertTrue(LiveViewerMutes.audible(true, streams, List.of(), SECOND_URL, "Alex", null),
            "the same player's next broadcast is a new stream, heard again");
        assertTrue(LiveViewerMutes.audible(true, streams, List.of(), "https://cdn.example/main/s3/live.m3u8", "Bo", null),
            "a takeover on the same slot is a new stream");
    }

    @Test
    void mutingAStreamerSilencesThemOnEitherSlotAndEveryStream() {
        List<Muted> muted = List.of(new Muted(null, "Alex"));
        assertFalse(LiveViewerMutes.audible(true, Set.of(), muted, MAIN_URL, "Alex", null));
        assertFalse(LiveViewerMutes.audible(true, Set.of(), muted, SECOND_URL, "alex", null), "names match case-insensitively");
        assertTrue(LiveViewerMutes.audible(true, Set.of(), muted, MAIN_URL, "Bo", null));
    }

    @Test
    void aKnownUuidFollowsARename() {
        List<Muted> muted = List.of(new Muted(ALEX_UUID, "Alex"));
        assertFalse(LiveViewerMutes.audible(true, Set.of(), muted, MAIN_URL, "AlexRenamed", ALEX_UUID));
        assertTrue(LiveViewerMutes.audible(true, Set.of(), muted, MAIN_URL, "Alex", "ffffffffffffffffffffffffffffffff"),
            "a different player who took the name is not muted");
    }

    @Test
    void soundOffSilencesEverything() {
        assertFalse(LiveViewerMutes.audible(false, Set.of(), List.of(), MAIN_URL, "Bo", null));
    }

    @Test
    void anUnknownStreamerIsOnlyMutedByStream() {
        List<Muted> muted = List.of(new Muted(null, "Alex"));
        assertTrue(LiveViewerMutes.audible(true, Set.of(), muted, MAIN_URL, null, null), "the replay loop names nobody");
    }

    @Test
    void withAddsRefreshesAndCaps() {
        List<Muted> one = LiveViewerMutes.with(List.of(), "Alex", null);
        assertEquals(List.of(new Muted(null, "Alex")), one);
        List<Muted> again = LiveViewerMutes.with(one, "alex", ALEX_UUID);
        assertEquals(List.of(new Muted(ALEX_UUID, "alex")), again, "no duplicate; the uuid is kept once known");
        assertEquals(List.of(new Muted(ALEX_UUID, "Alex")), LiveViewerMutes.with(again, "Alex", null), "a later mute keeps the uuid");

        List<Muted> many = new ArrayList<>();
        for (int i = 0; i < LiveViewerMutes.MAX_MUTED; i++) many.add(new Muted(null, "P" + i));
        List<Muted> capped = LiveViewerMutes.with(many, "Newest", null);
        assertEquals(LiveViewerMutes.MAX_MUTED, capped.size());
        assertEquals("P1", capped.get(0).name(), "the oldest goes first");
        assertEquals("Newest", capped.get(capped.size() - 1).name());
    }

    @Test
    void withoutRemovesOnlyThatStreamer() {
        List<Muted> muted = List.of(new Muted(ALEX_UUID, "Alex"), new Muted(null, "Bo"));
        assertEquals(List.of(new Muted(null, "Bo")), LiveViewerMutes.without(muted, "Alex", ALEX_UUID));
        assertEquals(List.of(new Muted(ALEX_UUID, "Alex")), LiveViewerMutes.without(muted, "BO", null));
        assertEquals(2, muted.size(), "the input list is untouched");
    }

    @Test
    void entriesRoundTripThroughTheConfigForm() {
        Muted withUuid = new Muted(ALEX_UUID, "Alex");
        Muted withoutUuid = new Muted(null, "Bo_2");
        assertEquals(ALEX_UUID + "|Alex", withUuid.serialize());
        assertEquals("|Bo_2", withoutUuid.serialize());
        assertEquals(withUuid, Muted.parse(withUuid.serialize()));
        assertEquals(withoutUuid, Muted.parse(withoutUuid.serialize()));
        assertEquals(new Muted(ALEX_UUID, "Alex"), Muted.parse("01234567-89ab-cdef-0123-456789ABCDEF|Alex"), "dashed, upper-case uuids normalise");
        assertEquals(new Muted(null, "Alex"), Muted.parse("not-a-uuid|Alex"), "a bad uuid is dropped, the name kept");
        assertNull(Muted.parse("Alex"), "no separator");
        assertNull(Muted.parse("|"), "no name");
        assertNull(Muted.parse("|bad name!"), "not a player name");
        assertNull(Muted.parse(null));
    }

    @Test
    void theStreamerOfAStreamComesFromTheStatusShownWithIt() {
        Status live = new Status(true, "s1", MAIN_URL, "Alex", 1, 10, true, null, false, List.of());
        assertEquals("Alex", LiveFeedSource.streamerOf(MAIN_URL, false, live));
        assertNull(LiveFeedSource.streamerOf(SECOND_URL, false, live), "a different stream than the status names");
        assertNull(LiveFeedSource.streamerOf("https://cdn.example/replay/seg00001.ts", true, live), "the replay names nobody");
        assertNull(LiveFeedSource.streamerOf(MAIN_URL, false, null));
    }
}
