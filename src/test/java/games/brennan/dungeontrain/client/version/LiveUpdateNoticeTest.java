package games.brennan.dungeontrain.client.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live update notice's rules: only a real (major/minor) release that lands after the
 * session's baseline and is newer than the installed jar is announced; feed parsing never throws.
 */
final class LiveUpdateNoticeTest {

    @Test
    @DisplayName("A new minor release after the baseline is announced")
    void minorBumpAnnounces() {
        assertTrue(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.983.4", "0.984.0"));
    }

    @Test
    @DisplayName("Cascade patch releases never announce")
    void patchBumpIsSilent() {
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.983.0", "0.983.1"));
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.983.5", "0.983.22"));
    }

    @Test
    @DisplayName("The same release is announced once — after announcing, it is the baseline")
    void sameVersionAnnouncesOnce() {
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.984.0", "0.984.0"));
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.984.0", "0.984.3"));
    }

    @Test
    @DisplayName("No baseline yet means nothing is new yet (live only, not on join)")
    void noBaselineIsSilent() {
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.980.0", null, "0.984.0"));
    }

    @Test
    @DisplayName("An installed jar already at or past the release says nothing")
    void installedAtOrAheadIsSilent() {
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.984.0", "0.983.0", "0.984.0"));
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.985.0", "0.983.0", "0.984.0"));
    }

    @Test
    @DisplayName("Garbage versions fail safe")
    void garbageIsSilent() {
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.983.0", "garbage"));
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.983.0", ""));
        assertFalse(LiveUpdateNotice.shouldAnnounce("0.983.0", "0.983.0", null));
    }

    @Test
    @DisplayName("parseLatest reads promos.<mc>-latest and strips a leading v")
    void parseLatestReadsPromo() {
        String body = "{\"homepage\":\"x\",\"1.21.1\":{\"0.984.0\":\"notes\"},"
            + "\"promos\":{\"1.21.1-latest\":\"v0.984.0\",\"1.21.1-recommended\":\"0.983.0\"}}";
        assertEquals("0.984.0", UpdateFeed.parseLatest(body, "1.21.1"));
    }

    @Test
    @DisplayName("parseLatest returns null for anything malformed")
    void parseLatestMalformed() {
        assertNull(UpdateFeed.parseLatest(null, "1.21.1"));
        assertNull(UpdateFeed.parseLatest("", "1.21.1"));
        assertNull(UpdateFeed.parseLatest("not json", "1.21.1"));
        assertNull(UpdateFeed.parseLatest("[]", "1.21.1"));
        assertNull(UpdateFeed.parseLatest("{\"promos\":{}}", "1.21.1"));
        assertNull(UpdateFeed.parseLatest("{\"promos\":{\"1.21.1-latest\":{}}}", "1.21.1"));
        assertNull(UpdateFeed.parseLatest("{\"promos\":{\"1.21.1-latest\":\"0.984.0\"}}", "1.20.1"));
    }

    @Test
    @DisplayName("parsePublishedAt reads a GitHub release's published_at")
    void parsePublishedAt() {
        String body = "{\"tag_name\":\"v0.984.0\",\"published_at\":\"2026-09-27T09:00:00Z\"}";
        assertEquals(java.time.Instant.parse("2026-09-27T09:00:00Z").toEpochMilli(),
            ReleaseTime.parsePublishedAt(body));
        assertNull(ReleaseTime.parsePublishedAt(null));
        assertNull(ReleaseTime.parsePublishedAt("{}"));
        assertNull(ReleaseTime.parsePublishedAt("{\"published_at\":\"yesterday\"}"));
        assertNull(ReleaseTime.parsePublishedAt("{\"published_at\":null}"));
    }

    @Test
    @DisplayName("Time since release is measured at send time; a future stamp (clock skew) clamps to zero")
    void sinceRelease() {
        assertEquals(java.time.Duration.ofSeconds(90), ReleaseTime.since(1_000_000L, 1_090_000L));
        assertEquals(java.time.Duration.ZERO, ReleaseTime.since(2_000_000L, 1_000_000L));
    }
}
