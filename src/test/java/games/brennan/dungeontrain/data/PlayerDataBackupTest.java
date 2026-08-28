package games.brennan.dungeontrain.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rolling restore points: what goes in an archive, when one is skipped, and what pruning keeps. */
class PlayerDataBackupTest {

    @TempDir
    Path tmp;

    private Path backups() {
        return tmp.resolve("backups");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<PlayerDataBackup.Source> sources() {
        return List.of(
            new PlayerDataBackup.Source("user", tmp.resolve("data/user")),
            new PlayerDataBackup.Source("achievements", tmp.resolve("data/achievements")));
    }

    /** The shape the game actually uses: the whole data root, with backups/ excluded. */
    private List<PlayerDataBackup.Source> rootSource() {
        return List.of(new PlayerDataBackup.Source(
            "dungeontrain", tmp.resolve("data"), java.util.Set.of("backups")));
    }

    @Test
    void archivesEverySourceUnderItsOwnLabel() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("data/achievements/uuid.json"), "granted");

        PlayerDataBackup.Result result =
            PlayerDataBackup.create(backups(), sources(), "launch", "1.2.3");

        assertTrue(result.wrote());
        assertEquals(2, result.fileCount());
        try (ZipFile zip = new ZipFile(result.archive().orElseThrow().toFile())) {
            assertNotNull(zip.getEntry("user/templates/a.nbt"));
            assertNotNull(zip.getEntry("achievements/uuid.json"));
            String manifest = new String(
                zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            assertTrue(manifest.contains("\"modVersion\": \"1.2.3\""));
            assertTrue(manifest.contains("\"reason\": \"launch\""));
            assertTrue(manifest.contains(result.digest()));
        }
    }

    @Test
    void archivingTheWholeRootCatchesLooseFilesAndExcludesBackups() throws IOException {
        // The loose files under the root were missed by an earlier per-folder enumeration, so
        // dtpacks-state.json (which package is active) and the queued uploads were in no archive.
        write(tmp.resolve("data/dtpacks-state.json"), "{}");
        write(tmp.resolve("data/outbox/relay-outbox.json"), "[]");
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("data/backups/dungeontrain-backup-20260101-000000.zip"), "old archive");

        PlayerDataBackup.Result result = PlayerDataBackup.create(
            tmp.resolve("data/backups"), rootSource(), "world-load", "1.2.3");

        assertEquals(3, result.fileCount(), "the previous archive must not be inside this one");
        try (ZipFile zip = new ZipFile(result.archive().orElseThrow().toFile())) {
            assertNotNull(zip.getEntry("dungeontrain/dtpacks-state.json"));
            assertNotNull(zip.getEntry("dungeontrain/outbox/relay-outbox.json"));
            assertNotNull(zip.getEntry("dungeontrain/user/templates/a.nbt"));
            assertNull(zip.getEntry(
                "dungeontrain/backups/dungeontrain-backup-20260101-000000.zip"));
        }
    }

    @Test
    void writesNothingWhenThereIsNothingToBackUp() throws IOException {
        PlayerDataBackup.Result result =
            PlayerDataBackup.create(backups(), sources(), "launch", "1.2.3");

        assertFalse(result.wrote());
        assertFalse(Files.exists(backups()), "an empty install shouldn't even make the folder");
    }

    @Test
    void skipsWhenNothingChangedSinceTheNewestArchive() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        assertTrue(PlayerDataBackup.create(backups(), sources(), "launch", "1.2.3").wrote());

        PlayerDataBackup.Result second =
            PlayerDataBackup.create(backups(), sources(), "launch", "1.2.3");

        assertFalse(second.wrote(), "an idle launch must not cost a zip");
        assertEquals(1, PlayerDataBackup.listArchives(backups()).size());
    }

    @Test
    void archivesAgainOnceTheContentChanges() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        PlayerDataBackup.create(backups(), sources(), "launch", "1.2.3");

        write(tmp.resolve("data/user/templates/b.nbt"), "another carriage");
        PlayerDataBackup.Result second =
            PlayerDataBackup.create(backups(), sources(), "launch", "1.2.3");

        assertTrue(second.wrote());
        assertEquals(2, second.fileCount());
    }

    @Test
    void pruningKeepsTheNewestAndAlwaysTheOldest() throws IOException {
        Files.createDirectories(backups());
        // Named by timestamp, and the name IS the ordering — so these stand in for real archives.
        for (int i = 1; i <= PlayerDataBackup.KEEP_NEWEST + 5; i++) {
            write(backups().resolve(String.format("dungeontrain-backup-202601%02d-000000.zip", i)), "z");
        }
        Path oldest = backups().resolve("dungeontrain-backup-20260101-000000.zip");
        Path newest = backups().resolve(
            String.format("dungeontrain-backup-202601%02d-000000.zip", PlayerDataBackup.KEEP_NEWEST + 5));

        PlayerDataBackup.prune(backups());

        List<Path> left = PlayerDataBackup.listArchives(backups());
        assertEquals(PlayerDataBackup.KEEP_NEWEST + 1, left.size());
        assertTrue(left.contains(newest));
        assertTrue(left.contains(oldest),
            "the oldest archive is the pre-migration snapshot — the only record of what was there first");
    }

    @Test
    void restoreOnlyAddsMissingFiles() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("data/achievements/uuid.json"), "granted");
        Path archive = PlayerDataBackup.create(backups(), sources(), "launch", "1.2.3")
            .archive().orElseThrow();

        Path restoreRoot = tmp.resolve("restored");
        write(restoreRoot.resolve("user/templates/a.nbt"), "MINE, newer");
        List<PlayerDataBackup.Source> targets = List.of(
            new PlayerDataBackup.Source("user", restoreRoot.resolve("user")),
            new PlayerDataBackup.Source("achievements", restoreRoot.resolve("achievements")));

        int written = PlayerDataBackup.restore(archive, targets);

        assertEquals(1, written);
        assertEquals("MINE, newer", Files.readString(restoreRoot.resolve("user/templates/a.nbt")),
            "a restore must never overwrite what the player has now");
        assertEquals("granted", Files.readString(restoreRoot.resolve("achievements/uuid.json")));
    }

    @Test
    void restoreRefusesToEscapeItsTargetDirectory() {
        List<PlayerDataBackup.Source> targets =
            List.of(new PlayerDataBackup.Source("user", tmp.resolve("restored/user")));

        // Archive entry names are player-supplied data — a crafted zip must not write outside.
        assertNull(PlayerDataBackup.targetFor("user/../../evil.txt", targets));
        assertNull(PlayerDataBackup.targetFor("unknown-label/a.nbt", targets));
        assertNull(PlayerDataBackup.targetFor("no-slash", targets));
        assertNotNull(PlayerDataBackup.targetFor("user/templates/a.nbt", targets));
    }
}
