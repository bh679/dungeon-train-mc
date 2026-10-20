package games.brennan.dungeontrain.client.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the update notice names a release: the major entry when there is one, otherwise the
 * first feature, then content, then fix; internal and unreleased entries never name it.
 */
final class ReleaseNameTest {

    private static String name(String body, String version) {
        ReleaseName.Info info = ReleaseName.pick(body, version);
        return info == null ? null : info.name();
    }

    private static String entry(String title, String type, String releasedIn, String extra) {
        return "{\"id\":\"" + title + "\",\"title\":\"" + title + "\",\"type\":\"" + type
            + "\",\"summary\":\"Summary of " + title + ".\",\"released\":true,\"released_in\":\"" + releasedIn + "\"" + extra + "}";
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
        assertEquals("The Creaking joins the Train", name(body, "0.1357.0"));
    }

    @Test
    @DisplayName("A full major leads a notable one")
    void fullMajorBeforeNotable() {
        String body = ledger(
            entry("Middle", "feat", "v1.2.0", ",\"major\":true,\"notable\":true"),
            entry("Headline", "feat", "v1.2.0", ",\"major\":true"));
        assertEquals("Headline", name(body, "1.2.0"));
    }

    @Test
    @DisplayName("Without a major: feature, then content, then fix, first in ledger order")
    void typeOrder() {
        String body = ledger(
            entry("A fix", "fix", "v1.2.0", ""),
            entry("Some content", "content", "v1.2.0", ""),
            entry("First feature", "feat", "v1.2.0", ""),
            entry("Second feature", "feat", "v1.2.0", ""));
        assertEquals("First feature", name(body, "1.2.0"));
        assertEquals("A fix", name(ledger(
            entry("Tidy", "chore", "v1.2.0", ""), entry("A fix", "fix", "v1.2.0", "")), "1.2.0"));
    }

    @Test
    @DisplayName("Only entries released in that release count, matched on major.minor")
    void matchesRelease() {
        String body = ledger(
            entry("Older", "feat", "v1.1.0", ""),
            entry("This one", "fix", "v1.2.0", ""));
        assertEquals("This one", name(body, "1.2.3"));
        assertNull(name(body, "1.3.0"));
    }

    @Test
    @DisplayName("Internal and unreleased entries never name a release")
    void skipsInternalAndUnreleased() {
        String body = ledger(
            entry("Dev tool", "feat", "v1.2.0", ",\"tags\":[\"internal\"]"),
            "{\"title\":\"Pending\",\"type\":\"feat\",\"released\":false,\"released_in\":\"v1.2.0\"}",
            entry("Player fix", "fix", "v1.2.0", ",\"tags\":[\"bug_fix\"]"));
        assertEquals("Player fix", name(body, "1.2.0"));
    }

    @Test
    @DisplayName("Malformed input fails safe")
    void malformed() {
        assertNull(name(null, "1.2.0"));
        assertNull(name("", "1.2.0"));
        assertNull(name("not json", "1.2.0"));
        assertNull(name("[]", "1.2.0"));
        assertNull(name("{\"entries\":{}}", "1.2.0"));
        assertNull(name(ledger(entry("X", "feat", "garbage", "")), "1.2.0"));
        assertNull(name(ledger(entry("X", "feat", "v1.2.0", "")), "garbage"));
    }

    @Test
    @DisplayName("The tier follows the lead entry: major, notable, or normal")
    void tier() {
        assertEquals(ReleaseName.Tier.MAJOR, ReleaseName.pick(ledger(
            entry("Big", "content", "v1.2.0", ",\"major\":true")), "1.2.0").tier());
        assertEquals(ReleaseName.Tier.NOTABLE, ReleaseName.pick(ledger(
            entry("Mid", "feat", "v1.2.0", ",\"major\":true,\"notable\":true"),
            entry("Small", "feat", "v1.2.0", "")), "1.2.0").tier());
        ReleaseName.Info normal = ReleaseName.pick(ledger(entry("Small", "feat", "v1.2.0", "")), "1.2.0");
        assertEquals(ReleaseName.Tier.NORMAL, normal.tier());
        assertEquals("Summary of Small.", normal.summary());
    }

    @Test
    @DisplayName("shortSummary keeps the first plain sentence")
    void shortSummaryFirstSentence() {
        assertEquals("Write a book.", ReleaseName.shortSummary("Write a book. Someone else finds it."));
        assertEquals("Pale trees have taken root.", ReleaseName.shortSummary("Pale trees have taken root."));
        assertEquals("Highlights from the last few weeks:",
            ReleaseName.shortSummary("Highlights from the last few weeks:\n- one\n- two"));
    }

    @Test
    @DisplayName("shortSummary strips markdown headings, emphasis and links")
    void shortSummaryStripsMarkdown() {
        assertEquals("A HUGE boost to the number of dimensions.",
            ReleaseName.shortSummary("## Number of dimensions\nA **HUGE** boost to the number of dimensions. More."));
        assertEquals("See the trailer now.", ReleaseName.shortSummary("See the [trailer](https://x.y/z) now."));
    }

    @Test
    @DisplayName("shortSummary truncates long text and drops bare links")
    void shortSummaryTruncatesAndDropsLinks() {
        String longText = "word ".repeat(60).strip();
        String cut = ReleaseName.shortSummary(longText);
        assertTrue(cut.endsWith("...") && cut.length() <= ReleaseName.SUMMARY_MAX, cut);
        assertEquals("", ReleaseName.shortSummary("https://www.bilibili.com/video/BV1TNMW6iEiQ/"));
        assertEquals("", ReleaseName.shortSummary(""));
    }
}
