package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Paging through the versions the relay recorded of a build.
 *
 * <p>"Current" — the build as it is now — sits one past the newest recorded frame rather than being
 * one of them: the relay's history is what it was told, and the local template may have moved on
 * since. The arrows walk back into the history and forward out of it again.</p>
 */
final class VersionStripTest {

    private static final int[] SEQS = {1, 4, 9};

    @Test
    @DisplayName("Current sits past the newest frame, and each seq knows its own place")
    void indexOfPlacesEverySeq() {
        assertEquals(0, VersionStrip.indexOf(SEQS, 1));
        assertEquals(1, VersionStrip.indexOf(SEQS, 4));
        assertEquals(2, VersionStrip.indexOf(SEQS, 9));
        assertEquals(SEQS.length, VersionStrip.indexOf(SEQS, 0));
        // A seq the index does not hold reads as Current rather than as some other frame.
        assertEquals(SEQS.length, VersionStrip.indexOf(SEQS, 7));
    }

    @Test
    @DisplayName("Back walks Current into the newest frame and on down, then stops at the oldest")
    void olderWalksBackAndStops() {
        assertEquals(9, VersionStrip.older(SEQS, 0));
        assertEquals(4, VersionStrip.older(SEQS, 9));
        assertEquals(1, VersionStrip.older(SEQS, 4));
        assertEquals(1, VersionStrip.older(SEQS, 1), "the oldest frame has nothing before it");
    }

    @Test
    @DisplayName("Forward walks up the frames and out to Current, which is the end of the line")
    void newerWalksForwardToCurrent() {
        assertEquals(4, VersionStrip.newer(SEQS, 1));
        assertEquals(9, VersionStrip.newer(SEQS, 4));
        assertEquals(0, VersionStrip.newer(SEQS, 9), "past the newest frame is the build as it is now");
        assertEquals(0, VersionStrip.newer(SEQS, 0), "Current is already the newest thing there is");
    }

    @Test
    @DisplayName("A build with one recorded frame still pages between it and Current")
    void singleFrameStillPages() {
        int[] one = {3};
        assertEquals(3, VersionStrip.older(one, 0));
        assertEquals(0, VersionStrip.newer(one, 3));
        assertEquals(3, VersionStrip.older(one, 3));
    }
}
