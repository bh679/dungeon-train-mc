package games.brennan.dungeontrain.data;

import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Source;
import games.brennan.dungeonbackup.core.BackupArchiver;
import games.brennan.dungeonbackup.core.DataRecovery;
import games.brennan.dungeontrain.compat.EnderChestResetBridge;
import games.brennan.dungeontrain.compat.EnderChestResetBridge.EcpDirs;
import games.brennan.enderchestpersistence.StoreMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private Path appData() { return tmp.resolve("home/Library/Application Support"); }

    /** ECP's folders as a client in the default ({@code outside}) mode resolves them. */
    private EcpDirs ecpOutside() {
        return new EcpDirs(
            EnderChestResetBridge.resolveStore(StoreMode.OUTSIDE, false, configDir(), appData()),
            configDir().resolve("enderchestpersistence"));
    }

    private Registration reg() {
        return DungeonTrainBackup.describe(dataRoot(), dtpacks(), Optional.of(ecpOutside())).build();
    }

    private static final String UUID = "7b7774bd-0000-0000-0000-000000000001";

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
        assertEquals("enderchestpersistence", labels.get(2), "every archive's instance-folder chest label");
        assertEquals("enderchestpersistence-store", labels.get(3));
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

    // ---- Ender Chest Persistence ----

    @Test
    void ecpStoreResolvesWhereEcpPutsIt() {
        Path instance = configDir().resolve("enderchestpersistence");
        assertEquals(appData().resolve("enderchestpersistence"),
            EnderChestResetBridge.resolveStore(StoreMode.OUTSIDE, false, configDir(), appData()));
        assertEquals(instance, EnderChestResetBridge.resolveStore(StoreMode.OUTSIDE, true, configDir(), appData()),
            "a dedicated server always stores inside the instance");
        assertEquals(instance, EnderChestResetBridge.resolveStore(StoreMode.INSIDE, false, configDir(), appData()));
        assertEquals(instance, EnderChestResetBridge.resolveStore(StoreMode.OFF, false, configDir(), appData()));
    }

    @Test
    void anInsideStoreIsArchivedOnceUnderTheExistingLabel() {
        Path instance = configDir().resolve("enderchestpersistence");
        Registration r = DungeonTrainBackup.describe(dataRoot(), dtpacks(),
            Optional.of(new EcpDirs(instance, instance))).build();
        List<String> labels = r.sources().stream().map(Source::label).toList();
        assertEquals(List.of("dungeontrain", "dtpacks", "enderchestpersistence"), labels);
    }

    @Test
    void aMissingEcpLeavesTheRestOfTheRegistrationIntact() {
        Registration r = DungeonTrainBackup.describe(dataRoot(), dtpacks(), Optional.empty()).build();
        assertEquals(List.of("dungeontrain", "dtpacks"), r.sources().stream().map(Source::label).toList());
    }

    @Test
    void aBackupHoldsTheChestFromTheOutsideStoreAndItsBackup() throws IOException {
        // The bug: every archive held config/enderchestpersistence/ (abandoned since ECP 0.3.0) and
        // never the app-data store, so no restore point had the player's chest in it.
        Path store = appData().resolve("enderchestpersistence");
        write(store.resolve(UUID + ".dat"), "chest");
        write(store.resolve(UUID + ".dat.bak"), "chest before last save");
        write(configDir().resolve("enderchestpersistence/" + UUID + ".dat"), "stale");

        Path archive = BackupArchiver.create(tmp.resolve("backups"), "dungeontrain", reg().sources(),
            "test", "0.0.0").archive().orElseThrow();

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("enderchestpersistence-store/" + UUID + ".dat"));
            assertNotNull(zip.getEntry("enderchestpersistence-store/" + UUID + ".dat.bak"));
            assertNotNull(zip.getEntry("enderchestpersistence/" + UUID + ".dat"));
        }

        // Restore into a machine that lost both folders: each lands back where it came from.
        deleteTree(store);
        deleteTree(configDir().resolve("enderchestpersistence"));
        BackupArchiver.restore(archive, reg().restoreTargets());
        assertEquals("chest", Files.readString(store.resolve(UUID + ".dat")));
        assertEquals("chest before last save", Files.readString(store.resolve(UUID + ".dat.bak")));
        assertEquals("stale", Files.readString(configDir().resolve("enderchestpersistence/" + UUID + ".dat")));
    }

    @Test
    void anOldArchiveNeverPutsItsInstanceCopyIntoTheLiveStore() throws IOException {
        // Archives written before this fix carry only "enderchestpersistence/…" = the instance folder.
        Path old = tmp.resolve("old-backup.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(old))) {
            out.putNextEntry(new java.util.zip.ZipEntry("enderchestpersistence/" + UUID + ".dat"));
            out.write("stale".getBytes());
            out.closeEntry();
        }
        BackupArchiver.restore(old, reg().restoreTargets());
        assertTrue(Files.isRegularFile(configDir().resolve("enderchestpersistence/" + UUID + ".dat")));
        assertFalse(Files.exists(appData().resolve("enderchestpersistence/" + UUID + ".dat")));
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
