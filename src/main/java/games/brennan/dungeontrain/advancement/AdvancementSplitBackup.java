package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeonbackup.api.DungeonBackup;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Backs up a player's advancement history before the per-difficulty split ({@link AdvancementProfiles})
 * first touches it — once per install.
 *
 * <p>The split only adds files, but it is the first time a release rearranges what a player has earned,
 * so the history it starts from is kept twice:</p>
 * <ol>
 *   <li>a <b>Dungeon Backup restore point</b> of the whole data root, reason {@value #REASON} — the
 *       normal way back, mirrored outside the instance like any other;</li>
 *   <li>a <b>plain copy</b> of every pre-split {@code <uuid>.json} in {@code achievements/pre-split/}.
 *       Dungeon Backup keeps five restore points per mod version, and a world load, a death and a
 *       session end take one each, so the restore point is pruned within a few sessions. The copy is a
 *       few kilobytes, never pruned, and rides along in every later restore point.</li>
 * </ol>
 *
 * <p>Runs on {@link ServerStartingEvent} at normal priority: after Dungeon Backup's {@code HIGHEST}
 * relocation out of {@code config/} (so the files are at their final address) and before any player
 * logs in (so before {@link AdvancementProfiles#alignOnLogin}). A marker file records that it ran;
 * a failed copy leaves the marker unwritten so the next start tries again. Nothing here ever stops the
 * game loading.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AdvancementSplitBackup {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The restore point's reason, shown in its file name and by {@code /dtrestore}. */
    public static final String REASON = "before-advancement-split";
    /** Present once this install's pre-split history has been backed up (or there was none). */
    static final String MARKER = ".difficulty-split";
    /** Where the never-pruned copies live, inside the achievements folder. */
    static final String COPY_DIR = "pre-split";
    private static final String LEGACY_DIR_NAME = "dungeontrain-achievements";

    /** What a run did — for the log line and the tests. */
    enum Outcome { ALREADY_DONE, NOTHING_TO_BACK_UP, BACKED_UP, COPY_FAILED }

    /** The restore point, behind a seam so the tests don't need Dungeon Backup running. */
    @FunctionalInterface
    interface RestorePoint {
        /** Take it; return a short description of what was written, or why nothing was. */
        String take() throws IOException;
    }

    private AdvancementSplitBackup() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        Path dir = PlayerDataPaths.dir(PlayerDataPaths.ACHIEVEMENTS);
        Path legacy = PlayerDataPaths.configRoot().resolve(LEGACY_DIR_NAME);
        try {
            Outcome outcome = run(dir, legacy, AdvancementSplitBackup::takeRestorePoint);
            if (outcome == Outcome.NOTHING_TO_BACK_UP) {
                LOGGER.info("[DungeonTrain] Advancement split: no earlier advancements to back up");
            }
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] Advancement split backup failed — continuing without it", e);
        }
    }

    private static String takeRestorePoint() throws IOException {
        return DungeonBackup.backupNow(DungeonTrain.MOD_ID, REASON)
            .map(result -> result.archive().map(Path::toString).orElse("nothing new to archive"))
            .orElse("skipped — backups are turned off");
    }

    /**
     * The whole job against explicit folders. Back up and copy only when there is pre-split history
     * and no marker; mark a fresh install straight away.
     */
    static Outcome run(Path achievementsDir, Path legacyDir, RestorePoint restorePoint) {
        Path marker = achievementsDir.resolve(MARKER);
        if (Files.exists(marker)) return Outcome.ALREADY_DONE;

        List<Path> preSplit = new ArrayList<>(preSplitFiles(achievementsDir));
        for (Path file : preSplitFiles(legacyDir)) {
            if (!Files.exists(achievementsDir.resolve(file.getFileName()))) preSplit.add(file);
        }
        if (preSplit.isEmpty()) {
            writeMarker(marker);
            return Outcome.NOTHING_TO_BACK_UP;
        }

        try {
            LOGGER.info("[DungeonTrain] Advancement split: restore point {}", restorePoint.take());
        } catch (IOException | RuntimeException e) {
            // The copy below still protects the advancements themselves.
            LOGGER.warn("[DungeonTrain] Advancement split: restore point failed ({}), keeping a copy anyway",
                e.toString());
        }

        Path copyDir = achievementsDir.resolve(COPY_DIR);
        try {
            Files.createDirectories(copyDir);
            for (Path file : preSplit) {
                Path copy = copyDir.resolve(file.getFileName());
                if (!Files.exists(copy)) Files.copy(file, copy);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Advancement split: couldn't copy {} file(s) to {} — will retry next start",
                preSplit.size(), copyDir, e);
            return Outcome.COPY_FAILED;
        }
        writeMarker(marker);
        LOGGER.info("[DungeonTrain] Advancement split: backed up {} player profile(s) — copies kept in {}",
            preSplit.size(), copyDir);
        return Outcome.BACKED_UP;
    }

    /** Every {@code <uuid>.json} directly in {@code dir} — the pre-split (now Normal) profiles. */
    static List<Path> preSplitFiles(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(AdvancementSplitBackup::isPreSplitName).toList();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Advancement split: couldn't list {}: {}", dir, e.toString());
            return List.of();
        }
    }

    private static boolean isPreSplitName(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json")) return false;
        try {
            UUID.fromString(name.substring(0, name.length() - ".json".length()));
            return true;
        } catch (IllegalArgumentException e) {
            return false; // <uuid>.hard.json, <uuid>.shared.json, anything else
        }
    }

    private static void writeMarker(Path marker) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Pre-split advancements backed up by Dungeon Train " + VersionInfo.VERSION + "\n");
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Advancement split: couldn't write {}: {}", marker, e.toString());
        }
    }
}
