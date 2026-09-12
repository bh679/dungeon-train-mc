package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dev-mode source delete must also remove the exploded classpath copy the running game reads, or
 * the deleted template comes back on the next cache miss.
 */
final class SourceTreeFilesTest {

    @Test
    @DisplayName("the classpath twin is the same relative path under build/resources/main")
    void twinPath(@TempDir Path root) {
        Path src = root.resolve("src/main/resources/data/dungeontrain/portals/room/default.group.json");
        assertEquals(root.resolve("build/resources/main/data/dungeontrain/portals/room/default.group.json"),
            SourceTreeFiles.classpathTwin(src));
    }

    @Test
    @DisplayName("a file outside src/main/resources has no twin")
    void noTwinOutsideSourceTree(@TempDir Path root) {
        assertNull(SourceTreeFiles.classpathTwin(root.resolve("run/dungeontrain/user/contents/x.nbt")));
    }

    @Test
    @DisplayName("deleting the source file removes the twin too; a missing twin is fine")
    void deletesBoth(@TempDir Path root) throws IOException {
        Path src = root.resolve("src/main/resources/data/dungeontrain/contents/zz.nbt");
        Path twin = root.resolve("build/resources/main/data/dungeontrain/contents/zz.nbt");
        Files.createDirectories(src.getParent());
        Files.createDirectories(twin.getParent());
        Files.writeString(src, "a");
        Files.writeString(twin, "a");

        assertTrue(SourceTreeFiles.deleteWithClasspathTwin(src));
        assertFalse(Files.exists(src));
        assertFalse(Files.exists(twin));

        // Second delete: nothing there, no error, reports false.
        assertFalse(SourceTreeFiles.deleteWithClasspathTwin(src));
        assertFalse(SourceTreeFiles.deleteWithClasspathTwin(null));
    }
}
