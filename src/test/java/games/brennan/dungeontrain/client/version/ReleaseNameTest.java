package games.brennan.dungeontrain.client.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins how the update notice names a release: the major entry when there is one, otherwise the
 * first feature, then content, then fix; internal and unreleased entries never name it.
 */
final class ReleaseNameTest {

    private static String entry(String title, String type, String releasedIn, String extra) {
        return "{\"id\":\"" + title + "\",\"title\":\"" + title + "\",\"type\":\"" + type
            + "\",\"released\":true,\"released_in\":\"" + releasedIn + "\"" + extra + "}";
    }

    private static String ledger(String... entries) {
        return "{\"entries\":[" + String.join(",", entries) + "]}";
    }

    @Test
    @DisplayName("A major entry names the release over any feature")
    void majorWins() {
        String body = ledger(
            entry("Faster loading", "feat", "v0.1357.0", ""),
            entry("The Creaking joins the Train", "content", "v0.1357.0", ",\"major\":true"));
        assertEquals("The Creaking joins the Train", ReleaseName.pickName(body, "0.1357.0"));
    }

    @Test
    @DisplayName("A full major leads a notable one")
    void fullMajorBeforeNotable() {
        String body = ledger(
            entry("Middle", "feat", "v1.2.0", ",\"major\":true,\"notable\":true"),
            entry("Headline", "feat", "v1.2.0", ",\"major\":true"));
        assertEquals("Headline", ReleaseName.pickName(body, "1.2.0"));
    }

    @Test
    @DisplayName("Without a major: feature, then content, then fix, first in ledger order")
    void typeOrder() {
        String body = ledger(
            entry("A fix", "fix", "v1.2.0", ""),
            entry("Some content", "content", "v1.2.0", ""),
            entry("First feature", "feat", "v1.2.0", ""),
            entry("Second feature", "feat", "v1.2.0", ""));
        assertEquals("First feature", ReleaseName.pickName(body, "1.2.0"));
        assertEquals("A fix", ReleaseName.pickName(ledger(
            entry("Tidy", "chore", "v1.2.0", ""), entry("A fix", "fix", "v1.2.0", "")), "1.2.0"));
    }

    @Test
    @DisplayName("Only entries released in that release count, matched on major.minor")
    void matchesRelease() {
        String body = ledger(
            entry("Older", "feat", "v1.1.0", ""),
            entry("This one", "fix", "v1.2.0", ""));
        assertEquals("This one", ReleaseName.pickName(body, "1.2.3"));
        assertNull(ReleaseName.pickName(body, "1.3.0"));
    }

    @Test
    @DisplayName("Internal and unreleased entries never name a release")
    void skipsInternalAndUnreleased() {
        String body = ledger(
            entry("Dev tool", "feat", "v1.2.0", ",\"tags\":[\"internal\"]"),
            "{\"title\":\"Pending\",\"type\":\"feat\",\"released\":false,\"released_in\":\"v1.2.0\"}",
            entry("Player fix", "fix", "v1.2.0", ",\"tags\":[\"bug_fix\"]"));
        assertEquals("Player fix", ReleaseName.pickName(body, "1.2.0"));
    }

    @Test
    @DisplayName("Malformed input fails safe")
    void malformed() {
        assertNull(ReleaseName.pickName(null, "1.2.0"));
        assertNull(ReleaseName.pickName("", "1.2.0"));
        assertNull(ReleaseName.pickName("not json", "1.2.0"));
        assertNull(ReleaseName.pickName("[]", "1.2.0"));
        assertNull(ReleaseName.pickName("{\"entries\":{}}", "1.2.0"));
        assertNull(ReleaseName.pickName(ledger(entry("X", "feat", "garbage", "")), "1.2.0"));
        assertNull(ReleaseName.pickName(ledger(entry("X", "feat", "v1.2.0", "")), "garbage"));
    }
}
