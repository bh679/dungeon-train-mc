package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds player data that an install already lost, and works out what could be put back.
 *
 * <p>{@link PlayerDataPaths} and {@link PlayerDataMigration} stop the loss happening again.
 * They do nothing for the player it already happened to — someone who pressed "Update Pack",
 * watched their build library and every advancement disappear, and now has an install that looks
 * factory-fresh. This class is for them.</p>
 *
 * <p><b>The loss signature has to be narrow</b>, because "this install has no data" is also what a
 * genuinely new install looks like, and nobody wants a recovery prompt on their first launch. So it
 * fires only when all three hold:</p>
 * <ol>
 *   <li>the live root holds no builds, no advancements and no stats;</li>
 *   <li>the pre-move {@code config/} locations are empty too — if they aren't, nothing was lost and
 *       {@link PlayerDataMigration} is about to move them across;</li>
 *   <li>a candidate exists to restore <em>from</em>.</li>
 * </ol>
 *
 * <p>Candidates are ranked: this install's own backups first (certain provenance), then sibling
 * instance folders (a plausible guess). Sibling candidates are <b>never restored automatically</b>
 * — the folder next door may belong to a different pack or a different person, so the player is
 * shown the full path and decides.</p>
 *
 * <p>Read-only and {@code FMLPaths}-free: every entry point takes explicit roots, so the scan is
 * drivable from JUnit with {@code @TempDir}.</p>
 */
public final class PlayerDataRecovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How many directory levels above the instance to look for sibling installs. */
    static final int SIBLING_LEVELS = 2;

    /** Cap on sibling directories examined per level, so a huge folder can't stall the title screen. */
    static final int MAX_SIBLINGS_PER_LEVEL = 64;

    /** Where a candidate came from. Ordinal order is the ranking the player is offered. */
    public enum Kind { EXTERNAL_BACKUP, BACKUP, SIBLING_INSTANCE }

    /**
     * Something worth restoring.
     *
     * @param kind        backup archive, or another instance folder on this machine
     * @param path        the archive, or the sibling instance root — shown to the player verbatim
     * @param description short human-readable label
     */
    public record Candidate(Kind kind, Path path, String description) {}

    private PlayerDataRecovery() {}

    /**
     * Does this install look like one that lost its data?
     *
     * @param dataRoot    {@code <gameDir>/dungeontrain/}
     * @param configDir   the config directory, checked for data the migration hasn't moved yet
     * @param dtpacksRoot {@code <gameDir>/dtpacks/} — saved packages are builds too, and they have
     *                    always lived outside {@code config/}, so an install holding one lost nothing
     */
    public static boolean looksEmptied(Path dataRoot, Path configDir, Path dtpacksRoot) {
        if (hasLiveData(dataRoot)) return false;
        if (hasSavedPackages(dtpacksRoot)) return false;
        // Data still sitting in config/ isn't lost — it's about to be migrated.
        return !PlayerDataMigration.hasLegacyData(configDir);
    }

    /**
     * Does {@code dtpacks/} hold an actual saved package — an extracted folder with content, or a
     * {@code .zip} snapshot?
     *
     * <p>Deliberately <b>not</b> "does it contain any file". {@code UserContentImporter} writes a
     * {@code README.txt} into that folder on first run, so a plain file check is true on every
     * install and would suppress the recovery offer for everyone — including the player who just
     * lost everything. Only the two shapes {@code PackageRegistry} recognises as a package count.</p>
     */
    static boolean hasSavedPackages(Path dtpacksRoot) {
        if (dtpacksRoot == null || !Files.isDirectory(dtpacksRoot)) return false;
        try (var children = Files.list(dtpacksRoot)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    if (PlayerDataMigration.containsAnyFile(child)) return true;
                } else if (child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    return true;
                }
            }
        } catch (IOException | SecurityException e) {
            LOGGER.debug("[DungeonTrain] Recovery: couldn't list {}: {}", dtpacksRoot, e.toString());
        }
        return false;
    }

    /** Is there any build, advancement or stat at the live root? */
    static boolean hasLiveData(Path dataRoot) {
        for (String sub : List.of(PlayerDataPaths.USER,
                PlayerDataPaths.ACHIEVEMENTS, PlayerDataPaths.STATS)) {
            if (PlayerDataMigration.containsAnyFile(dataRoot.resolve(sub))) return true;
        }
        return false;
    }

    /**
     * Everything that could be restored, best first.
     *
     * @param gameDir the instance root, whose parents are searched for sibling installs
     * @param externalBackupsRoot the out-of-instance backup folder, or {@code null} if there is
     *                            none. Passed in rather than resolved here so this stays a pure
     *                            function of its arguments — reading the real OS app-data folder
     *                            made the result depend on whatever else is on the machine.
     */
    public static List<Candidate> findCandidates(Path dataRoot, Path gameDir, Path externalBackupsRoot) {
        List<Candidate> found = new ArrayList<>();
        // Ranked first: an archive this install wrote, kept OUTSIDE the instance. It has the same
        // certain provenance as an in-instance backup and survives strictly more — it is the only
        // candidate that exists at all when the instance was deleted and reinstalled.
        for (Path external : externalBackupsRoot == null
                ? List.<Path>of() : PlayerDataBackup.listArchives(externalBackupsRoot)) {
            found.add(new Candidate(Kind.EXTERNAL_BACKUP, external, external.getFileName().toString()));
        }
        for (Path archive : PlayerDataBackup.listArchives(dataRoot.resolve(PlayerDataPaths.BACKUPS))) {
            found.add(new Candidate(Kind.BACKUP, archive, archive.getFileName().toString()));
        }
        found.addAll(findSiblingInstances(gameDir));
        return List.copyOf(found);
    }

    /**
     * Other Minecraft instances on this machine that still hold Dungeon Train data.
     *
     * <p>A player who "updated" by installing the pack into a fresh instance still has the old one
     * next door — that is the single most likely place their builds still exist. Launchers nest
     * instances one or two levels up ({@code .../Instances/<pack>/} for CurseForge,
     * {@code .../profiles/<pack>/} for Modrinth), so both levels are searched.</p>
     *
     * <p>Bounded on every axis and read-only: two levels, a capped number of entries per level, no
     * symlink following, and nothing below an instance root is walked beyond the two folders that
     * would hold data.</p>
     */
    static List<Candidate> findSiblingInstances(Path gameDir) {
        List<Candidate> found = new ArrayList<>();
        Path self;
        try {
            self = gameDir.toAbsolutePath().normalize();
        } catch (Exception e) {
            return List.of();
        }
        Path level = self.getParent();
        for (int depth = 0; depth < SIBLING_LEVELS && level != null; depth++, level = level.getParent()) {
            found.addAll(siblingsIn(level, self));
        }
        found.sort(Comparator.comparing(c -> c.path().toString()));
        return found;
    }

    private static List<Candidate> siblingsIn(Path parent, Path self) {
        List<Candidate> found = new ArrayList<>();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return found;
        int examined = 0;
        try (var children = Files.list(parent)) {
            for (Path child : children.toList()) {
                if (++examined > MAX_SIBLINGS_PER_LEVEL) break;
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                Path candidate = child.toAbsolutePath().normalize();
                if (candidate.equals(self)) continue;
                String what = dataHeldBy(candidate);
                if (what != null) found.add(new Candidate(Kind.SIBLING_INSTANCE, candidate, what));
            }
        } catch (IOException | SecurityException e) {
            LOGGER.debug("[DungeonTrain] Recovery: couldn't list {}: {}", parent, e.toString());
        }
        return found;
    }

    /**
     * A short description of the Dungeon Train data an instance folder holds, or {@code null} when
     * it holds none. Checks both the current and the pre-relocation layouts, since the instance
     * that still has the player's builds is by definition the one that never got updated.
     */
    static String dataHeldBy(Path instanceRoot) {
        Path data = instanceRoot.resolve(PlayerDataPaths.ROOT_DIR);
        Path config = instanceRoot.resolve("config");
        boolean builds = PlayerDataMigration.containsAnyFile(data.resolve(PlayerDataPaths.USER))
            || PlayerDataMigration.containsAnyFile(config.resolve("dungeontrain").resolve("user"));
        boolean profile = PlayerDataMigration.containsAnyFile(data.resolve(PlayerDataPaths.ACHIEVEMENTS))
            || PlayerDataMigration.containsAnyFile(config.resolve("dungeontrain-achievements"));
        if (builds && profile) return "builds and progress";
        if (builds) return "builds";
        if (profile) return "progress";
        return null;
    }

    /**
     * Restore {@code candidate} into {@code dataRoot}.
     *
     * <p>Additive in both directions: an archive restore skips entries whose file already exists,
     * and a sibling restore copies rather than moves, so the instance it came from is left exactly
     * as it was. Nothing here can make the player worse off than before they pressed the button.</p>
     *
     * @return the number of files written
     */
    public static int restore(Candidate candidate, Path dataRoot, Path dtpacksRoot) throws IOException {
        return switch (candidate.kind()) {
            // Both are archives this install wrote, in the same format — only the folder differs.
            case EXTERNAL_BACKUP, BACKUP ->
                PlayerDataBackup.restore(candidate.path(), backupTargets(dataRoot, dtpacksRoot));
            case SIBLING_INSTANCE -> restoreFromInstance(candidate.path(), dataRoot);
        };
    }

    /**
     * The label → directory mapping backups are written with, and therefore restored into. Must
     * stay in step with {@code PlayerDataBackupHook.sources()} — the labels are what tell a restore
     * which tree each archive entry belongs to.
     */
    public static List<PlayerDataBackup.Source> backupTargets(Path dataRoot, Path dtpacksRoot) {
        return List.of(
            new PlayerDataBackup.Source(PlayerDataPaths.ROOT_DIR, dataRoot),
            new PlayerDataBackup.Source("dtpacks", dtpacksRoot));
    }

    /**
     * Copy Dungeon Train data out of another instance folder, from whichever layout it is in.
     * Existing files win, so this can only ever add.
     */
    private static int restoreFromInstance(Path instanceRoot, Path dataRoot) throws IOException {
        int written = 0;
        Path otherData = instanceRoot.resolve(PlayerDataPaths.ROOT_DIR);
        Path otherConfig = instanceRoot.resolve("config");
        for (PlayerDataPaths.Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            if (relocation.kind() != PlayerDataPaths.Kind.DIRECTORY) continue;
            Path to = relocation.newPath(dataRoot);
            // Newer layout first, then the pre-relocation one — an un-updated instance has the latter.
            written += copyInto(relocation.newPath(otherData), to);
            written += copyInto(relocation.legacyPath(otherConfig), to);
        }
        LOGGER.info("[DungeonTrain] Recovery: copied {} file(s) from {}", written, instanceRoot);
        return written;
    }

    private static int copyInto(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return 0;
        return games.brennan.dungeontrain.editor.PackageSaveOps.copyTree(from, to).copied();
    }
}
