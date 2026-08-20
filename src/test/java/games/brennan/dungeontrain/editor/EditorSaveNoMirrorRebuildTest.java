package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard: no editor's {@code save()} may re-mirror the plot.
 *
 * <h2>Why this exists</h2>
 * Every one of these editors used to call {@link EditorMirror#rebuildFromMaster}
 * (and its variant-pool twin) in-world just before capture, which made hitting
 * Save destructive — the far half was rebuilt from the low-octant master
 * regardless of edit history, wiping deliberate asymmetry and anything placed by
 * a path the live mirror handlers never see, mutating the world mid-save, and
 * rewriting the variant sidecar as a side effect. Live mirroring is the
 * mechanism; the rebuild is now on demand only, via {@link EditorMirrorRebuild}
 * behind {@code /dungeontrain editor mirror rebuild}.
 *
 * <p>Reintroducing the call would be invisible in a build and easy to do while
 * adding a new editor category, so this scans the sources directly.</p>
 */
final class EditorSaveNoMirrorRebuildTest {

    /** The editors with a {@code save()} that captures a plot into a template. */
    private static final List<String> EDITORS = List.of(
        "CarriageEditor.java",
        "CarriageContentsEditor.java",
        "CarriagePartEditor.java",
        "PillarEditor.java",
        "PortalRoomEditor.java",
        "TrackEditor.java",
        "TunnelEditor.java");

    private static Path editorSources() {
        return RepoPaths.root().resolve("src/main/java/games/brennan/dungeontrain/editor");
    }

    @Test
    @DisplayName("No editor save path calls rebuildFromMaster")
    void editorsDoNotRebuildOnSave() throws IOException {
        Path dir = editorSources();
        for (String file : EDITORS) {
            Path source = dir.resolve(file);
            assertTrue(Files.isRegularFile(source), "missing editor source: " + source);
            assertFalse(Files.readString(source).contains("rebuildFromMaster"),
                file + " calls rebuildFromMaster — saving must capture the plot as it stands. "
                    + "Run the rebuild on demand via EditorMirrorRebuild instead.");
        }
    }

    @Test
    @DisplayName("The on-demand rebuild entry point still runs both mirror passes")
    void rebuildHelperRunsBothPasses() throws IOException {
        String helper = Files.readString(editorSources().resolve("EditorMirrorRebuild.java"));
        assertTrue(helper.contains("EditorVariantMirror.rebuildFromMaster"),
            "EditorMirrorRebuild must run the variant-pool pass");
        assertTrue(helper.contains("EditorMirror.rebuildFromMaster"),
            "EditorMirrorRebuild must run the structural pass");
    }
}
