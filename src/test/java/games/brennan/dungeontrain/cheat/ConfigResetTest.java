package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ConfigReset} moves the player's configs aside so defaults regenerate. The property that
 * matters most is that nothing is ever destroyed: every byte the player wrote has to still be on
 * disk, under a name they can rename back.
 */
class ConfigResetTest {

    private static Path write(Path dir, String name, String contents) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, contents);
        return file;
    }

    @Test
    @DisplayName("Every governed config is moved aside, contents intact")
    void movesEveryGovernedFileAside(@TempDir Path dir) throws IOException {
        for (String name : ConfigReset.GOVERNED_FILES) {
            write(dir, name, "contents of " + name);
        }

        ConfigReset.Result result = ConfigReset.run(dir);

        assertTrue(result.success());
        assertEquals(ConfigReset.GOVERNED_FILES.size(), result.moved().size());
        for (ConfigReset.Moved moved : result.moved()) {
            assertFalse(Files.exists(dir.resolve(moved.file())), moved.file() + " should be gone");
            Path backup = dir.resolve(moved.backup());
            assertTrue(Files.exists(backup), "backup should exist: " + moved.backup());
            assertEquals("contents of " + moved.file(), Files.readString(backup));
            assertTrue(moved.backup().startsWith(moved.file() + ".bak-"), moved.backup());
        }
    }

    @Test
    @DisplayName("Files that were never written are skipped, not reported as moved")
    void skipsAbsentFiles(@TempDir Path dir) throws IOException {
        write(dir, DtConfigIntegrity.SERVER_FILE, "only this one exists");

        ConfigReset.Result result = ConfigReset.run(dir);

        assertTrue(result.success());
        assertEquals(List.of(DtConfigIntegrity.SERVER_FILE),
            result.moved().stream().map(ConfigReset.Moved::file).toList());
    }

    @Test
    @DisplayName("A fresh install with no configs at all is a clean no-op")
    void emptyDirIsANoOp(@TempDir Path dir) {
        ConfigReset.Result result = ConfigReset.run(dir);

        assertTrue(result.success());
        assertTrue(result.moved().isEmpty());
    }

    @Test
    @DisplayName("Unrelated files in the config folder are left alone")
    void leavesOtherConfigsAlone(@TempDir Path dir) throws IOException {
        write(dir, DtConfigIntegrity.SERVER_FILE, "dt");
        write(dir, "dungeontrain-client.toml", "client-only settings, not governed");
        write(dir, "someothermod.toml", "not ours");

        ConfigReset.run(dir);

        assertEquals("client-only settings, not governed",
            Files.readString(dir.resolve("dungeontrain-client.toml")));
        assertEquals("not ours", Files.readString(dir.resolve("someothermod.toml")));
    }

    @Test
    @DisplayName("When a config can't be moved it is left untouched, and the failure is reported")
    void unmovableFileIsLeftUntouched(@TempDir Path dir) throws IOException {
        write(dir, DtConfigIntegrity.SERVER_FILE, "dt server settings");
        write(dir, DtConfigIntegrity.COMMON_FILE, "dt common settings");
        // The backup is written next to the original, so a read-only config folder is the real
        // shape of "the set-aside failed" — the same case as a permissions or full-disk error.
        assumeTrue(dir.toFile().setWritable(false), "could not make the temp dir read-only");
        try {
            assumeTrue(!Files.isWritable(dir), "running as a user that ignores write permissions");

            ConfigReset.Result result = ConfigReset.run(dir);

            assertFalse(result.success());
            assertTrue(result.moved().isEmpty(), "nothing should have moved");
            assertEquals(ConfigReset.GOVERNED_FILES.stream()
                .filter(name -> Files.exists(dir.resolve(name))).toList(), result.failed());
            // The player's data is exactly where they left it.
            assertEquals("dt server settings", Files.readString(dir.resolve(DtConfigIntegrity.SERVER_FILE)));
            assertEquals("dt common settings", Files.readString(dir.resolve(DtConfigIntegrity.COMMON_FILE)));
        } finally {
            dir.toFile().setWritable(true); // let @TempDir clean up
        }
    }
}
