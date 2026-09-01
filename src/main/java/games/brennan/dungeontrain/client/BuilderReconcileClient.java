package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Holds a "the relay lost some of your builds" offer until there is somewhere to show it.
 *
 * <p>The offer arrives as a packet during world join, which is the one moment on the client where
 * screens are contended: the join chain hands from one screen to the next, and anything that calls
 * {@code setScreen} in the middle of it either gets replaced a tick later or steals a screen from
 * something that was mid-handoff. So the offer is <b>held</b>, and opened only once the player is
 * actually in the world with nothing else in front of them — the same {@code mc.screen == null} guard
 * the mod's input gating uses.</p>
 *
 * <p>The wait is patient rather than timed out: a player who joins straight into a menu sees the card
 * when they close it. It is dropped on disconnect, because the next session will be offered it
 * again — the server re-checks on every join, and a build the relay is still missing is still
 * missing.</p>
 *
 * <p>"Never" is recorded as a marker file beside the player's data, like the recovery prompt's. It
 * suppresses the card, not the server's check: a dedicated server has no way to know one client has
 * stopped wanting to be asked, and the answer belongs to the install rather than to the server.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderReconcileClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DISMISSED_MARKER = "reconcile-dismissed.marker";

    /**
     * Ticks to wait once the coast is clear. Long enough that the card doesn't appear over the last
     * frame of the join fade, short enough that it is plainly a response to having just joined.
     */
    private static final int OPEN_DELAY_TICKS = 40;

    private static int pendingOnDisk = 0;
    private static int pendingBackupOnly = 0;
    private static int delayRemaining = -1;

    private BuilderReconcileClient() {}

    /** The server has found missing builds. Hold the offer until {@link #onClientTick} can show it. */
    public static void offer(int onDisk, int backupOnly) {
        if (onDisk <= 0 && backupOnly <= 0) return;
        if (dismissed()) {
            LOGGER.debug("[DungeonTrain] Build reconcile: offer suppressed — dismissed on this install.");
            return;
        }
        pendingOnDisk = onDisk;
        pendingBackupOnly = backupOnly;
        delayRemaining = OPEN_DELAY_TICKS;
        LOGGER.info("[DungeonTrain] Build reconcile: {} build(s) on disk and {} in backups are missing "
                + "from the relay.", onDisk, backupOnly);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (delayRemaining < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            // Left the world before the card could be shown. The next join asks again.
            clear();
            return;
        }
        // Anything else on screen — the join chain, a menu the player opened — resets the wait rather
        // than being interrupted by this.
        if (mc.screen != null) {
            delayRemaining = OPEN_DELAY_TICKS;
            return;
        }
        if (delayRemaining-- > 0) return;

        int onDisk = pendingOnDisk;
        int backupOnly = pendingBackupOnly;
        clear();
        mc.setScreen(new BuilderReconcileScreen(onDisk, backupOnly));
    }

    /** The player answered. {@code permanent} stops the card returning on this install. */
    static void onAnswered(boolean permanent) {
        clear();
        if (!permanent) return;
        try {
            Path marker = marker();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Dungeon Train: build reconcile offer dismissed by the player.\n");
            LOGGER.info("[DungeonTrain] Build reconcile: dismissed permanently.");
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Build reconcile: couldn't record the dismissal: {}", e.toString());
        }
    }

    private static boolean dismissed() {
        try {
            return Files.exists(marker());
        } catch (Exception e) {
            return false;
        }
    }

    private static Path marker() {
        return PlayerDataPaths.root().resolve(DISMISSED_MARKER);
    }

    private static void clear() {
        pendingOnDisk = 0;
        pendingBackupOnly = 0;
        delayRemaining = -1;
    }
}
