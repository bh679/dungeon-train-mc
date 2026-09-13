package games.brennan.dungeontrain.client.crash;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.CrashRunState;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.data.SaveFreePlayProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Surfaces {@link CrashRecoveryScreen} at the title screen when the last session crashed in the
 * middle of a run and that world is still on disk.
 *
 * <p>Structure is the one {@code DataRecoveryPromptHandler} and {@code ConfigDeviationPromptHandler}
 * use: arm on {@code ScreenEvent.Init.Post} for the title screen, open after a short tick delay,
 * bail if the player navigated away meanwhile so it never steals a click from someone already
 * heading into a world. Those prompts all disarm the moment another screen takes over, so whichever
 * opens first wins the visit and the rest wait for the player to close it.</p>
 *
 * <p>The delay is <b>longer</b> than data recovery's 18 ticks on purpose: "your builds and progress
 * are missing" still outranks "your last run crashed", so if both apply the data prompt goes first
 * and this one waits for the next visit to the title screen.</p>
 *
 * <p>Offered <b>once per launch</b>. Esc or "Not now" leaves the record in place so the offer
 * returns next time; only "Discard" or a salvage session's clean end clears it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CrashRecoveryPromptHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** See the class note — deliberately above {@code DataRecoveryPromptHandler.OPEN_DELAY_TICKS}. */
    private static final int OPEN_DELAY_TICKS = 20;

    private static int openDelayRemaining = -1;
    private static TitleScreen pendingParent = null;
    private static boolean openedThisSession = false;

    private CrashRecoveryPromptHandler() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (openDelayRemaining <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen)) {
            LOGGER.debug("[DungeonTrain] Crash recovery: title screen replaced by {} during the open delay; standing down.",
                    mc.screen == null ? "null" : mc.screen.getClass().getName());
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        openDelayRemaining--;
        if (openDelayRemaining != 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent) {
            LOGGER.debug("[DungeonTrain] Crash recovery: title screen instance changed during the open delay; standing down.");
            return;
        }
        Optional<CrashRunState.RunState> crashed = crashedRun(mc);
        if (crashed.isEmpty()) return;
        openedThisSession = true;
        LOGGER.info("[DungeonTrain] Crash recovery: last session ended mid-run in '{}'; offering salvage.",
                crashed.get().levelId());
        mc.setScreen(new CrashRecoveryScreen(parent, crashed.get()));
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (openedThisSession || openDelayRemaining > 0) return;
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) return;
        Optional<CrashRunState.RunState> crashed = crashedRun(Minecraft.getInstance());
        LOGGER.debug("[DungeonTrain] Crash recovery: title screen init, record={}", crashed.map(CrashRunState.RunState::levelId).orElse("none"));
        if (crashed.isEmpty()) return;
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
    }

    /**
     * The run the last session crashed in, if there is one to go back to. A record whose save has
     * since been deleted is dropped here — offering a world that can't open would be worse than
     * nothing.
     */
    private static Optional<CrashRunState.RunState> crashedRun(Minecraft mc) {
        try {
            Optional<CrashRunState.RunState> state = CrashRunState.read(PlayerDataPaths.root());
            if (state.isEmpty()) return Optional.empty();
            if (!mc.getLevelSource().levelExists(state.get().levelId())) {
                LOGGER.info("[DungeonTrain] Crash recovery: save '{}' is gone; forgetting the run.",
                        state.get().levelId());
                CrashRunState.clear(PlayerDataPaths.root());
                return Optional.empty();
            }
            // A Free Play run has nothing to salvage that a fresh Free Play world wouldn't hand
            // straight back, and its Ender Chest is the Free Play one anyway. Skip the card.
            if (SaveFreePlayProbe.wasFreePlay(mc.getLevelSource().getLevelPath(state.get().levelId()),
                    mc.getUser().getProfileId())) {
                LOGGER.info("[DungeonTrain] Crash recovery: '{}' was already Free Play; nothing to salvage, forgetting the run.",
                        state.get().levelId());
                CrashRunState.clear(PlayerDataPaths.root());
                return Optional.empty();
            }
            return state;
        } catch (Exception e) {
            // A recovery offer must never be the thing that stops the game reaching the menu.
            LOGGER.warn("[DungeonTrain] Crash recovery check failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
