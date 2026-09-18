package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-logic tests for the streaming-software name match. Only {@code matchIn} is exercised — the
 * probe proper reads the live process table and has no business in a unit test.
 *
 * <p>A false positive here puts a "Streaming?" nudge in front of someone who isn't, so the near-miss
 * names — OBS's own helper processes, an unrelated app that starts with the same letters — matter
 * as much as the hits.</p>
 */
class StreamingSoftwareDetectorTest {

    @Test
    void findsObsOnWindows() {
        assertEquals("obs64.exe", StreamingSoftwareDetector.matchIn(List.of("explorer.exe", "obs64.exe", "javaw.exe")));
        assertEquals("obs32.exe", StreamingSoftwareDetector.matchIn(List.of("obs32.exe")));
    }

    @Test
    void findsObsOnMacAndLinuxWhateverTheCasing() {
        assertEquals("obs", StreamingSoftwareDetector.matchIn(List.of("OBS")));
        assertEquals("obs", StreamingSoftwareDetector.matchIn(List.of("obs")));
        assertEquals("obs64.exe", StreamingSoftwareDetector.matchIn(List.of("OBS64.EXE")));
    }

    @Test
    void findsStreamlabs() {
        assertEquals("streamlabs desktop.exe", StreamingSoftwareDetector.matchIn(List.of("Streamlabs Desktop.exe")));
        assertEquals("streamlabs obs.exe", StreamingSoftwareDetector.matchIn(List.of("Streamlabs OBS.exe")));
    }

    @Test
    void aFullPathMatchesOnItsFinalSegment() {
        assertEquals("obs64.exe", StreamingSoftwareDetector.matchIn(
                List.of("C:\\Program Files\\obs-studio\\bin\\64bit\\obs64.exe")));
        assertEquals("obs", StreamingSoftwareDetector.matchIn(
                List.of("/Applications/OBS.app/Contents/MacOS/OBS")));
    }

    @Test
    void nearMissesDoNotMatch() {
        assertNull(StreamingSoftwareDetector.matchIn(List.of(
                "obs-browser-page.exe", "obs-ffmpeg-mux.exe", "obsidian.exe", "Obsidian", "obs64", "xobs")));
    }

    @Test
    void nullAndBlankEntriesAreSkipped() {
        assertNull(StreamingSoftwareDetector.matchIn(Arrays.asList(null, "", "   ")));
        assertEquals("obs64.exe", StreamingSoftwareDetector.matchIn(Arrays.asList(null, "  ", "obs64.exe")));
    }

    @Test
    void nothingRunningIsNull() {
        assertNull(StreamingSoftwareDetector.matchIn(List.of()));
        assertNull(StreamingSoftwareDetector.matchIn(null));
    }
}
