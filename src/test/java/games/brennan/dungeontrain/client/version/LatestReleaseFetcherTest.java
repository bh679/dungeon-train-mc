package games.brennan.dungeontrain.client.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.version.VersionCheckState.Status.AHEAD;
import static games.brennan.dungeontrain.client.version.VersionCheckState.Status.LATEST;
import static games.brennan.dungeontrain.client.version.VersionCheckState.Status.UPDATE_AVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic coverage of how {@link LatestReleaseFetcher} reads the installed version against the
 * update feed's newest one for the title-screen badge.
 */
final class LatestReleaseFetcherTest {

    @Test
    @DisplayName("The same release is up to date")
    void sameIsLatest() {
        assertEquals(LATEST, LatestReleaseFetcher.classify("0.1612.0", "0.1612.0"));
    }

    @Test
    @DisplayName("A patch difference (auto-release cascade) is not an update")
    void patchOnlyIsLatest() {
        assertEquals(LATEST, LatestReleaseFetcher.classify("0.1612.0", "0.1612.7"));
        assertEquals(LATEST, LatestReleaseFetcher.classify("0.1612.3", "0.1612.0"));
    }

    @Test
    @DisplayName("An older minor sees the update")
    void olderMinorIsUpdate() {
        assertEquals(UPDATE_AVAILABLE, LatestReleaseFetcher.classify("0.1600.0", "0.1612.0"));
        assertEquals(UPDATE_AVAILABLE, LatestReleaseFetcher.classify("0.1612.0", "1.0.0"));
    }

    @Test
    @DisplayName("A newer build (dev) is ahead")
    void newerIsAhead() {
        assertEquals(AHEAD, LatestReleaseFetcher.classify("0.1613.0", "0.1612.0"));
    }

    @Test
    @DisplayName("An unreadable version reads as up to date, never a false update")
    void unreadableIsLatest() {
        assertEquals(LATEST, LatestReleaseFetcher.classify("unknown", "0.1612.0"));
    }
}
