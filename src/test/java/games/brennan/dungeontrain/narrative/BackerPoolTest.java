package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.narrative.AinBackerOverlay.sanitize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parse + sanitize behaviour for the backer name pool. Pure logic only — no network, no
 * ResourceManager, no AIN registry (which would drag in a live datapack reload).
 */
class BackerPoolTest {

    private static final int NAME = BackerPool.MAX_NAME_LEN;
    private static final int PHRASE = BackerPool.MAX_PHRASE_LEN;

    @AfterEach
    void tearDown() {
        BackerPool.reset();
    }

    // ---- sanitize --------------------------------------------------------------

    @Test
    @DisplayName("sanitize strips the section sign so formatting codes can't reach a component")
    void sanitizeStripsSectionSign() {
        assertEquals("cRedr", sanitize("§cRed§r", NAME));
    }

    @Test
    @DisplayName("sanitize removes newlines rather than letting them split lore into extra lines")
    void sanitizeRemovesNewlines() {
        assertEquals("twolines", sanitize("two\nlines", NAME));
        assertEquals("carriagereturn", sanitize("carriage\rreturn", NAME));
        assertEquals("tabbed", sanitize("tab\tbed", NAME));
    }

    @Test
    @DisplayName("sanitize trims before clamping, so leading spaces don't eat the length budget")
    void sanitizeTrimsBeforeClamping() {
        String padded = "   " + "W".repeat(NAME) + "   ";
        assertEquals("W".repeat(NAME), sanitize(padded, NAME));
    }

    @Test
    @DisplayName("sanitize clamps an over-long string instead of rejecting it")
    void sanitizeClamps() {
        assertEquals(NAME, sanitize("W".repeat(500), NAME).length());
        assertEquals(PHRASE, sanitize("W".repeat(500), PHRASE).length());
    }

    @Test
    @DisplayName("sanitize keeps ordinary punctuation and non-latin text")
    void sanitizeKeepsRealNames() {
        // Shapes that already exist in AIN's shipped community pool.
        assertEquals("Q (She/They)", sanitize("Q (She/They)", NAME));
        assertEquals("-Phoenix-", sanitize("-Phoenix-", NAME));
        assertEquals("日本語", sanitize("日本語", NAME));
    }

    @Test
    @DisplayName("sanitize reduces control-only and null input to empty")
    void sanitizeEmptyCases() {
        assertEquals("", sanitize(null, NAME));
        assertEquals("", sanitize("", NAME));
        assertEquals("", sanitize("   ", NAME));
        assertEquals("", sanitize("§§§", NAME));
        assertEquals("", sanitize("\n\r\t", NAME));
    }

    // ---- parse -----------------------------------------------------------------

    @Test
    @DisplayName("parse reads names and phrases from a well-formed response")
    void parseHappyPath() {
        BackerPool.Snapshot s = BackerPool.parse(
                "{\"ok\":true,\"names\":[\"Train Wolfy\"],\"phrases\":[\"keeps the lamps lit\"]}");
        assertNotNull(s);
        assertEquals(java.util.List.of("Train Wolfy"), s.names());
        assertEquals(java.util.List.of("keeps the lamps lit"), s.phrases());
    }

    @Test
    @DisplayName("an ok response with empty arrays is a valid EMPTY snapshot, not a parse failure")
    void parseEmptyIsValid() {
        // This is the "no backers yet" answer and must stand the overlay down rather than being
        // treated as malformed and leaving a stale pool applied.
        BackerPool.Snapshot s = BackerPool.parse("{\"ok\":true,\"names\":[],\"phrases\":[]}");
        assertNotNull(s);
        assertTrue(s.isEmpty());
    }

    @Test
    @DisplayName("parse returns null for malformed or failed bodies so the last snapshot is kept")
    void parseRejectsBadBodies() {
        assertNull(BackerPool.parse("{\"ok\":false}"));
        assertNull(BackerPool.parse("{}"));
        assertNull(BackerPool.parse("[]"));
        assertNull(BackerPool.parse("\"a string\""));
    }

    @Test
    @DisplayName("parse tolerates missing or wrongly-typed arrays by reading them as empty")
    void parseTolerantOfShapeDrift() {
        BackerPool.Snapshot s = BackerPool.parse("{\"ok\":true,\"names\":\"nope\"}");
        assertNotNull(s);
        assertTrue(s.isEmpty());
    }

    @Test
    @DisplayName("parse sanitizes every entry, so a hostile relay can't inject formatting codes")
    void parseSanitizesEntries() {
        BackerPool.Snapshot s = BackerPool.parse(
                "{\"ok\":true,\"names\":[\"  §aWolfy\\n \"],\"phrases\":[]}");
        assertEquals(java.util.List.of("aWolfy"), s.names());
    }

    @Test
    @DisplayName("parse drops entries that sanitize to nothing")
    void parseDropsEmptied() {
        BackerPool.Snapshot s = BackerPool.parse(
                "{\"ok\":true,\"names\":[\"§§\",\"Wolfy\",\"\"],\"phrases\":[]}");
        assertEquals(java.util.List.of("Wolfy"), s.names());
    }

    @Test
    @DisplayName("parse skips non-string entries rather than failing the whole response")
    void parseSkipsNonStrings() {
        BackerPool.Snapshot s = BackerPool.parse(
                "{\"ok\":true,\"names\":[\"Wolfy\",null,42,{\"a\":1},[\"x\"]],\"phrases\":[]}");
        // 42 is a JSON primitive and stringifies to "42" — a number is a legal, if odd, name.
        assertTrue(s.names().contains("Wolfy"));
        assertTrue(s.names().size() <= 2);
    }

    @Test
    @DisplayName("parse dedupes case-insensitively so paying twice doesn't double the odds")
    void parseDedupes() {
        BackerPool.Snapshot s = BackerPool.parse(
                "{\"ok\":true,\"names\":[\"Wolfy\",\"wolfy\",\"WOLFY\"],\"phrases\":[]}");
        assertEquals(java.util.List.of("Wolfy"), s.names());
    }

    @Test
    @DisplayName("parse caps the entry count even if the relay serves more")
    void parseCaps() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"names\":[");
        for (int i = 0; i < BackerPool.MAX_ENTRIES + 50; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"name").append(i).append('"');
        }
        sb.append("],\"phrases\":[]}");
        BackerPool.Snapshot s = BackerPool.parse(sb.toString());
        assertEquals(BackerPool.MAX_ENTRIES, s.names().size());
    }

    @Test
    @DisplayName("parse clamps names and phrases to their own separate limits")
    void parseClampsPerKind() {
        String longName = "N".repeat(200);
        String longPhrase = "P".repeat(200);
        BackerPool.Snapshot s = BackerPool.parse(
                "{\"ok\":true,\"names\":[\"" + longName + "\"],\"phrases\":[\"" + longPhrase + "\"]}");
        assertEquals(NAME, s.names().get(0).length());
        assertEquals(PHRASE, s.phrases().get(0).length());
    }

    // ---- snapshot --------------------------------------------------------------

    @Test
    @DisplayName("the snapshot starts empty and reset returns it to empty")
    void snapshotLifecycle() {
        assertTrue(BackerPool.snapshot().isEmpty());
        BackerPool.setForTest(new BackerPool.Snapshot(java.util.List.of("Wolfy"), java.util.List.of()));
        assertEquals(java.util.List.of("Wolfy"), BackerPool.snapshot().names());
        BackerPool.reset();
        assertTrue(BackerPool.snapshot().isEmpty());
    }
}
