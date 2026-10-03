package games.brennan.dungeontrain.client.version;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.InstalledVersion;
import games.brennan.dungeontrain.client.version.compare.NewestRelease;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.VersionCompareState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Opens the {@link UpdatePromptScreen} card once per session when the build is behind the newest real
 * release on <em>any</em> launcher ({@link NewestRelease}) — not just the player's own, which for
 * CurseForge players can lag for as long as a build sits in review.
 *
 * <p>The answer needs the launchers' pack listings, which {@code TitleScreenLayoutHandler} starts
 * fetching at the first title screen. Until both have landed or failed, an "up to date" reading is
 * provisional — a slow listing must not hide the other's newer release — so this keeps checking.</p>
 *
 * <p>Tick-driven like {@code LowMemoryNotice}: the first title screen exists while the resource-loading
 * splash is still up, and a screen opened then gets displaced. So the card only opens over a title
 * screen with no overlay, after a short delay, with that same title screen still showing. If another
 * title-screen card is up first, this one waits its turn.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class UpdatePromptHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks to wait after arming before checking — matches the sibling title-screen prompts. */
    private static final int OPEN_DELAY_TICKS = 24;

    private static int openDelayRemaining = -1;
    private static TitleScreen pendingParent;
    /** Once per game session: set when the card has opened or is known not to be needed. */
    private static boolean decided;

    private UpdatePromptHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (decided) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen titleScreen) || mc.getOverlay() != null) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        if (openDelayRemaining < 0) {
            openDelayRemaining = OPEN_DELAY_TICKS;
            pendingParent = titleScreen;
            return;
        }
        if (--openDelayRemaining > 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent) return;

        Optional<FullSemver> installed = InstalledVersion.get();
        if (installed.isEmpty()) {
            decided = true;
            return;
        }
        Platform launcher = Platform.current();
        Optional<NewestRelease.Target> target = NewestRelease.across(installed.get(), launcher,
                VersionCompareState.versions(launcher), VersionCompareState.versions(launcher.other()));
        if (target.isEmpty()) {
            // Up to date on what has loaded so far; settle only once neither listing is still loading.
            if (!anyLoading()) decided = true;
            return;
        }
        decided = true;
        LOGGER.info("Update prompt: installed {} is behind {} (newest on {}) — showing the card",
                installed.get(), target.get().version(), target.get().platform());
        mc.setScreen(new UpdatePromptScreen(parent, target.get().version(), installed.get()));
    }

    private static boolean anyLoading() {
        for (Platform p : Platform.values()) {
            if (VersionCompareState.status(p) == VersionCompareState.Status.LOADING) return true;
        }
        return false;
    }
}
