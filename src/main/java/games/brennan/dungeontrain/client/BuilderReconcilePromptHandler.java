package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderReconcileRunner;
import games.brennan.dungeontrain.client.builder.BuilderReconcileScan;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offers to put back builds the relay has lost, at the title screen.
 *
 * <p><b>Usually there is no prompt at all.</b> Putting a build back is the same upload its next save
 * would have made — the player's own build, from the player's own copy, into their own private
 * profile — so by default it simply happens and a toast says how many. The card below is what
 * "Confirm build restores" in Options > Dungeon Train turns on, for anyone who would rather decide,
 * and it is also the only way to keep the backup-only tier out of a restore.</p>
 *
 * <p>Structure is {@link DataRecoveryPromptHandler}'s, and for the same reasons: arm on
 * {@code ScreenEvent.Init.Post} for the title screen, open after a short tick delay, and bail if the
 * player has navigated away, so it never steals a click from someone already heading into a world.
 * That navigate-away guard is what keeps the title-screen prompts out of each other's way.</p>
 *
 * <p>At the title screen rather than at world join because none of it needs a world: the relay
 * answers a plain HTTP call from the menu, and a build is a file on this install. Asking here means
 * a player who has ten worlds is asked once, about their builds, rather than once per world about
 * whichever subset that world happened to upload.</p>
 *
 * <p>The scan runs <b>once per session</b> and off-thread — it is a relay round trip plus a walk of
 * the store directories, and neither belongs on the render thread. Nothing is shown until it comes
 * back, so a slow relay delays the card rather than the menu.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderReconcilePromptHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DISMISSED_MARKER = "reconcile-dismissed.marker";

    /**
     * Ticks to wait after the title screen initialises. Longer than the recovery card's 18: losing
     * your whole install outranks losing some uploads, so this one queues behind it.
     */
    private static final int OPEN_DELAY_TICKS = 30;

    private static int openDelayRemaining = -1;
    private static TitleScreen pendingParent = null;
    private static boolean openedThisSession = false;
    private static boolean scanStarted = false;

    /** Session cache of the scan: null until it comes back. */
    private static volatile BuilderReconcileScan.Result result = null;

    private BuilderReconcilePromptHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (openedThisSession || openDelayRemaining > 0) return;
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) return;
        startScan();
        BuilderReconcileScan.Result found = result;
        if (found == null || found.isEmpty()) return;   // not back yet, or nothing to say
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (openDelayRemaining <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen)) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        openDelayRemaining--;
        if (openDelayRemaining != 0) return;

        TitleScreen parent = pendingParent;
        BuilderReconcileScan.Result found = result;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent || found == null || found.isEmpty()) return;
        openedThisSession = true;
        LOGGER.info("[DungeonTrain] Build reconcile: offering {} on-disk and {} backup-only build(s).",
                found.onDisk().size(), found.inBackups().size());
        mc.setScreen(new BuilderReconcileScreen(parent, found));
    }

    /**
     * Kick the scan off the first time a title screen appears.
     *
     * <p>Here rather than at client start: the relay call needs the player's profile, and the first
     * title screen is the earliest point everything it depends on is up. A result that lands after
     * this visit to the menu is shown on the next one — which, on a first launch straight into a
     * world, is when the player comes back out.</p>
     */
    private static void startScan() {
        if (scanStarted) return;
        scanStarted = true;
        if (dismissed() || !BuilderReconcileRunner.canRun()) return;
        BuilderReconcileRunner.scan().thenAccept(found -> {
            if (found == null || found.isEmpty()) return;
            LOGGER.info("[DungeonTrain] Build reconcile: {} of this install's builds are missing "
                    + "from the relay ({} on disk, {} in backups only).", found.total(),
                    found.onDisk().size(), found.inBackups().size());
            if (ClientDisplayConfig.isConfirmBuildRestore()) {
                result = found;   // the card picks it up on this title screen or the next
                return;
            }
            // Nothing to ask. Putting a build back is the same upload its next save would have made,
            // from the player's own copy into their own private profile — so it just happens, and the
            // card exists for anyone who would rather be asked (Options > Dungeon Train).
            BuilderReconcileRunner.restore(found, true).thenAccept(outcome -> {
                if (outcome.restored() > 0) toast(outcome.restored());
            });
        });
    }

    /**
     * Say what was put back, without asking anything.
     *
     * <p>A toast rather than a screen: the restore needed no decision, but a player whose builds
     * quietly reappeared in their profile should still be able to see why. There is no chat at the
     * title screen, which is where this runs.</p>
     */
    private static void toast(int restored) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("gui.dungeontrain.builder.reconcile.toast.title"),
                Component.translatable("gui.dungeontrain.builder.reconcile.toast.body", restored)));
    }

    /** The player answered. {@code permanent} stops the card returning on this install. */
    static void onAnswered(boolean permanent) {
        result = null;
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

    /** Beside the player's data, like the recovery card's — it outlives a wiped config folder. */
    private static Path marker() {
        return PlayerDataPaths.root().resolve(DISMISSED_MARKER);
    }
}
