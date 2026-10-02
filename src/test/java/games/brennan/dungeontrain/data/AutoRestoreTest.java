package games.brennan.dungeontrain.data;

import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.core.BackupArchiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When lost data is put back without the player asking — and, as importantly, when it is not.
 * There is no manual restore to fall back on, so each of these is the whole behaviour.
 */
class AutoRestoreTest {

    @TempDir
    Path tmp;

    private final AtomicInteger reloads = new AtomicInteger();

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path instance() { return tmp.resolve("instance"); }
    private Path dataRoot() { return instance().resolve("dungeontrain"); }
    private Path configDir() { return instance().resolve("config"); }
    private Path external() { return tmp.resolve("appdata/DungeonTrain/backups"); }

    /** DT's real registration, with the template reload swapped for a counter (no Minecraft here). */
    private Registration reg() {
        return DungeonTrainBackup.describe(dataRoot(), instance().resolve("dtpacks"))
            .onRestored(reloads::incrementAndGet)
            .build();
    }

    private AutoRestore.Outcome run() {
        return AutoRestore.run(reg(), instance(), configDir(), external());
    }

    /** Archive whatever is live into {@code backupsDir}, then lose every live file. */
    private void archiveThenLose(Path backupsDir) throws IOException {
        BackupArchiver.create(backupsDir, "dungeontrain", reg().sources(), "test", "1").archive().orElseThrow();
        for (String sub : new String[] {"user", "achievements", "stats", "loadouts"}) {
            Path dir = dataRoot().resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    @Test
    void anEmptiedInstallIsRestoredFromItsNewestBackup() throws IOException {
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        write(dataRoot().resolve("achievements/uuid.json"), "{\"granted\":[\"a:x\"]}");
        archiveThenLose(reg().backupsRoot());

        AutoRestore.Outcome outcome = run();

        assertTrue(outcome.restored());
        assertEquals(2, outcome.files());
        assertEquals("carriage", Files.readString(dataRoot().resolve("user/templates/a.nbt")));
        assertTrue(Files.isRegularFile(dataRoot().resolve("achievements/uuid.json")));
        assertEquals(1, reloads.get(), "restored builds must be reloaded into the caches");
    }

    @Test
    void theOutOfInstanceMirrorIsEnoughOnItsOwn() throws IOException {
        // Instance deleted and reinstalled: the only copy left is the one kept outside it.
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        archiveThenLose(external());

        assertTrue(run().restored());
        assertEquals("carriage", Files.readString(dataRoot().resolve("user/templates/a.nbt")));
    }

    @Test
    void anInstallWithDataIsLeftAlone() throws IOException {
        write(dataRoot().resolve("user/templates/a.nbt"), "old");
        BackupArchiver.create(reg().backupsRoot(), "dungeontrain", reg().sources(), "test", "1");
        write(dataRoot().resolve("user/templates/a.nbt"), "edited since");
        Files.delete(dataRoot().resolve("user/templates/a.nbt"));
        write(dataRoot().resolve("user/templates/b.nbt"), "a different build");

        AutoRestore.Outcome outcome = run();

        assertFalse(outcome.restored(), "the player has builds — this is not a loss");
        assertFalse(Files.exists(dataRoot().resolve("user/templates/a.nbt")),
            "a build deleted on purpose must not come back");
        assertEquals(0, reloads.get());
    }

    @Test
    void aDeliberatelyEmptiedInstallStaysEmpty() throws IOException {
        // The Video Tools profile reset, or the old card's "Don't ask".
        write(dataRoot().resolve("achievements/uuid.json"), "{\"granted\":[\"a:x\"]}");
        archiveThenLose(reg().backupsRoot());
        write(dataRoot().resolve(AutoRestore.DISMISSED_MARKER), "cleared on purpose");

        assertFalse(run().restored());
        assertFalse(Files.exists(dataRoot().resolve("achievements/uuid.json")));
    }

    @Test
    void anotherInstallFolderIsNeverCopiedFromUnasked() throws IOException {
        // The card used to offer a sibling instance; nobody is asked now, and it is only a guess.
        write(tmp.resolve("other-instance/dungeontrain/user/templates/theirs.nbt"), "someone's build");

        assertFalse(run().restored());
        assertFalse(Files.exists(dataRoot().resolve("user/templates/theirs.nbt")));
    }

    @Test
    void anArchiveThatBringsNothingBackFallsThroughToTheNext() throws IOException {
        // Ranked first (the mirror): an archive with no builds or progress in it.
        write(dataRoot().resolve("loadouts/uuid.dat"), "loadout");
        archiveThenLose(external());
        // Ranked second: the real one.
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        archiveThenLose(reg().backupsRoot());

        AutoRestore.Outcome outcome = run();

        assertTrue(outcome.restored());
        assertEquals("carriage", Files.readString(dataRoot().resolve("user/templates/a.nbt")));
    }

    @Test
    void nothingToRestoreFromIsNotAnError() {
        // A brand-new player: no data, and no backups either.
        assertFalse(run().restored());
        assertEquals(0, reloads.get());
    }
}
