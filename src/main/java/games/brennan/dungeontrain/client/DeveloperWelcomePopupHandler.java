package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Decides when to surface the {@link DeveloperWelcomePopupScreen} over the
 * title screen.
 *
 * <p>UX rule: <b>don't bombard a brand-new player on first launch</b> — only
 * show the popup once the player has been in a world this session and has
 * returned to the title screen. Re-fires on every return (e.g. play → quit
 * → popup → play again → quit → popup again) but does not re-trigger if the
 * player bounces around the title menu (Options → Back) without re-entering
 * a world.</p>
 *
 * <h3>"After the menu has loaded" delay</h3>
 * <p>When a qualifying title-screen return is detected, the popup-open is
 * deferred by {@link #OPEN_DELAY_TICKS} ticks (~1.2 s) so the player sees
 * the title menu fully settle (logo fade, panorama spin-up, button layout)
 * before the popup screen takes over and slides in. The deferral is
 * cancelled if the player navigates away from the title screen during the
 * wait (e.g. clicks Singleplayer immediately).</p>
 *
 * <h3>Why per-tick polling for "has been in world"</h3>
 * <p>The {@link EditorAutoOpenHandler} docs (and our own runtime experience)
 * note that {@code ClientPlayerNetworkEvent.LoggingOut} fires spuriously
 * during the integrated-server handshake when transitioning <i>into</i> a
 * fresh world. We avoid that whole class of footgun by polling
 * {@link Minecraft#player}/{@link Minecraft#level} on
 * {@link ClientTickEvent.Post} — once both are non-null we latch
 * {@link #hasBeenInWorldThisSession} to {@code true} and stop caring until
 * the title screen consumes it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeveloperWelcomePopupHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ~1.2 s at 20 ticks/sec — long enough for the title logo fade and any other menu settling animations to complete before the popup screen replaces the title. */
    private static final int OPEN_DELAY_TICKS = 24;

    /**
     * Latched {@code true} on the first tick we see both a player and a
     * level loaded; cleared back to {@code false} when the popup-open is
     * scheduled.
     */
    private static volatile boolean hasBeenInWorldThisSession = false;

    /**
     * Ticks remaining until we open the popup. {@code -1} means "not
     * armed". Decremented every client tick once armed; when it hits 0
     * the popup is opened (if we're still on the title screen).
     */
    private static int openDelayRemaining = -1;

    /** Title-screen instance captured when the delay was armed, so we can hand it to the popup as parent and detect if the player navigated away mid-delay. */
    private static TitleScreen pendingParent = null;

    private DeveloperWelcomePopupHandler() {}

    public static void onClientTickPost() {
        Minecraft mc = Minecraft.getInstance();

        // 1) Detect "player has been in a world this session" — latches once and stays true.
        if (!hasBeenInWorldThisSession && mc.player != null && mc.level != null) {
            hasBeenInWorldThisSession = true;
            LOGGER.info("DeveloperWelcomePopup: detected player in world — armed for next title-screen return");
        }

        // 2) If a popup-open is scheduled, count it down.
        if (openDelayRemaining > 0) {
            // Cancel if the player navigated away from the title screen
            // before the delay elapsed (e.g. clicked Singleplayer).
            if (!(mc.screen instanceof TitleScreen)) {
                LOGGER.info("DeveloperWelcomePopup: title screen changed during open-delay; cancelling");
                openDelayRemaining = -1;
                pendingParent = null;
                return;
            }
            openDelayRemaining--;
            if (openDelayRemaining == 0) {
                TitleScreen parent = pendingParent;
                openDelayRemaining = -1;
                pendingParent = null;
                if (parent != null && mc.screen == parent) {
                    // Capture "is this a returning showing?" BEFORE marking
                    // the popup as shown. First-time players don't see the
                    // opt-out button; returning players do.
                    boolean isReturning = ClientDisplayConfig.isDeveloperPopupShownBefore();
                    LOGGER.info("DeveloperWelcomePopup: open-delay elapsed — showing popup (returning={})", isReturning);
                    mc.setScreen(new DeveloperWelcomePopupScreen(parent, isReturning));
                    // Mark shown so the next surfacing includes the opt-out
                    // button. Persists across sessions via
                    // dungeontrain-client.toml.
                    ClientDisplayConfig.setDeveloperPopupShownBefore(true);
                } else {
                    LOGGER.info("DeveloperWelcomePopup: parent screen changed at open time; skipping");
                }
            }
        }
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        if (!hasBeenInWorldThisSession) {
            return;
        }
        // Hard opt-out check — if the player previously clicked
        // "Don't ask again", honour that across all sessions and never
        // surface the popup. Consume the flag too so we don't keep
        // checking on every title-screen return.
        if (ClientDisplayConfig.isDeveloperPopupOptedOut()) {
            hasBeenInWorldThisSession = false;
            return;
        }
        // Consume the flag now so the popup only fires once per
        // play-and-return cycle. Re-entering a world re-arms it.
        hasBeenInWorldThisSession = false;

        // Don't open the popup immediately — schedule it for OPEN_DELAY_TICKS
        // ticks from now so the player gets to see the title menu fully
        // load and settle first. The tick loop above does the actual open.
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
        LOGGER.info("DeveloperWelcomePopup: title-screen return detected — popup scheduled in {} ticks (~{}ms)",
                OPEN_DELAY_TICKS, OPEN_DELAY_TICKS * 50);
    }
}
