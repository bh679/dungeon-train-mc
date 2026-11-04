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
import java.util.Optional;

/**
 * When to put the {@link ModOfTheYearPopupScreen} in front of a player who qualifies
 * ({@link ModOfTheYear#shouldAsk}):
 * <ol>
 *   <li><b>On launch</b> — the first title screen of the session, once the menu has settled
 *       ({@link #OPEN_DELAY_TICKS}, the same wait {@code DeveloperWelcomePopupHandler} uses).</li>
 *   <li><b>After a recommendation</b> — an NPS answer of 8-10 from the death screen or
 *       {@code /feedback} ({@link #onNpsAnswered}): right after the submit, over the survey, which
 *       it returns to when closed. If that screen goes away first (a quick respawn), it waits until
 *       the player is back in the game or on the title screen.</li>
 * </ol>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ModOfTheYearPopupHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ~1.2 s — lets the title logo and panorama settle before the popup takes over. */
    private static final int OPEN_DELAY_TICKS = 24;
    /** ~0.5 s after a rating is submitted — long enough to see the answer land. */
    private static final int ANSWER_DELAY_TICKS = 10;

    /** The launch check runs once per session, on the first title screen. */
    private static boolean launchChecked = false;
    /** The after-answer ask lost its screen; ask once the player is free (see {@link #playerIsFree}). */
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
        if (nextAsk().isEmpty()) return;
        LOGGER.info("ModOfTheYearPopup: recommendation ({}) — asking over the survey", score);
        schedule(Minecraft.getInstance().screen, false, ANSWER_DELAY_TICKS);
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen title) || launchChecked) return;
        launchChecked = true;
        if (nextAsk().isEmpty()) return;
        schedule(title, true, OPEN_DELAY_TICKS);
        LOGGER.info("ModOfTheYearPopup: launch — popup scheduled in {} ticks", OPEN_DELAY_TICKS);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (askAfterAnswer && openDelayRemaining < 0 && playerIsFree(mc)) {
            askAfterAnswer = false;
            schedule(mc.screen, false, OPEN_DELAY_TICKS);
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
        Optional<ModOfTheYear.Ask> ask = nextAsk();
        if (ask.isEmpty()) return;
        LOGGER.info("ModOfTheYearPopup: showing {} popup", ask.get());
        mc.setScreen(new ModOfTheYearPopupScreen(parent, ask.get()));
    }

    private static void schedule(Screen parent, boolean launch, int delayTicks) {
        pendingParent = parent;
        pendingIsLaunch = launch;
        openDelayRemaining = delayTicks;
    }

    /** In the game with no screen up, or on the title screen. */
    private static boolean playerIsFree(Minecraft mc) {
        if (mc.screen instanceof TitleScreen) return true;
        return mc.screen == null && mc.player != null && mc.player.isAlive();
    }

    /** What to ask this player today, if anything — the modpack before the mod. */
    static Optional<ModOfTheYear.Ask> nextAsk() {
        boolean qualifies = ModOfTheYear.qualifies(
                ClientDisplayConfig.isNpsRecommendedHigh(), ClientDisplayConfig.getLastNpsScore());
        return ModOfTheYear.nextAsk(ModOfTheYear.phaseOn(LocalDate.now(ZoneOffset.UTC)), qualifies,
                ClientDisplayConfig.motyDone());
    }
}
