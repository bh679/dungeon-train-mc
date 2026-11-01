package games.brennan.dungeontrain.client.moty;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * When to put the {@link ModOfTheYearPopupScreen} in front of a player who qualifies
 * ({@link ModOfTheYear#shouldAsk}):
 * <ol>
 *   <li><b>On launch</b> — the first title screen of the session, once the menu has settled
 *       ({@link #OPEN_DELAY_TICKS}, the same wait {@code DeveloperWelcomePopupHandler} uses).</li>
 *   <li><b>After a recommendation</b> — an NPS answer of 8-10 from the death screen or
 *       {@code /feedback} ({@link #onNpsAnswered}). The survey is left to finish first: the popup
 *       waits until the player is back in the game or on the title screen, so it never cuts into
 *       the death screen's pages.</li>
 * </ol>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ModOfTheYearPopupHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ~1.2 s — lets the title logo and panorama settle before the popup takes over. */
    private static final int OPEN_DELAY_TICKS = 24;

    /** The launch check runs once per session, on the first title screen. */
    private static boolean launchChecked = false;
    /** A recommendation just came in; ask once the player is free (see {@link #playerIsFree}). */
    private static boolean askAfterAnswer = false;
    /** Ticks until a scheduled open; {@code -1} when nothing is scheduled. */
    private static int openDelayRemaining = -1;
    /** The screen the scheduled popup will sit over; the open is put off if the player has moved on. */
    private static Screen pendingParent = null;
    /** Whether the scheduled open is the launch ask (else the after-answer ask). */
    private static boolean pendingIsLaunch = false;

    private ModOfTheYearPopupHandler() {}

    /**
     * Record an NPS ("recommend") answer from either survey path, and queue the ask when it is a
     * recommendation and the contest has a phase open for this player. Client thread.
     */
    public static void onNpsAnswered(int score) {
        ClientDisplayConfig.setLastNpsScore(score);
        if (!ModOfTheYear.isRecommendation(score)) return;
        ClientDisplayConfig.markNpsRecommendedHigh();
        if (currentPhaseToAsk() != ModOfTheYear.Phase.NONE) {
            askAfterAnswer = true;
            LOGGER.info("ModOfTheYearPopup: recommendation ({}) — asking once the survey is done", score);
        }
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen title) || launchChecked) return;
        launchChecked = true;
        if (currentPhaseToAsk() == ModOfTheYear.Phase.NONE) return;
        schedule(title, true);
        LOGGER.info("ModOfTheYearPopup: launch — popup scheduled in {} ticks", OPEN_DELAY_TICKS);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (askAfterAnswer && openDelayRemaining < 0 && playerIsFree(mc)) {
            askAfterAnswer = false;
            schedule(mc.screen, false);
        }
        if (openDelayRemaining < 0) return;
        // Hold the countdown while the resource-loading splash is still over the title screen.
        if (mc.getOverlay() != null) return;
        // The title screen is rebuilt more than once while the game finishes loading, so the launch
        // ask follows whichever title screen is up; the after-answer ask needs the same screen.
        if (pendingIsLaunch && mc.screen instanceof TitleScreen title) {
            pendingParent = title;
        }
        if (mc.screen != pendingParent) {
            // The player moved on (opened a world, another prompt took over): ask at the next chance.
            LOGGER.info("ModOfTheYearPopup: screen changed during open delay; asking later");
            if (pendingIsLaunch) {
                launchChecked = false;
            } else {
                askAfterAnswer = true;
            }
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        if (--openDelayRemaining > 0) return;
        openDelayRemaining = -1;
        Screen parent = pendingParent;
        pendingParent = null;
        ModOfTheYear.Phase phase = currentPhaseToAsk();
        if (phase == ModOfTheYear.Phase.NONE) return;
        LOGGER.info("ModOfTheYearPopup: showing {} popup", phase);
        mc.setScreen(new ModOfTheYearPopupScreen(parent, phase));
    }

    private static void schedule(Screen parent, boolean launch) {
        pendingParent = parent;
        pendingIsLaunch = launch;
        openDelayRemaining = OPEN_DELAY_TICKS;
    }

    /** In the game with no screen up, or on the title screen. */
    private static boolean playerIsFree(Minecraft mc) {
        if (mc.screen instanceof TitleScreen) return true;
        return mc.screen == null && mc.player != null && mc.player.isAlive();
    }

    /** The phase this player should be asked about today, or {@code NONE}. */
    private static ModOfTheYear.Phase currentPhaseToAsk() {
        ModOfTheYear.Phase phase = ModOfTheYear.phaseOn(LocalDate.now(ZoneOffset.UTC));
        boolean qualifies = ModOfTheYear.qualifies(
                ClientDisplayConfig.isNpsRecommendedHigh(), ClientDisplayConfig.getLastNpsScore());
        return ModOfTheYear.shouldAsk(phase, qualifies,
                ClientDisplayConfig.isMotyNominated(), ClientDisplayConfig.isMotyVoted())
                ? phase : ModOfTheYear.Phase.NONE;
    }
}
