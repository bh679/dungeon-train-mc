package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeonbackup.api.DungeonBackup;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Trigger;
import games.brennan.dungeonbackup.core.FileTrees;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.compat.EnderChestResetBridge;
import games.brennan.dungeontrain.editor.UserContentMigration;
import games.brennan.dungeontrain.template.TemplateStores;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Dungeon Train's one registration with Dungeon Backup — the only place DT describes its player
 * data to the library, and therefore the only place that changes if the shape of that data does.
 *
 * <p>Everything the old in-tree backup system enumerated in five places ({@code RELOCATIONS},
 * {@code sources()}, {@code hasLiveData}, {@code backupTargets}, {@code dataHeldBy}) is now
 * derived from this. A new folder under {@link PlayerDataPaths#root()} is archived and restored
 * with no change here; only a <em>new legacy location</em> (something that used to live in
 * {@code config/}) or a new out-of-root tree needs a line.</p>
 *
 * <p>Registered from the mod constructor so it exists before the first server-start event, which
 * is when the library migrates and takes the world-load restore point.</p>
 */
public final class DungeonTrainBackup {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Translation key for what DT's data is, in the recovery card's sentences. */
    public static final String DATA_DESCRIPTION_KEY = "gui.dungeontrain.backup.data_description";

    /** The archive label DT's data root is written under. Existing archives depend on it. */
    public static final String ROOT_LABEL = PlayerDataPaths.ROOT_DIR;

    private static Registration registration;

    private DungeonTrainBackup() {}

    /** Register once. Safe to call again — the library replaces the earlier entry. */
    public static synchronized Registration register() {
        registration = describe(PlayerDataPaths.root(), PlayerDataPaths.dtpacksRoot()).register();
        return registration;
    }

    /**
     * Everything DT tells the library, against explicit roots — the only {@code FMLPaths}-free
     * seam, and therefore the one {@code DungeonTrainBackupTest} drives with {@code @TempDir}.
     */
    static Registration.Builder describe(Path dataRoot, Path dtpacks) {
        Registration.Builder builder = DungeonBackup.register(DungeonTrain.MOD_ID)
            .displayName("Dungeon Train")
            .modVersion(() -> VersionInfo.VERSION)
            .dataRoot(dataRoot)
            .rootLabel(ROOT_LABEL)
            .excludeTopLevel(PlayerDataPaths.BACKUPS)
            // A player's saved packages are their builds too, and they live outside the data root.
            .alsoBackUp("dtpacks", dtpacks)
            // Keeps <AppData>/DungeonTrain/backups — where every existing mirror already is.
            .externalDirName(PlayerDataPaths.EXTERNAL_DIR)
            .relocations(PlayerDataPaths.RELOCATIONS)
            .livenessProbe(root -> hasLiveData(root) || hasSavedPackages(dtpacks))
            .siblingProbe(DungeonTrainBackup::dataHeldBy)
            // Pre-0.125 layout first, so its files are at their final names before the library's
            // pass walks config/. Was a direct call inside PlayerDataMigration.runOnce().
            .beforeMigration(UserContentMigration::runOnce)
            // Recovered templates are on disk but not in any cache — without the barrier they
            // wouldn't appear in the editor until the next restart. The importer pass runs too:
            // a backup can carry dtpacks/<name>.zip, which only becomes a package once extracted.
            .onRestored(() -> TemplateStores.reloadAll(true))
            // Dev builds keep tripping the card: a working copy routinely has an empty data root.
            .promptSuppressed(DungeonTrain::isDevBuild)
            .dataDescriptionKey(DATA_DESCRIPTION_KEY)
            .commandAliases("dtbackup", "dtrestore")
            // Operators who set these before the extraction keep working.
            .legacyOverrideNames("dungeontrain.backups", "DUNGEONTRAIN_BACKUPS")
            .backupOn(Trigger.WORLD_LOAD, Trigger.PLAYER_DEATH, Trigger.SESSION_END);

        // ECP's per-player stash lives in config/enderchestpersistence/ — INSIDE the folder
        // launchers wipe — and survives a new world, which is exactly the loss this system exists
        // for. Pulled in as an extra tree until ECP registers itself. Optional: an ECP that moved
        // its seam degrades to "not backed up", never to a failed registration.
        EnderChestResetBridge.stashDir().ifPresent(dir -> builder.alsoBackUp("enderchestpersistence", dir));
        return builder;
    }

    /** The live registration, or the one {@link #register()} would build. */
    public static Registration registration() {
        Registration r = registration;
        return r != null ? r : register();
    }

    /**
     * Ask for a restore point off the server thread. Called from {@code BuilderSave} on a
     * successful save — the one moment where there is brand-new work that exists nowhere else.
     */
    public static void requestBackup(String reason) {
        DungeonBackup.requestBackup(DungeonTrain.MOD_ID, reason);
    }

    /** Every DT archive, newest first, in-instance before the out-of-instance mirror. */
    public static List<Path> archives() {
        return DungeonBackup.archives(DungeonTrain.MOD_ID);
    }

    // ---- Probes ----

    /** Is there any build, advancement or stat at the live root? */
    static boolean hasLiveData(Path dataRoot) {
        for (String sub : List.of(PlayerDataPaths.USER, PlayerDataPaths.ACHIEVEMENTS, PlayerDataPaths.STATS)) {
            if (FileTrees.containsAnyFile(dataRoot.resolve(sub))) return true;
        }
        return false;
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
                    if (FileTrees.containsAnyFile(child)) return true;
                } else if (child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    return true;
                }
            }
        } catch (IOException | SecurityException e) {
            LOGGER.debug("[DungeonTrain] Recovery: couldn't list {}: {}", dtpacksRoot, e.toString());
        }
        return false;
    }

    /**
     * A short description of the Dungeon Train data an instance folder holds, or empty when it
     * holds none. Checks both the current and the pre-relocation layouts, since the instance that
     * still has the player's builds is by definition the one that never got updated.
     */
    static Optional<String> dataHeldBy(Path instanceRoot) {
        Path data = instanceRoot.resolve(PlayerDataPaths.ROOT_DIR);
        Path config = instanceRoot.resolve("config");
        boolean builds = FileTrees.containsAnyFile(data.resolve(PlayerDataPaths.USER))
            || FileTrees.containsAnyFile(config.resolve("dungeontrain").resolve("user"));
        boolean profile = FileTrees.containsAnyFile(data.resolve(PlayerDataPaths.ACHIEVEMENTS))
            || FileTrees.containsAnyFile(config.resolve("dungeontrain-achievements"));
        if (builds && profile) return Optional.of("builds and progress");
        if (builds) return Optional.of("builds");
        if (profile) return Optional.of("progress");
        return Optional.empty();
    }
}
