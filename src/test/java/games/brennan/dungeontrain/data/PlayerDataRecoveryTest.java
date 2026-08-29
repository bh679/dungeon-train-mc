package games.brennan.dungeontrain.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loss signature and the candidate scan.
 *
 * <p>The signature has to be narrow: "this install has no data" is also what a brand-new install
 * looks like, and a recovery prompt on someone's first launch would be nonsense. Most of these
 * tests are about the cases where it must stay quiet.</p>
 */
class PlayerDataRecoveryTest {

    @TempDir
    Path tmp;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path dataRoot() {
        return tmp.resolve("instance/dungeontrain");
    }

    private Path configDir() {
        return tmp.resolve("instance/config");
    }

    /** A temp stand-in for the OS app-data folder, so tests never see this machine's real backups. */
    private Path externalRoot() {
        return tmp.resolve("external-backups");
    }

    private Path dtpacksRoot() {
        return tmp.resolve("instance/dtpacks");
    }

    @Test
    void aFreshInstallLooksEmptiedButOffersNothing() {
        assertTrue(PlayerDataRecovery.looksEmptied(dataRoot(), configDir(), dtpacksRoot()));
        assertEquals(List.of(), PlayerDataRecovery.findCandidates(dataRoot(), tmp.resolve("instance"), externalRoot()),
            "nothing to restore from means no prompt, however empty the install is");
    }

    @Test
    void anInstallWithBuildsIsNotEmptied() throws IOException {
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        assertFalse(PlayerDataRecovery.looksEmptied(dataRoot(), configDir(), dtpacksRoot()));
    }

    @Test
    void anInstallWithAdvancementsIsNotEmptied() throws IOException {
        write(dataRoot().resolve("achievements/uuid.json"), "granted");
        assertFalse(PlayerDataRecovery.looksEmptied(dataRoot(), configDir(), dtpacksRoot()));
    }

    @Test
    void dataStillWaitingInConfigIsNotALoss() throws IOException {
        // The migration is about to move this across on the very same launch.
        write(configDir().resolve("dungeontrain/user/templates/a.nbt"), "carriage");
        assertFalse(PlayerDataRecovery.looksEmptied(dataRoot(), configDir(), dtpacksRoot()));
    }

    @Test
    void savedPackagesAreNotALoss() throws IOException {
        // dtpacks/ has always lived outside config/, so a pack update never touched it. A player
        // whose builds are all in a saved package still has them.
        write(dtpacksRoot().resolve("My Pack/templates/a.nbt"), "carriage");
        assertFalse(PlayerDataRecovery.looksEmptied(dataRoot(), configDir(), dtpacksRoot()));
    }

    @Test
    void aZippedPackageSnapshotIsAlsoNotALoss() throws IOException {
        write(dtpacksRoot().resolve("My Pack.zip"), "PK");
        assertTrue(PlayerDataRecovery.hasSavedPackages(dtpacksRoot()));
        assertFalse(PlayerDataRecovery.looksEmptied(dataRoot(), configDir(), dtpacksRoot()));
    }

    @Test
    void theDtpacksReadmeIsNotASavedPackage() throws IOException {
        // UserContentImporter writes this on first run, so it is present on EVERY install. Counting
        // it as a saved package suppressed the recovery offer for everyone — caught on a live boot,
        // where the prompt never appeared for an install that had genuinely lost everything.
        write(dtpacksRoot().resolve("README.txt"), "Dungeon Train packages folder.");
        assertFalse(PlayerDataRecovery.hasSavedPackages(dtpacksRoot()));
        assertTrue(PlayerDataRecovery.looksEmptied(dataRoot(), configDir(), dtpacksRoot()));
    }

    @Test
    void anEmptyPackageFolderIsNotASavedPackage() throws IOException {
        Files.createDirectories(dtpacksRoot().resolve("Abandoned Pack"));
        assertFalse(PlayerDataRecovery.hasSavedPackages(dtpacksRoot()));
    }

    @Test
    void backupTargetsMatchTheLabelsBackupsAreWrittenWith() {
        // If these two lists drift, a restore silently writes nothing: every archive entry's label
        // fails to match a target and is skipped.
        List<PlayerDataBackup.Source> targets =
            PlayerDataRecovery.backupTargets(dataRoot(), dtpacksRoot());
        List<String> labels = targets.stream().map(PlayerDataBackup.Source::label).toList();
        assertEquals(List.of(PlayerDataPaths.ROOT_DIR, "dtpacks"), labels);
        assertNotNull(PlayerDataBackup.targetFor(
            PlayerDataPaths.ROOT_DIR + "/user/templates/a.nbt", targets));
        assertNotNull(PlayerDataBackup.targetFor("dtpacks/My Pack/templates/a.nbt", targets));
    }

    @Test
    void ranksAnOutOfInstanceBackupAboveEverythingElse() throws IOException {
        // The only candidate that still exists when the instance itself was deleted, so it leads.
        write(externalRoot().resolve("dungeontrain-backup-20260101-000000.zip"), "z");
        write(dataRoot().resolve("backups/dungeontrain-backup-20260102-000000.zip"), "z");
        write(tmp.resolve("instance-old/config/dungeontrain/user/a.nbt"), "carriage");

        List<PlayerDataRecovery.Candidate> found = PlayerDataRecovery.findCandidates(
            dataRoot(), tmp.resolve("instance"), externalRoot());

        assertEquals(3, found.size());
        assertEquals(PlayerDataRecovery.Kind.EXTERNAL_BACKUP, found.get(0).kind());
        assertEquals(PlayerDataRecovery.Kind.BACKUP, found.get(1).kind());
        assertEquals(PlayerDataRecovery.Kind.SIBLING_INSTANCE, found.get(2).kind());
    }

    @Test
    void candidateExposesItsDisplayFormsAsStrings() {
        // Component.translatable throws at RENDER time on a non-String argument, so a Path passed
        // straight in compiles and then crashes the screen — which is how clicking "What happened?"
        // took the game down. These accessors are what the screen uses instead.
        PlayerDataRecovery.Candidate c = new PlayerDataRecovery.Candidate(
            PlayerDataRecovery.Kind.EXTERNAL_BACKUP,
            Path.of("/tmp/DungeonTrain/backups/dungeontrain-backup-20260101-000000.zip"), "x");
        assertEquals("dungeontrain-backup-20260101-000000.zip", c.fileName());
        assertEquals("/tmp/DungeonTrain/backups", c.folder());
        assertEquals("/tmp/DungeonTrain/backups/dungeontrain-backup-20260101-000000.zip", c.location());

        // A root path has no parent — empty, never the string "null".
        PlayerDataRecovery.Candidate root = new PlayerDataRecovery.Candidate(
            PlayerDataRecovery.Kind.SIBLING_INSTANCE, Path.of("/"), "y");
        assertEquals("", root.folder());
    }

    @Test
    void findsThisInstallsOwnBackups() throws IOException {
        write(dataRoot().resolve("backups/dungeontrain-backup-20260101-000000.zip"), "z");

        List<PlayerDataRecovery.Candidate> found =
            PlayerDataRecovery.findCandidates(dataRoot(), tmp.resolve("instance"), externalRoot());

        assertEquals(1, found.size());
        assertEquals(PlayerDataRecovery.Kind.BACKUP, found.get(0).kind());
    }

    @Test
    void findsASiblingInstanceInEitherLayout() throws IOException {
        // The un-updated instance next door, still on the old config/ layout.
        write(tmp.resolve("instance-old/config/dungeontrain/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("instance-old/config/dungeontrain-achievements/uuid.json"), "granted");
        // And one already on the new layout.
        write(tmp.resolve("instance-new/dungeontrain/user/templates/b.nbt"), "carriage");

        List<PlayerDataRecovery.Candidate> found =
            PlayerDataRecovery.findSiblingInstances(tmp.resolve("instance"));

        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(c -> c.kind() == PlayerDataRecovery.Kind.SIBLING_INSTANCE));
        // Sorted by path, so the listing is stable across launches rather than filesystem order.
        assertEquals("builds", found.get(0).description(), "instance-new, on the current layout");
        assertEquals("builds and progress", found.get(1).description(),
            "instance-old, still on the pre-relocation config/ layout");
    }

    @Test
    void backupModeSurvivesAnUnknownStoredValue() {
        // The mode is read on the server thread including where no client config exists, so an
        // unreadable or unknown value must resolve to the default, never to "no backups".
        assertEquals(games.brennan.dungeontrain.data.BackupMode.EXTERNAL,
            games.brennan.dungeontrain.data.BackupMode.DEFAULT);
        assertEquals(games.brennan.dungeontrain.data.BackupMode.DEFAULT,
            games.brennan.dungeontrain.data.BackupMode.parse("nonsense"));
        assertEquals(games.brennan.dungeontrain.data.BackupMode.DEFAULT,
            games.brennan.dungeontrain.data.BackupMode.parse(null));
        assertEquals(games.brennan.dungeontrain.data.BackupMode.OFF,
            games.brennan.dungeontrain.data.BackupMode.parse("off"));
        assertFalse(games.brennan.dungeontrain.data.BackupMode.OFF.writesAnything());
        assertFalse(games.brennan.dungeontrain.data.BackupMode.INSTANCE.writesOutsideTheInstance());
        assertTrue(games.brennan.dungeontrain.data.BackupMode.EXTERNAL.writesOutsideTheInstance());
    }

    @Test
    void ranksBackupsAheadOfSiblings() throws IOException {
        write(dataRoot().resolve("backups/dungeontrain-backup-20260101-000000.zip"), "z");
        write(tmp.resolve("instance-old/config/dungeontrain/user/a.nbt"), "carriage");

        List<PlayerDataRecovery.Candidate> found =
            PlayerDataRecovery.findCandidates(dataRoot(), tmp.resolve("instance"), externalRoot());

        assertEquals(2, found.size());
        assertEquals(PlayerDataRecovery.Kind.BACKUP, found.get(0).kind(),
            "our own archive has certain provenance; the folder next door is a guess");
    }

    @Test
    void ignoresAnInstanceWithNoDungeonTrainData() throws IOException {
        write(tmp.resolve("some-other-game/config/whatever.toml"), "x");
        assertNull(PlayerDataRecovery.dataHeldBy(tmp.resolve("some-other-game")));
        assertEquals(List.of(), PlayerDataRecovery.findSiblingInstances(tmp.resolve("instance")));
    }

    @Test
    void neverOffersTheInstanceItIsRunningIn() throws IOException {
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        assertFalse(PlayerDataRecovery.findSiblingInstances(tmp.resolve("instance")).stream()
            .anyMatch(c -> c.path().equals(tmp.resolve("instance").toAbsolutePath().normalize())));
    }

    @Test
    void restoringFromASiblingCopiesRatherThanMoves() throws IOException {
        Path sibling = tmp.resolve("instance-old");
        write(sibling.resolve("config/dungeontrain/user/templates/a.nbt"), "carriage");
        write(sibling.resolve("config/dungeontrain-achievements/uuid.json"), "granted");
        PlayerDataRecovery.Candidate candidate = new PlayerDataRecovery.Candidate(
            PlayerDataRecovery.Kind.SIBLING_INSTANCE, sibling, "builds and progress");

        int written = PlayerDataRecovery.restore(candidate, dataRoot(), tmp.resolve("instance/dtpacks"));

        assertEquals(2, written);
        assertEquals("carriage", Files.readString(dataRoot().resolve("user/templates/a.nbt")));
        assertEquals("granted", Files.readString(dataRoot().resolve("achievements/uuid.json")));
        assertTrue(Files.isRegularFile(sibling.resolve("config/dungeontrain/user/templates/a.nbt")),
            "the instance we recovered from must be left exactly as it was");
    }

    @Test
    void restoringNeverOverwritesWhatIsAlreadyThere() throws IOException {
        Path sibling = tmp.resolve("instance-old");
        write(sibling.resolve("config/dungeontrain/user/templates/a.nbt"), "old");
        write(dataRoot().resolve("user/templates/a.nbt"), "MINE");
        PlayerDataRecovery.Candidate candidate = new PlayerDataRecovery.Candidate(
            PlayerDataRecovery.Kind.SIBLING_INSTANCE, sibling, "builds");

        PlayerDataRecovery.restore(candidate, dataRoot(), tmp.resolve("instance/dtpacks"));

        assertEquals("MINE", Files.readString(dataRoot().resolve("user/templates/a.nbt")));
    }
}
