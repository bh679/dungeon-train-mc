package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

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

    /**
     * Whether this world load has already been backed up. Reset on server stop, so each world
     * visit gets one backup on the way in and one on the way out rather than one per game session.
     */
    private static volatile boolean backedUpThisLoad = false;

    private PlayerDataBackupHook() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(ServerStartedEvent event) {
        runOnce("world-load");
    }

    /**
     * Back up again on the way out, and re-arm for the next world.
     *
     * <p>The load-time backup snapshots what was on disk <em>before</em> the session. Without this
     * one, a carriage built during a session would sit in no archive at all until the next world
     * load — so losing it in between would lose it for good, which is the exact failure this whole
     * area exists to prevent. The digest check means a session that authored nothing costs a
     * directory walk and writes no second archive.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        runOnce("session-end");
        backedUpThisLoad = false;
    }

    /**
     * Take a restore point, unless nothing has changed since the last one. Never throws — failing
     * to back up is not a reason to stop the game.
     */
    public static synchronized void runOnce(String reason) {
        if (backedUpThisLoad && "world-load".equals(reason)) return;
        if ("world-load".equals(reason)) backedUpThisLoad = true;
        try {
            PlayerDataBackup.create(PlayerDataPaths.backupsRoot(), sources(), reason,
                VersionInfo.VERSION);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Backup: couldn't write a restore point ({}): {}",
                reason, e.toString());
        }
    }

    /**
     * Everything worth archiving: the <b>whole</b> data root, plus {@code dtpacks/} — a player's
     * saved packages are their builds too, and they live outside the data root.
     *
     * <p>Taking the root wholesale rather than listing each relocated folder is deliberate. An
     * enumeration missed the loose files that sit directly under the root — {@code
     * dtpacks-state.json}, which records the active package, and the queued uploads in {@code
     * outbox/} — so they were in no archive at all. It also means a folder added later is backed up
     * without anyone remembering to add it here.</p>
     *
     * <p>{@code backups/} excludes itself, or every archive would contain the previous ones.</p>
     */
    public static List<PlayerDataBackup.Source> sources() {
        return List.of(
            new PlayerDataBackup.Source(PlayerDataPaths.ROOT_DIR, PlayerDataPaths.root(),
                Set.of(PlayerDataPaths.BACKUPS)),
            new PlayerDataBackup.Source("dtpacks", PlayerDataPaths.dtpacksRoot()));
    }
}
