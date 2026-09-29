package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.GlobalBookBurnStats;
import games.brennan.dungeontrain.advancement.GlobalNarrativeProgress;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.event.AchievementEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Brings a running server up to date with a restore that just merged the cross-world progress
 * files on disk ({@link RestoreMergers}).
 *
 * <p>Only needed for an in-game {@code /dtrestore}. The title-screen card restores with no server
 * running, and the next login's replay picks the files up by itself. Mid-session, though, three
 * things hold pre-restore state and would throw the restore away:</p>
 * <ul>
 *   <li>the stats caches — flushed whole on logout / server stop, over the merged file;</li>
 *   <li>the narrative cache — re-saved whole on the next letter read;</li>
 *   <li>each online player's world advancements — the logout reconcile deletes every sidecar
 *       entry not done in the current world, which is every restored one.</li>
 * </ul>
 */
public final class RestoredProfileSync {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RestoredProfileSync() {}

    /** Called from the backup registration's {@code onRestored} hook. Never throws. */
    public static void apply() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            try {
                GlobalPlayerStats.absorbDisk();
                GlobalBookBurnStats.absorbDisk();
                GlobalNarrativeProgress.invalidate();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    AchievementEvents.resyncFromGlobalStore(player);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[DungeonTrain] Post-restore profile sync failed — restored progress is on "
                    + "disk and applies at next login", e);
            }
        });
    }
}
