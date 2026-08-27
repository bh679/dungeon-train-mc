package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires {@link PlayerDataBackup} to the game: one restore point per launch, taken once the server
 * is up and {@link PlayerDataMigration} has finished moving everything into place.
 *
 * <p>Separate from {@link PlayerDataBackup} so that class stays free of Forge types and testable
 * against {@code @TempDir}. {@code ServerStarted} rather than {@code ServerStarting} because the
 * migration runs on the latter at {@code HIGHEST} — backing up first would archive a half-moved
 * tree, and the pre-migration snapshot the migration takes for itself already covers that moment.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerDataBackupHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One backup per game session; a second world load shouldn't cost another walk. */
    private static volatile boolean backedUpThisSession = false;

    private PlayerDataBackupHook() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(ServerStartedEvent event) {
        runOnce();
    }

    /**
     * Take this launch's backup, unless nothing has changed since the last one. Never throws —
     * failing to back up is not a reason to stop the game.
     */
    public static synchronized void runOnce() {
        if (backedUpThisSession) return;
        backedUpThisSession = true;
        try {
            PlayerDataBackup.create(PlayerDataPaths.backupsRoot(), sources(), "launch",
                VersionInfo.VERSION);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Backup: couldn't write this launch's restore point: {}",
                e.toString());
        }
    }

    /**
     * Everything worth archiving: each relocated directory under the data root, plus
     * {@code dtpacks/} — a player's saved packages are their builds too, and they live outside the
     * data root.
     *
     * <p>Labels are the relocation's own names, which is what makes
     * {@link PlayerDataRecovery#backupTargets} able to put each tree back where it came from.
     * {@code backups/} is not a source, so archives never nest inside each other.</p>
     */
    public static List<PlayerDataBackup.Source> sources() {
        List<PlayerDataBackup.Source> sources = new ArrayList<>();
        for (PlayerDataPaths.Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            if (relocation.kind() != PlayerDataPaths.Kind.DIRECTORY) continue;
            sources.add(new PlayerDataBackup.Source(
                relocation.newRelative(), relocation.newPath(PlayerDataPaths.root())));
        }
        sources.add(new PlayerDataBackup.Source("dtpacks", PlayerDataPaths.dtpacksRoot()));
        return List.copyOf(sources);
    }
}
