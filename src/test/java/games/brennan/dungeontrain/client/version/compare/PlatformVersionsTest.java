package games.brennan.dungeontrain.client.version.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformVersionsTest {

    private static ReleaseEntry entry(String version) {
        return new ReleaseEntry(FullSemver.parse(version).orElseThrow(), "notes " + version, "");
    }

    private static FullSemver v(String s) {
        return FullSemver.parse(s).orElseThrow();
    }

    private static final PlatformVersions LISTING = new PlatformVersions(Platform.MODRINTH, List.of(
            entry("0.828.0"), entry("0.849.0"), entry("0.843.0"), entry("0.794.0")));

    @Test
    @DisplayName("sorted newest first regardless of input order")
    void sorted() {
        assertEquals("0.849.0", LISTING.latest().orElseThrow().version().toString());
        assertEquals("0.794.0", LISTING.entries().get(3).version().toString());
    }

    @Test
    @DisplayName("behind = listed versions strictly newer than the point")
    void counts() {
        assertEquals(2, LISTING.countNewerThan(v("0.828.0")));
        assertEquals(4, LISTING.countNewerThan(v("0.700.0")));
        assertEquals(0, LISTING.countNewerThan(v("0.849.0")));
        assertEquals(0, LISTING.countNewerThan(v("0.900.0")));
    }

    @Test
    @DisplayName("what a player on 0.794.0 missed up to 0.843.0, newest first")
    void between() {
        List<ReleaseEntry> missed = LISTING.entriesBetween(v("0.794.0"), v("0.843.0"));
        assertEquals(List.of("0.843.0", "0.828.0"),
                missed.stream().map(e -> e.version().toString()).toList());
        assertTrue(LISTING.entriesBetween(v("0.849.0"), v("0.849.0")).isEmpty());
        assertTrue(PlatformVersions.empty(Platform.CURSEFORGE).latest().isEmpty());
    }
}
