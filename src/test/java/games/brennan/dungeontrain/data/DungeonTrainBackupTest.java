package games.brennan.dungeontrain.data;

import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Source;
import games.brennan.dungeonbackup.core.DataRecovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Dungeon Train tells Dungeon Backup, and the probes it supplies.
 *
 * <p>The engine itself is tested in {@code dungeonbackup-mc}. What can go wrong on THIS side is
 * the description: a relocation table that drifts from the stores, a probe that suppresses the
 * recovery card for everyone, labels that stop matching the archives already on disk.</p>
 */
class DungeonTrainBackupTest {

    @TempDir
    Path tmp;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path dataRoot() { return tmp.resolve("instance/dungeontrain"); }
    private Path dtpacks() { return tmp.resolve("instance/dtpacks"); }
    private Path configDir() { return tmp.resolve("instance/config"); }

    private Registration reg() {
        return DungeonTrainBackup.describe(dataRoot(), dtpacks()).build();
    }

    @Test
    void theRegistrationCarriesTheRelocationTableVerbatim() {
        // PlayerDataPathsTest guards RELOCATIONS against each store; this guards that the library
        // actually receives that list — the migration drains what it is given, nothing else.
        assertEquals(PlayerDataPaths.RELOCATIONS, reg().relocations());
    }

    @Test
    void labelsMatchTheArchivesAlreadyOnDisk() {
        // Existing dungeontrain-backup-*.zip archives carry "dungeontrain/…" and "dtpacks/…"
        // entries. Renaming either label would make every one of them restore nothing.
        List<String> labels = reg().sources().stream().map(Source::label).toList();
        assertEquals("dungeontrain", labels.get(0));
        assertEquals("dtpacks", labels.get(1));
        assertTrue(reg().excludeTopLevel().contains(PlayerDataPaths.BACKUPS));
        assertEquals("DungeonTrain", reg().externalDirName(), "the mirror folder every player already has");
        assertEquals("dtbackup", reg().backupCommandAlias());
        assertEquals("dtrestore", reg().restoreCommandAlias());
        assertEquals("dungeontrain.backups", reg().legacyOverrideProperty());
    }

    @Test
    void aFreshInstallLooksEmptied() {
        assertTrue(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void buildsAdvancementsOrStatsCountAsLiveData() throws IOException {
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        assertFalse(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void savedPackagesAreNotALoss() throws IOException {
        // dtpacks/ has always lived outside config/, so a pack update never touched it.
        write(dtpacks().resolve("My Pack/templates/a.nbt"), "carriage");
        assertFalse(DataRecovery.looksEmptied(reg(), configDir()));
        assertTrue(DungeonTrainBackup.hasSavedPackages(dtpacks()));
    }

    @Test
    void aZippedPackageSnapshotIsAlsoNotALoss() throws IOException {
        write(dtpacks().resolve("My Pack.zip"), "PK");
        assertTrue(DungeonTrainBackup.hasSavedPackages(dtpacks()));
    }

    @Test
    void theDtpacksReadmeIsNotASavedPackage() throws IOException {
        // UserContentImporter writes this on first run, so it is present on EVERY install. Counting
        // it as a saved package suppressed the recovery offer for everyone — caught on a live boot.
        write(dtpacks().resolve("README.txt"), "Dungeon Train packages folder.");
        assertFalse(DungeonTrainBackup.hasSavedPackages(dtpacks()));
        assertTrue(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void anEmptyPackageFolderIsNotASavedPackage() throws IOException {
        Files.createDirectories(dtpacks().resolve("Abandoned Pack"));
        assertFalse(DungeonTrainBackup.hasSavedPackages(dtpacks()));
    }

    @Test
    void siblingProbeDescribesWhatAnInstanceHoldsInEitherLayout() throws IOException {
        write(tmp.resolve("old/config/dungeontrain/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("old/config/dungeontrain-achievements/uuid.json"), "granted");
        write(tmp.resolve("new/dungeontrain/user/templates/b.nbt"), "carriage");
        write(tmp.resolve("other/config/whatever.toml"), "x");

        assertEquals("builds and progress", reg().dataHeldBy(tmp.resolve("old")).orElseThrow());
        assertEquals("builds", reg().dataHeldBy(tmp.resolve("new")).orElseThrow());
        assertTrue(reg().dataHeldBy(tmp.resolve("other")).isEmpty());
    }
}
