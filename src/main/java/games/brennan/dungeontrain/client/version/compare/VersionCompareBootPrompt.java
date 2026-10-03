package games.brennan.dungeontrain.client.version.compare;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Opens the Versions page by itself, once per session, when the build is behind the newest real
 * release on any launcher ({@link NewestRelease}) — not just the player's own, which for CurseForge
 * players can lag for as long as a build sits in review. Same idea as {@code ConfigDeviationPromptHandler}, but the
 * thing it waits on is the listing, not a tick count: the page only knows it is behind once the
 * launcher's pack listing has arrived, and that lands on the render thread through
 * {@link VersionCompareState}, which pokes this class every time a fetch completes.
 *
 * <p>Arms on the first title screen of the session and stays armed until either the listing says
 * "behind" while the title screen is still up (open the page) or says "not behind" (never open).
 * A player who has already left the title screen when the listing lands is not interrupted —
 * the next title screen re-arms it. Sibling-mod lag alone does not open the page; the companion
 * row still shows it once the page is opened by hand.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VersionCompareBootPrompt {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean armed;
    private static boolean decided;

    private VersionCompareBootPrompt() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (decided || !(event.getScreen() instanceof TitleScreen)) return;
        armed = true;
        // The listing may already be in from an earlier title screen this session.
        onDataChanged();
    }

    /** Render thread. Called whenever a platform or sibling listing lands or fails. */
    static void onDataChanged() {
        if (!armed || decided) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !(mc.screen instanceof TitleScreen title)) return;

        Optional<FullSemver> installed = InstalledVersion.get();
        if (installed.isEmpty()) {
            decided = true;
            armed = false;
            return;
        }
        Platform launcher = Platform.current();
        Optional<NewestRelease.Target> target = NewestRelease.across(installed.get(), launcher,
                VersionCompareState.versions(launcher), VersionCompareState.versions(launcher.other()));
        if (target.isEmpty()) {
            // Not behind on what has loaded so far. Only settle "never open" once neither launcher is
            // still loading — a lagging launcher's listing must not hide the other's newer release.
            if (!anyLoading()) {
                decided = true;
                armed = false;
            }
            return;
        }
        decided = true;
        armed = false;
        LOGGER.info("Versions page: installed {} is behind {} {} — opening at the title screen",
                installed.get(), target.get().platform(), target.get().version());
        mc.setScreen(new VersionCompareScreen(title));
    }

    private static boolean anyLoading() {
        for (Platform p : Platform.values()) {
            if (VersionCompareState.status(p) == VersionCompareState.Status.LOADING) return true;
        }
        return false;
    }
}
