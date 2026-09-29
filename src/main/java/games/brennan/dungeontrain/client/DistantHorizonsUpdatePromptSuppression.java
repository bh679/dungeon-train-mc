package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Keeps Distant Horizons' own "update available" screen off the launch path for players running
 * the DH build the modpack pins.
 *
 * <p>DH's {@code MixinMinecraft} opens {@code UpdateModScreen} at startup whenever its self-updater
 * finds a newer release on Modrinth. The pack pins DH to one build on purpose — it is the build
 * whose API the band and portal-room suppression is compiled against — so for a pack player the
 * prompt is an invitation to break the pack. Any other DH version (a player who upgraded DH
 * themselves) is left alone and gets DH's prompt as normal.</p>
 *
 * <p>Matched by class name, so this file names no DH type and loads with or without DH present.
 * The swap happens in {@link ScreenEvent.Opening}. DH opens its prompt by redirecting the
 * {@code Runnable.run()} in {@code Minecraft#onGameLoadFinished}, so vanilla's own initial-screen
 * task — the title screen, or a {@code --quickPlay*} join, behind NeoForge's loading-warnings
 * screen — never runs and nothing is on screen yet. {@code MinecraftInitialScreensMixin} stashes
 * that task in {@link InitialScreensCapture} first, so suppressing the prompt means running
 * exactly what vanilla would have run. Opening a title screen instead used to cancel quick-play
 * joins (the perf harness's {@code -PquickJoin}). If some screen is already showing when the
 * prompt tries to open, it is simply kept. Nothing of DH's is written or changed — its updater
 * config and state are untouched, and the prompt returns the moment the player is on a DH build
 * the pack does not pin.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DistantHorizonsUpdatePromptSuppression {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DH_MOD_ID = "distanthorizons";
    private static final String UPDATE_SCREEN_SUFFIX = "distanthorizons.common.wrappers.gui.updater.UpdateModScreen";

    private static boolean logged;

    private DistantHorizonsUpdatePromptSuppression() {}

    /** What takes the suppressed prompt's place. */
    public enum Decision {
        /** A screen is already up (title, connect, anything) — cancel the prompt and keep it. */
        KEEP_CURRENT_SCREEN,
        /** Nothing is up but vanilla's initial-screen task was captured — cancel the prompt and run it. */
        RUN_INITIAL_SCREENS,
        /** Nothing is up and nothing was captured — open a fresh title screen so the player is not stranded. */
        OPEN_TITLE_SCREEN
    }

    /**
     * Pure decision, kept free of Minecraft types so it is unit-testable.
     *
     * @param hasCurrentScreen         whether any screen is showing when the prompt tries to open
     * @param hasPendingInitialScreens whether {@link InitialScreensCapture} holds vanilla's task
     */
    public static Decision decide(boolean hasCurrentScreen, boolean hasPendingInitialScreens) {
        if (hasCurrentScreen) return Decision.KEEP_CURRENT_SCREEN;
        return hasPendingInitialScreens ? Decision.RUN_INITIAL_SCREENS : Decision.OPEN_TITLE_SCREEN;
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next == null || !next.getClass().getName().endsWith(UPDATE_SCREEN_SUFFIX)) return;

        String pinned = VersionInfo.MODPACK_DISTANT_HORIZONS_VERSION;
        String loaded = loadedDhVersion();
        if (pinned.isEmpty() || loaded == null || !loaded.startsWith(pinned)) {
            if (!logged) {
                LOGGER.info("[DungeonTrain] Distant Horizons update prompt left alone: loaded {} vs modpack pin {}",
                        loaded, pinned.isEmpty() ? "(none)" : pinned);
                logged = true;
            }
            return;
        }

        Decision decision = decide(event.getCurrentScreen() != null, InitialScreensCapture.hasPending());
        if (!logged) {
            LOGGER.info("[DungeonTrain] Distant Horizons update prompt suppressed: loaded {} matches the modpack pin ({})",
                    loaded, decision);
            logged = true;
        }
        switch (decision) {
            case KEEP_CURRENT_SCREEN -> event.setCanceled(true);
            case RUN_INITIAL_SCREENS -> {
                // Cancel first, then run vanilla's task. It calls Minecraft#setScreen itself; the
                // outer setScreen that posted this event has changed no state yet and returns as
                // soon as it sees the cancel, so the screen the task opens is the one that stays.
                event.setCanceled(true);
                Runnable initialScreens = InitialScreensCapture.consume();
                if (initialScreens != null) initialScreens.run();
            }
            case OPEN_TITLE_SCREEN -> event.setNewScreen(new TitleScreen(false));
        }
    }

    private static String loadedDhVersion() {
        return ModList.get().getModContainerById(DH_MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(null);
    }
}
