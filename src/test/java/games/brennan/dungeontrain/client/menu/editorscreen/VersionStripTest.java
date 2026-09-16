package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import org.junit.jupiter.api.extension.ExtendWith;
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
@ExtendWith(MenuTestLanguage.class)
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
    @DisplayName("An older version says which version it came from and who saved it")
    void captionNamesParentAndAuthor() {
        RelayBuildPreviews.VersionInfo info = new RelayBuildPreviews.VersionInfo(
                SEQS, new int[] {0, 1, 1}, new String[] {"Ada", "", "Grace"});
        assertEquals("from v1 \u00B7 by Grace", VersionStrip.caption(info, SEQS, 9),
                "v3 was saved from v1 — a branch — by Grace");
        assertEquals("from v1", VersionStrip.caption(info, SEQS, 4), "no author known, so only the parent");
        assertEquals("by Ada", VersionStrip.caption(info, SEQS, 1), "the first version came from nothing");
        assertEquals("", VersionStrip.caption(info, SEQS, 0), "Current has no caption");
        assertEquals("", VersionStrip.caption(null, SEQS, 4), "nothing known, nothing said");
    }

    @Test
    @DisplayName("A version saved from something other than the one before it is a branch")
    void branchesAreTheForks() {
        RelayBuildPreviews.VersionInfo info = new RelayBuildPreviews.VersionInfo(
                SEQS, new int[] {0, 1, 1}, new String[] {"", "", ""});
        assertEquals(false, VersionStrip.isBranch(info, SEQS, 0), "the first version forks from nothing");
        assertEquals(false, VersionStrip.isBranch(info, SEQS, 1), "v2 follows v1");
        assertEquals(true, VersionStrip.isBranch(info, SEQS, 2), "v3 was saved from v1, not v2");
        RelayBuildPreviews.VersionInfo unknown = new RelayBuildPreviews.VersionInfo(
                SEQS, new int[] {0, 0, 77}, new String[] {"", "", ""});
        assertEquals(false, VersionStrip.isBranch(unknown, SEQS, 1), "no parent recorded reads as linear");
        assertEquals(false, VersionStrip.isBranch(unknown, SEQS, 2), "a parent the strip cannot place is not a fork");
    }

    @Test
    @DisplayName("A dot's tooltip is its number and then its caption")
    void tooltipNumbersTheDot() {
        RelayBuildPreviews.VersionInfo info = new RelayBuildPreviews.VersionInfo(
                SEQS, new int[] {0, 1, 1}, new String[] {"Ada", "", "Grace"});
        assertEquals("v3 of 3 \u00B7 from v1 \u00B7 by Grace", VersionStrip.tooltip(info, SEQS, 9));
        assertEquals("v2 of 3 \u00B7 from v1", VersionStrip.tooltip(info, SEQS, 4));
    }

    @Test
    @DisplayName("A parent the strip cannot place is not named")
    void unknownParentIsSilent() {
        RelayBuildPreviews.VersionInfo info = new RelayBuildPreviews.VersionInfo(
                SEQS, new int[] {0, 77, 4}, new String[] {"", "", ""});
        assertEquals("", VersionStrip.caption(info, SEQS, 4), "77 is no version of this build");
        assertEquals("from v2", VersionStrip.caption(info, SEQS, 9));
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
