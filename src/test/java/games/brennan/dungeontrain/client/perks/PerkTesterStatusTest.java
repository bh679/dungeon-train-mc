package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When {@link PerkTesterStatus} asks the relay again, and how it reads the answer. */
final class PerkTesterStatusTest {

    private static final long NOW = 100_000_000L;
    private static final long TEN_MIN = 10 * 60_000L;

    @Test
    @DisplayName("never asked, or invalidated after a link: ask at once")
    void askAtOnce() {
        assertTrue(PerkTesterStatus.due(NOW, 0L, false, 0L));
        assertTrue(PerkTesterStatus.due(NOW, 0L, true, 0L));
    }

    @Test
    @DisplayName("no link code out: ask every ten minutes, yes or no")
    void tenMinutesWithoutALink() {
        assertFalse(PerkTesterStatus.due(NOW, NOW - TEN_MIN + 1, false, 0L));
        assertTrue(PerkTesterStatus.due(NOW, NOW - TEN_MIN, false, 0L));
        assertFalse(PerkTesterStatus.due(NOW, NOW - 60_000L, true, 0L));
    }

    @Test
    @DisplayName("a link code is out: a no is asked again every 30 s")
    void pendingLinkReasksANo() {
        long started = NOW - 60_000L;
        assertFalse(PerkTesterStatus.due(NOW, NOW - PerkTesterStatus.PENDING_TTL_MS + 1, false, started));
        assertTrue(PerkTesterStatus.due(NOW, NOW - PerkTesterStatus.PENDING_TTL_MS, false, started));
    }

    @Test
    @DisplayName("a yes is not re-asked early, and an expired link code is not pending")
    void yesAndExpiredCode() {
        long started = NOW - 60_000L;
        assertFalse(PerkTesterStatus.due(NOW, NOW - 60_000L, true, started));
        long expired = NOW - PerkTesterStatus.LINK_CODE_TTL_MS;
        assertFalse(PerkTesterStatus.due(NOW, NOW - 60_000L, false, expired));
    }

    @Test
    @DisplayName("reads the relay's answer, and nothing else")
    void parse() {
        assertEquals(Boolean.TRUE, PerkTesterStatus.parse("{\"ok\":true,\"tester\":true}"));
        assertEquals(Boolean.FALSE, PerkTesterStatus.parse("{\"ok\":true,\"tester\":false}"));
        assertNull(PerkTesterStatus.parse("{\"error\":\"not_found\"}"));
        assertNull(PerkTesterStatus.parse(""));
    }

    @Test
    @DisplayName("a dev build is a tester unless told otherwise; a release build asks the relay")
    void devBuildsAreTesters() {
        assertEquals(Boolean.TRUE, PerkTesterStatus.devAnswer(true, null));
        assertEquals(Boolean.TRUE, PerkTesterStatus.devAnswer(true, " "));
        assertEquals(Boolean.TRUE, PerkTesterStatus.devAnswer(true, "true"));
        assertEquals(Boolean.FALSE, PerkTesterStatus.devAnswer(true, "false"));
        assertNull(PerkTesterStatus.devAnswer(false, null));
        assertNull(PerkTesterStatus.devAnswer(false, "true"));
    }
}
