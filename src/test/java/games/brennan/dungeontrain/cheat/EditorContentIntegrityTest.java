package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content detection for {@link EditorContentIntegrity}. The whole Free Play
 * decision hangs off {@code containsAnyFile}, so its edges matter: a folder
 * that merely <em>exists</em> is what a fresh install looks like and must not
 * taint anybody's run, while content nested in a per-kind subfolder — which is
 * where every store actually writes — must.
 */
class EditorContentIntegrityTest {

    @Test
    @DisplayName("A missing folder has no content")
    void missingFolderIsClean(@TempDir Path tmp) {
        assertFalse(EditorContentIntegrity.containsAnyFile(tmp.resolve("nope")));
    }

    @Test
    @DisplayName("null is treated as no content rather than throwing")
    void nullIsClean() {
        assertFalse(EditorContentIntegrity.containsAnyFile(null));
    }

    @Test
    @DisplayName("An empty folder has no content — that's a fresh install's user/ folder")
    void emptyFolderIsClean(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("unsaved"));
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg));
    }

    @Test
    @DisplayName("Empty subfolders alone are still no content")
    void emptySubfoldersAreClean(@TempDir Path tmp) throws IOException {
        Path pkg = tmp.resolve("unsaved");
        Files.createDirectories(pkg.resolve("carriages"));
        Files.createDirectories(pkg.resolve("contents"));
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg));
    }

    @Test
    @DisplayName("A file directly in the package folder counts")
    void topLevelFileCounts(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("unsaved"));
        Files.writeString(pkg.resolve("weights.json"), "{}");
        assertTrue(EditorContentIntegrity.containsAnyFile(pkg));
    }

    @Test
    @DisplayName("A file nested in a per-kind subfolder counts — that's where stores write")
    void nestedFileCounts(@TempDir Path tmp) throws IOException {
        Path carriages = Files.createDirectories(tmp.resolve("my-pack").resolve("carriages"));
        Files.writeString(carriages.resolve("custom.nbt"), "not really nbt");
        assertTrue(EditorContentIntegrity.containsAnyFile(tmp.resolve("my-pack")));
    }

    @Test
    @DisplayName("A regular file passed where a folder was expected is not a package")
    void regularFileIsNotAPackage(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("README.txt");
        Files.writeString(file, "dropped in by hand");
        assertFalse(EditorContentIntegrity.containsAnyFile(file));
    }

    @Test
    @DisplayName("OS files dropped by opening the folder (.DS_Store, Thumbs.db, desktop.ini) are not content")
    void osFilesAreClean(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("unsaved"));
        Path portals = Files.createDirectories(pkg.resolve("portals"));
        Files.writeString(pkg.resolve(".DS_Store"), "x");
        Files.writeString(portals.resolve(".DS_Store"), "x");
        Files.writeString(pkg.resolve("Thumbs.db"), "x");
        Files.writeString(portals.resolve("desktop.ini"), "x");
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg));
    }

    @Test
    @DisplayName("The editor's Reroll carriage choice alone is not content")
    void rerollChoiceIsClean(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("unsaved"));
        Files.writeString(pkg.resolve(EditorContentIntegrity.EDITOR_VIEW_FILE), "{}");
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg));
    }

    @Test
    @DisplayName("A file named like the reroll choice inside a subfolder still counts")
    void nestedViewFileNameCounts(@TempDir Path tmp) throws IOException {
        Path sub = Files.createDirectories(tmp.resolve("unsaved").resolve("contents"));
        Files.writeString(sub.resolve(EditorContentIntegrity.EDITOR_VIEW_FILE), "{}");
        assertTrue(EditorContentIntegrity.containsAnyFile(tmp.resolve("unsaved")));
    }
}
