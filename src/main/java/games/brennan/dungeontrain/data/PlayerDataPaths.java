package games.brennan.dungeontrain.data;

import games.brennan.dungeonbackup.api.Kind;
import games.brennan.dungeonbackup.api.Located;
import games.brennan.dungeonbackup.api.Relocation;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for where Dungeon Train keeps the player's own data.
 *
 * <p><b>Why this class exists.</b> Everything a player accumulates — their Train Editor builds,
 * their cross-world advancements, their stats, their loadouts — used to live under
 * {@code config/}. Modpack launchers replace {@code config/} when a pack updates: ATLauncher
 * wipes it between pack versions, and the Modrinth app has repeatedly deleted instance files on
 * update. The Dungeon Train modpack ships {@code config/khi.toml} and
 * {@code config/smoothswapping.json} in its overrides, so <em>every</em> DT pack update touches
 * that folder. A player who pressed "Update Pack" could lose every build and every advancement in
 * one stroke, which is exactly what happened.</p>
 *
 * <p>So player data now lives at the <b>instance root</b>, under {@code <gameDir>/dungeontrain/} —
 * the same folder {@code players_seen/} and {@code ride-snapshots/} already used, and a sibling of
 * {@code saves/} and {@code dtpacks/}. Launchers do not replace the instance root; they cannot,
 * because the player's worlds are in it.</p>
 *
 * <p><b>What deliberately stays in {@code config/}:</b> the engine TOMLs
 * ({@code dungeontrain-server.toml}, {@code dungeontrain-common.toml}),
 * {@code adventureitemstats.properties}, {@code dungeontrain/cheat-mods.json} and the companion
 * resource-pack marker. Those are configs
 * {@link games.brennan.dungeontrain.cheat.DtConfigIntegrity} and
 * {@link games.brennan.dungeontrain.cheat.AisDataIntegrity} hold to their shipped defaults —
 * losing them on a pack update is <em>correct</em> (they regenerate at defaults), and moving them
 * would break the integrity checks that read {@code FMLPaths.CONFIGDIR} directly.</p>
 *
 * <p>The machinery that acts on these paths — the one-shot migration out of {@code config/}, the
 * rolling restore points, the out-of-instance mirror and the lost-data recovery card — lives in
 * the <b>Dungeon Backup</b> sibling mod ({@code games.brennan.dungeonbackup}); DT describes its
 * data to it once, in {@link DungeonTrainBackup}. This class keeps only DT's own folder names, the
 * legacy-location table that registration passes on, and the read-across-the-move helpers the
 * stores use. Deliberately thin: the only Minecraft/Forge contact is {@link FMLPaths}.</p>
 */
public final class PlayerDataPaths {

    /** The player-data root's folder name, directly under the instance root. */
    public static final String ROOT_DIR = "dungeontrain";

    // Subdirectory names, relative to root(). Public so the stores that own each one can
    // reference the same constant rather than re-spelling the folder.
    public static final String USER = "user";
    public static final String ACHIEVEMENTS = "achievements";
    public static final String STATS = "stats";
    public static final String NARRATIVE = "narrative";
    public static final String LOADOUTS = "loadouts";
    public static final String TRANSLATIONS = "translations";
    public static final String OUTBOX = "outbox";
    public static final String BACKUPS = "backups";
    /** Per-player Ender Chest flags (Free Play expansion). New store — never lived in {@code config/}. */
    public static final String ENDER_CHEST = "enderchest";

    /** Folder name used outside the instance, under the OS's per-user application data dir. */
    public static final String EXTERNAL_DIR = "DungeonTrain";

    /** The dtpacks active-package/disabled-set state file, at the root. */
    public static final String DTPACKS_STATE = "dtpacks-state.json";

    /**
     * Every piece of player data that moved, in migration order.
     *
     * <p>This list is the contract {@code PlayerDataPathsTest} asserts against each owning store's
     * own constant, so a store cannot be added or renamed and silently left behind in
     * {@code config/} where the next pack update would delete it. It is handed to Dungeon Backup
     * by {@link DungeonTrainBackup} — the library's migration drains it, and nothing else in the
     * backup system enumerates folders: archives take {@link #root()} wholesale.</p>
     */
    public static final List<Relocation> RELOCATIONS = List.of(
        // Train Editor content — the builds themselves. Note {@code config/dungeontrain/imported/}
        // is deliberately absent: DtpacksMigration already drains it into <gameDir>/dtpacks/, which
        // is outside config/ and so already safe. Moving it here first would empty the folder before
        // that one-shot ran and strand those packs in the legacy tier.
        new Relocation("dungeontrain/" + USER, USER, Kind.DIRECTORY),
        new Relocation("dungeontrain/" + DTPACKS_STATE, DTPACKS_STATE, Kind.FILE),
        // Cross-world profile — advancements, stats, narrative progress.
        new Relocation("dungeontrain-achievements", ACHIEVEMENTS, Kind.DIRECTORY),
        new Relocation("dungeontrain-stats", STATS, Kind.DIRECTORY),
        new Relocation("dungeontrain-narrative", NARRATIVE, Kind.DIRECTORY),
        // Per-player odds and ends.
        new Relocation("dungeontrain/" + LOADOUTS, LOADOUTS, Kind.DIRECTORY),
        new Relocation("dungeontrain/" + TRANSLATIONS, TRANSLATIONS, Kind.DIRECTORY),
        // Queued uploads. Small, but losing them silently drops work the player already did.
        new Relocation("dungeontrain-relay-outbox.json", OUTBOX + "/relay-outbox.json", Kind.FILE),
        new Relocation("dungeontrain-chat-outbox.json", OUTBOX + "/chat-outbox.json", Kind.FILE),
        new Relocation("dungeontrain-translation-outbox.json",
            OUTBOX + "/translation-outbox.json", Kind.FILE)
    );

    private PlayerDataPaths() {}

    // ---- Live roots ----

    /** {@code <gameDir>/dungeontrain/} — the player-data root. */
    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve(ROOT_DIR);
    }

    /** A subdirectory of {@link #root()}, e.g. {@code <gameDir>/dungeontrain/achievements/}. */
    public static Path dir(String subDir) {
        return root().resolve(subDir);
    }

    /** {@code <gameDir>/dungeontrain/backups/} — where Dungeon Backup writes DT's archives. */
    public static Path backupsRoot() {
        return dir(BACKUPS);
    }

    /** {@code <gameDir>/dtpacks/} — already outside {@code config/}, included in backups. */
    public static Path dtpacksRoot() {
        return FMLPaths.GAMEDIR.get().resolve("dtpacks");
    }

    /**
     * {@code <os app data>/DungeonTrain/backups} — restore points kept <b>outside</b> the Minecraft
     * instance, so they survive the instance itself being deleted, reset or reinstalled. Resolved
     * by Dungeon Backup from {@link #EXTERNAL_DIR}; empty when no home directory can be resolved,
     * which is a normal outcome on a locked-down or headless host and must never be treated as an
     * error — the in-instance backup still ran.
     */
    public static Optional<Path> externalBackupsRoot() {
        return DungeonTrainBackup.registration().externalBackupsRoot();
    }

    /** The config directory, still home to the integrity-governed engine configs. */
    public static Path configRoot() {
        return FMLPaths.CONFIGDIR.get();
    }

    // ---- Reading across the move ----

    /**
     * The path a store should <b>read</b> from: the new one when it exists, otherwise the
     * pre-move {@code config/} copy. See {@link Located#readPath}.
     */
    public static Path readPath(Path preferred, Path legacy) {
        return Located.readPath(preferred, legacy);
    }

    /**
     * Locate a per-player file: {@code <root>/<subDir>/<name>} now, {@code <config>/<legacy>/<name>}
     * before the move.
     *
     * @param legacyRelative the pre-move location, relative to the config directory, {@code /}-separated
     */
    public static Located locate(String subDir, String legacyRelative, String name) {
        return new Located(dir(subDir).resolve(name),
            Relocation.resolve(configRoot(), legacyRelative).resolve(name));
    }

    /** Locate a file sitting directly in {@link #root()}, e.g. the dtpacks state file. */
    public static Located locateAtRoot(String name, String legacyRelativeDir) {
        return new Located(root().resolve(name),
            Relocation.resolve(configRoot(), legacyRelativeDir).resolve(name));
    }

    /** Locate a whole directory at both addresses. */
    public static Located locateDir(String subDir, String legacyRelative) {
        return new Located(dir(subDir), Relocation.resolve(configRoot(), legacyRelative));
    }
}
