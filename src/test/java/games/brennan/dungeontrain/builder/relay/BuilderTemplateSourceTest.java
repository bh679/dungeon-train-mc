package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.data.PlayerDataBackup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Turning a file on disk into the archive entry it was backed up as.
 *
 * <p>The name has to match what {@code PlayerDataBackup.create} wrote, character for character, or a
 * build that IS in a backup is reported as unrecoverable — a silent failure, since the answer looks
 * exactly like "no copy anywhere".</p>
 */
final class BuilderTemplateSourceTest {

    @TempDir
    Path tmp;

    private List<PlayerDataBackup.Source> sources() {
        return List.of(
            new PlayerDataBackup.Source("dungeontrain", tmp.resolve("dungeontrain"), Set.of("backups")),
            new PlayerDataBackup.Source("dtpacks", tmp.resolve("dtpacks")));
    }

    @Test
    @DisplayName("a file under a backed-up root becomes label + its relative path")
    void namesAnEntryUnderTheDataRoot() {
        Path file = tmp.resolve("dungeontrain/user/templates/alpha.nbt");

        assertEquals("dungeontrain/user/templates/alpha.nbt",
            BuilderTemplateSource.entryNameFor(file, sources()));
    }

    @Test
    @DisplayName("a build inside a dtpack is named against the pack root, not the data root")
    void namesAnEntryUnderDtpacks() {
        Path file = tmp.resolve("dtpacks/my pack/parts/floor/wide.nbt");

        assertEquals("dtpacks/my pack/parts/floor/wide.nbt",
            BuilderTemplateSource.entryNameFor(file, sources()));
    }

    @Test
    @DisplayName("a file under no backed-up root has no entry")
    void refusesFilesOutsideTheBackedUpRoots() {
        assertNull(BuilderTemplateSource.entryNameFor(tmp.resolve("saves/world/level.dat"), sources()));
    }

    @Test
    @DisplayName("a file under an excluded folder has no entry — it was never in an archive")
    void refusesExcludedFolders() {
        // backups/ is excluded from the walk, so nothing under it is ever in a zip. Answering with a
        // name would send the reader looking for an entry that cannot exist.
        assertNull(BuilderTemplateSource.entryNameFor(
            tmp.resolve("dungeontrain/backups/old/alpha.nbt"), sources()));
    }
}
