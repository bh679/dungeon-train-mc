package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the scan / diff half of {@link EditorConfigDiff} — the
 * mechanism that makes editor <b>menu</b> changes (weights, stages, loot
 * tables, allow-lists) undoable by snapshotting the config tree around an
 * operation.
 *
 * <p>Runs against a {@link TempDir} rather than the real config root, so no
 * world, server or NeoForge bootstrap is needed. These call the package-private
 * {@code restore(root, snapshot)} — the file half only. The public overload also
 * invalidates the owning store's cache, which reaches into live statics
 * ({@code CarriageWeights.reload()} and friends); calling that from a test loads
 * real config into shared state and breaks unrelated suites, so cache
 * invalidation is verified in-game instead.</p>
 */
final class EditorConfigDiffTest {

    private static void write(Path root, String rel, String contents) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Scan walks nested files and keys them by forward-slash relative path")
    void scanIsRecursiveAndRelative(@TempDir Path root) throws IOException {
        write(root, "weights.json", "{\"standard\":5}");
        write(root, "templates/standard.parts.json", "{\"floor\":\"oak\"}");
        write(root, "prefabs/loot/common.json", "[]");

        Map<String, String> scan = EditorConfigDiff.scan(root);

        assertEquals(3, scan.size());
        assertEquals("{\"standard\":5}", scan.get("weights.json"));
        assertEquals("{\"floor\":\"oak\"}", scan.get("templates/standard.parts.json"));
        assertTrue(scan.containsKey("prefabs/loot/common.json"));
    }

    @Test
    @DisplayName("A missing root scans empty rather than failing")
    void missingRootIsEmpty(@TempDir Path root) {
        // A fresh install that has authored nothing. Diffing this against a
        // later scan is what makes a first-ever save undoable.
        assertTrue(EditorConfigDiff.scan(root.resolve("never-created")).isEmpty());
    }

    @Test
    @DisplayName("Diff reports only files that moved")
    void diffIgnoresUnchanged(@TempDir Path root) throws IOException {
        write(root, "weights.json", "{\"standard\":5}");
        write(root, "stages.json", "{\"early\":{}}");
        Map<String, String> before = EditorConfigDiff.scan(root);

        write(root, "weights.json", "{\"standard\":6}");
        Map<String, String> after = EditorConfigDiff.scan(root);

        List<EditorEditHistory.FileSnapshot> diff = EditorConfigDiff.diff(before, after);
        assertEquals(1, diff.size(), "stages.json did not move");
        assertEquals("weights.json", diff.get(0).relPath());
        assertEquals("{\"standard\":5}", diff.get(0).beforeJson());
        assertEquals("{\"standard\":6}", diff.get(0).afterJson());
    }

    @Test
    @DisplayName("A created file diffs with a null before; a deleted one with a null after")
    void diffRecordsAbsenceAsNull(@TempDir Path root) throws IOException {
        Map<String, String> before = EditorConfigDiff.scan(root);
        write(root, "stages.json", "{\"early\":{}}");
        Map<String, String> after = EditorConfigDiff.scan(root);

        List<EditorEditHistory.FileSnapshot> created = EditorConfigDiff.diff(before, after);
        assertEquals(1, created.size());
        assertNull(created.get(0).beforeJson(), "undoing a creation must delete, not blank");

        List<EditorEditHistory.FileSnapshot> deleted = EditorConfigDiff.diff(after, before);
        assertEquals(1, deleted.size());
        assertNull(deleted.get(0).afterJson());
    }

    @Test
    @DisplayName("An unchanged tree diffs to nothing, so no empty step is pushed")
    void identicalScansDiffEmpty(@TempDir Path root) throws IOException {
        write(root, "weights.json", "{\"standard\":5}");
        Map<String, String> before = EditorConfigDiff.scan(root);
        Map<String, String> after = EditorConfigDiff.scan(root);
        assertTrue(EditorConfigDiff.diff(before, after).isEmpty());
    }

    @Test
    @DisplayName("Restore writes a recorded file back")
    void restoreWritesBack(@TempDir Path root) throws IOException {
        write(root, "weights.json", "{\"standard\":6}");
        EditorEditHistory.FileSnapshot snapshot = new EditorEditHistory.FileSnapshot(
            "weights.json", "{\"standard\":5}", "{\"standard\":6}");

        assertTrue(EditorConfigDiff.restore(root, snapshot));
        assertEquals("{\"standard\":5}", Files.readString(root.resolve("weights.json")));
    }

    @Test
    @DisplayName("Restoring a null before-state deletes the file")
    void restoreDeletesWhenItDidNotExist(@TempDir Path root) throws IOException {
        write(root, "stages.json", "{\"early\":{}}");
        EditorEditHistory.FileSnapshot created = new EditorEditHistory.FileSnapshot(
            "stages.json", null, "{\"early\":{}}");

        assertTrue(EditorConfigDiff.restore(root, created));
        assertFalse(Files.exists(root.resolve("stages.json")),
            "the file did not exist before the op, so undo must remove it");
    }

    @Test
    @DisplayName("Restore recreates missing parent directories")
    void restoreCreatesParents(@TempDir Path root) throws IOException {
        EditorEditHistory.FileSnapshot nested = new EditorEditHistory.FileSnapshot(
            "prefabs/loot/common.json", "[\"minecraft:bread\"]", null);

        assertTrue(EditorConfigDiff.restore(root, nested));
        assertEquals("[\"minecraft:bread\"]",
            Files.readString(root.resolve("prefabs/loot/common.json")));
    }

    @Test
    @DisplayName("A round trip through diff and restore returns the tree to its start")
    void diffThenRestoreRoundTrips(@TempDir Path root) throws IOException {
        write(root, "weights.json", "{\"standard\":5}");
        Map<String, String> before = EditorConfigDiff.scan(root);

        // The "menu change": one file edited, one created.
        write(root, "weights.json", "{\"standard\":9}");
        write(root, "stages.json", "{\"late\":{}}");

        for (EditorEditHistory.FileSnapshot snapshot
                : EditorConfigDiff.diff(before, EditorConfigDiff.scan(root))) {
            assertTrue(EditorConfigDiff.restore(root, snapshot));
        }

        assertEquals(before, EditorConfigDiff.scan(root));
    }
}
