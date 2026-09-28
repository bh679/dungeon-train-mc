package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whose work a template is, once a byline is on file.
 *
 * <p>Provenance was decided entirely by which directory a file sat in, so a build downloaded from
 * another player — which lands in the active package, like everything else — read as the player's
 * own from the moment it arrived, and stayed that way however much they edited it. A
 * {@code BuildCredits} entry is the only thing on disk that says otherwise, and it is filed only for
 * work that is not theirs.</p>
 */
final class EditorPlotLabelsCreditTest {

    @Test
    @DisplayName("a credited file in the player's own directory is somebody else's")
    void creditOutranksTheDirectory() {
        EditorPlotLabels.Provenance p =
            EditorPlotLabels.credited(UserContentPaths.Provenance.USER, true);
        assertFalse(p.isUser(), "not theirs to file under My Builds");
        assertTrue(p.isImported());
    }

    @Test
    @DisplayName("without a byline the directory still decides, as it always did")
    void uncreditedIsUnchanged() {
        assertTrue(EditorPlotLabels.credited(UserContentPaths.Provenance.USER, false).isUser());
        assertTrue(EditorPlotLabels.credited(UserContentPaths.Provenance.IMPORTED, false).isImported());

        EditorPlotLabels.Provenance bundled =
            EditorPlotLabels.credited(UserContentPaths.Provenance.BUNDLED, false);
        assertFalse(bundled.isUser());
        assertFalse(bundled.isImported());
    }

    @Test
    @DisplayName("the correction runs one way only")
    void creditNeverPromotesTheOtherTiers() {
        // A file in another package is already imported; a bundled one cannot carry a credit at all,
        // and if a stale entry named one it must not turn a shipped template into somebody's upload.
        assertTrue(EditorPlotLabels.credited(UserContentPaths.Provenance.IMPORTED, true).isImported());
        EditorPlotLabels.Provenance bundled =
            EditorPlotLabels.credited(UserContentPaths.Provenance.BUNDLED, true);
        assertFalse(bundled.isUser());
        assertFalse(bundled.isImported());
    }
}
