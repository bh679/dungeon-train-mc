package games.brennan.dungeontrain.moderation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The baked list is a usable floor; the relay overlay merges in and round-trips through the cache. */
final class LiveWordListTest {

    @AfterEach
    void reset() {
        LiveWordList.setRelayForTest(null);
    }

    @Test
    @DisplayName("the baked resource parses, has terms, an allow list and a ladder")
    void bakedResource() {
        LiveWordList.Payload b = LiveWordList.baked();
        assertTrue(b.hasTerms());
        assertFalse(b.allow().isEmpty());
        assertNotNull(b.ladder());
        assertEquals(4, b.ladder().removeAtStrike());
        assertEquals(3, b.ladder().warningsBeforeRemoval());
    }

    @Test
    @DisplayName("every baked allow-listed word really is left alone by the baked list")
    void allowListHolds() {
        CompiledWords words = LiveWordList.compile(LiveWordList.baked(), LiveWordList.Payload.EMPTY);
        for (String ok : LiveWordList.baked().allow()) {
            assertFalse(TextCensor.censor("I visited " + ok + " once", words).changed(), ok);
        }
    }

    @Test
    @DisplayName("a relay overlay adds terms and its ladder wins; the cache JSON round-trips")
    void relayOverlayAndRoundTrip() {
        String body = "{\"ok\":true,\"version\":7,\"hard\":[\"blarnex\"],\"word\":[\"zorp\"],\"cjk\":[],"
            + "\"phrases\":[\"red flannel\"],\"allow\":[\"zorpington\"],"
            + "\"ladder\":{\"removeAtStrike\":5,\"banAfterRemovals\":2,\"banDays\":[7,0],\"strikeDecayDays\":14}}";
        LiveWordList.Payload p = LiveWordList.parse(body);
        assertEquals(7, p.version());
        assertEquals(List.of("blarnex"), p.hard());
        assertEquals(5, p.ladder().removeAtStrike());
        assertEquals(List.of(7, 0), p.ladder().banDays());

        LiveWordList.setRelayForTest(p);
        assertEquals(5, LiveWordList.ladder().removeAtStrike());
        CompiledWords words = LiveWordList.effective();
        assertTrue(TextCensor.censor("zorp", words).changed());
        assertFalse(TextCensor.censor("zorpington", words).changed());

        LiveWordList.Payload again = LiveWordList.parse(LiveWordList.toJson(p));
        assertEquals(p, again);
    }

    @Test
    @DisplayName("the baked mild words are masked by mild() and are not strike words")
    void bakedMildWords() {
        LiveWordList.setRelayForTest(LiveWordList.Payload.EMPTY);
        assertTrue(LiveWordList.baked().mild().contains("shit"));
        for (String w : LiveWordList.baked().mild()) {
            assertFalse(LiveWordList.baked().word().contains(w), w);
            TextCensor.Result r = TextCensor.censor("well " + w + " then", LiveWordList.effective(), LiveWordList.mild());
            assertEquals("well **** then", r.text(), w);
            assertTrue(r.hits().isEmpty(), w);
        }
    }

    @Test
    @DisplayName("a mild term wins over the relay still listing it as a strike word")
    void mildBeatsRelayStrikeList() {
        LiveWordList.setRelayForTest(LiveWordList.parse("{\"ok\":true,\"word\":[\"Shit\",\"zorp\"],\"hard\":[\"bastard\"]}"));
        TextCensor.Result r = TextCensor.censor("shit bastard zorp", LiveWordList.effective(), LiveWordList.mild());
        assertEquals("**** **** ****", r.text());
        assertEquals(List.of("zorp"), r.hits());
    }

    @Test
    @DisplayName("a relay mild list round-trips through the cache and joins the baked one")
    void relayMildRoundTrip() {
        LiveWordList.Payload p = LiveWordList.parse("{\"ok\":true,\"word\":[\"zorp\"],\"mild\":[\"flib\"]}");
        assertEquals(List.of("flib"), p.mild());
        assertEquals(p, LiveWordList.parse(LiveWordList.toJson(p)));
        LiveWordList.setRelayForTest(p);
        assertTrue(TextCensor.censor("flib", LiveWordList.mild()).changed());
        assertFalse(TextCensor.censor("flib", LiveWordList.effective()).changed());
        assertTrue(TextCensor.censor("shit", LiveWordList.mild()).changed());
        assertEquals(List.of(), LiveWordList.parse("{\"ok\":true,\"word\":[\"zorp\"]}").mild());
    }

    @Test
    @DisplayName("garbage, a non-ok body and an impossible ladder are dropped, never thrown")
    void defensiveParse() {
        assertEquals(LiveWordList.Payload.EMPTY, LiveWordList.parse("not json"));
        assertEquals(LiveWordList.Payload.EMPTY, LiveWordList.parse("[1,2]"));
        assertEquals(LiveWordList.Payload.EMPTY, LiveWordList.parse("{\"ok\":false,\"word\":[\"zorp\"]}"));
        LiveWordList.Payload p = LiveWordList.parse("{\"word\":[\"a\",\"ok\",7,null],\"ladder\":{\"removeAtStrike\":0}}");
        assertEquals(List.of("ok"), p.word());
        assertNull(p.ladder());
    }

    @Test
    @DisplayName("without a relay ladder the baked one is used")
    void ladderFallsBackToBaked() {
        LiveWordList.setRelayForTest(LiveWordList.Payload.EMPTY);
        assertEquals(LiveWordList.baked().ladder(), LiveWordList.ladder());
    }
}
