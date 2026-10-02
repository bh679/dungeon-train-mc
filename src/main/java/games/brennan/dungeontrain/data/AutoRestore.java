package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.core.DataRecovery;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Puts a player's lost Dungeon Train data back from a backup, without being asked.
 *
 * <p>There is no restore command and no "Restore" button: a player cannot run a backup or a restore
 * by hand, so that what is on disk is always something this system put there. When an install
 * looks like it lost its data — the same signature Dungeon Backup's recovery card used
 * ({@link DataRecovery#looksEmptied}) — the newest archive is restored on the way in, before any
 * world can load and before the world-load backup can capture the emptied state.</p>
 *
 * <p><b>Archives only.</b> The card also offered other instance folders on the machine. Those are a
 * guess about whose data it is, which is fine to offer and not fine to act on unasked, so they are
 * skipped here. The out-of-instance mirror covers the delete-and-reinstall case they were for.</p>
 *
 * <p><b>Never when the emptiness is deliberate.</b> The {@link #DISMISSED_MARKER} file — written by
 * the old card's "Don't ask" and by the Video Tools profile reset — means the player emptied this
 * install on purpose. Restoring over that would undo a reset.</p>
 *
 * <p>Uses only Dungeon Backup's 0.2.0 API. With 0.3.0 installed the same restore also merges
 * progress files that already exist — see {@link RestoreMergers}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AutoRestore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Dungeon Backup's "this install was emptied on purpose" marker, in the data root. */
    public static final String DISMISSED_MARKER = "recovery-dismissed.marker";

    /** How many archives to try before giving up — a damaged newest archive must not be the end. */
    static final int MAX_ARCHIVES = 5;

    /** What a pass did. {@code archive} is the first archive that put something back, or empty. */
    public record Outcome(int files, String archive) {
        public static final Outcome NOTHING = new Outcome(0, "");

        public boolean restored() { return files > 0; }
    }

    private AutoRestore() {}

    /**
     * Dedicated servers have no title screen, and this is also the last moment before the
     * world-load backup. Runs after Dungeon Backup's migration (that one is {@code HIGHEST}), so
     * data still waiting in {@code config/} has been moved and is not mistaken for a loss.
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        runIfEnabled();
    }

    /** Restore if this install lost its data. Never throws. Cheap when there is nothing to do. */
    public static Outcome runIfEnabled() {
        // A dev build's data root is routinely empty — a fresh worktree, a wiped run/ — and the
        // out-of-instance mirror is shared by every checkout on the machine. Same gate, and the
        // same -PbackupPrompt override, as the recovery card had.
        if (DungeonTrain.isDevBuild() && !Boolean.getBoolean(DungeonTrainBackup.PROMPT_PROPERTY)) {
            return Outcome.NOTHING;
        }
        try {
            Registration registration = DungeonTrainBackup.registration();
            return run(registration, FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get(),
                registration.externalBackupsRoot().orElse(null));
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] Automatic restore failed — nothing was changed", e);
            return Outcome.NOTHING;
        }
    }

    /** The decision and the restore, against explicit roots — what {@code AutoRestoreTest} drives. */
    static Outcome run(Registration registration, Path gameDir, Path configDir, Path externalBackupsRoot) {
        if (Files.exists(registration.dataRoot().resolve(DISMISSED_MARKER))) return Outcome.NOTHING;
        if (!DataRecovery.looksEmptied(registration, configDir)) return Outcome.NOTHING;

        List<DataRecovery.Candidate> archives = DataRecovery
            .findCandidates(registration, gameDir, externalBackupsRoot).stream()
            .filter(c -> c.kind() != DataRecovery.Kind.SIBLING_INSTANCE)
            .limit(MAX_ARCHIVES)
            .toList();

        int files = 0;
        String from = "";
        for (DataRecovery.Candidate archive : archives) {
            try {
                int written = DataRecovery.restore(registration, archive);
                if (written > 0 && from.isEmpty()) from = archive.fileName();
                files += written;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[DungeonTrain] Automatic restore: couldn't read {}: {}", archive.fileName(), e.toString());
            }
            if (!DataRecovery.looksEmptied(registration, configDir)) break;
        }
        if (files == 0) return Outcome.NOTHING;

        LOGGER.info("[DungeonTrain] Automatic restore: put back {} file(s), starting from {}", files, from);
        try {
            registration.onRestored().run();
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Automatic restore: post-restore reload failed: {}", e.toString());
        }
        return new Outcome(files, from);
    }

    /**
     * Record that this install's data was emptied on purpose, so the next launch does not put it
     * back. Called by the Video Tools profile reset.
     */
    public static void markEmptiedOnPurpose() {
        Path marker = PlayerDataPaths.root().resolve(DISMISSED_MARKER);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Dungeon Train: data cleared on purpose; automatic restore is off for this install.\n");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't record the deliberate reset — the next launch may restore "
                + "the cleared data from a backup: {}", e.toString());
        }
    }
}
