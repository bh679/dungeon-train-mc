package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Surfaces {@link DpiBypassPromptScreen} on the title screen when {@link DpiBypassDetector} finds a
 * DPI-bypass tool running — the thing most likely to be standing between this client and the relay.
 *
 * <p>On the TITLE screen for the same reason the sibling prompts are: it is the one place a player
 * is not yet mid-anything, and the relay features the warning is about start being fetched the
 * moment they load a world.</p>
 *
 * <h3>Why this one is tick-driven rather than armed at {@code ScreenEvent.Init.Post}</h3>
 * <p>{@link ConfigDeviationPromptHandler} and {@link PoliticalFilterPromptHandler} can answer
 * "should I prompt?" synchronously at screen init. This one cannot — the answer comes from a process
 * enumeration that has no business running on the render thread. So the probe is kicked onto the IO
 * pool the first time the title screen is up, and the arming check simply runs each tick until the
 * result lands. Everything after that is the same shape: a short delay, then open, and bail if the
 * player has navigated away meanwhile so it never steals a click from someone already heading into a
 * world.</p>
 *
 * <p>The per-tick check also settles the collision with the other title-screen cards for free. If
 * one of them is up, {@code mc.screen} is not a {@link TitleScreen}, so this handler stays disarmed
 * and quietly tries again once the player is back at the title screen. First card up wins; this one
 * waits its turn rather than landing on top.</p>
 *
 * <p>Gated on network consent having been GRANTED: without it DT makes no relay calls at all, so
 * there is nothing for a bypass tool to interfere with and the warning would be noise on top of the
 * consent screen the player is already answering. Same gate, same reasoning, as
 * {@link PoliticalFilterPrefs#shouldPrompt()}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DpiBypassPromptHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks to wait after arming before opening — matches the sibling prompts. */
    private static final int OPEN_DELAY_TICKS = 24;

    /** Ticks remaining until the prompt opens; {@code -1} means "not armed". */
    private static int openDelayRemaining = -1;

    /** Title screen captured when the delay was armed — parent for the prompt, and the navigate-away check. */
    private static TitleScreen pendingParent = null;

    /** Set once the probe has been handed to the IO pool, so it is started exactly once per session. */
    private static boolean probeStarted = false;

    /**
     * Set once the prompt has been opened this session. The stored opt-out normally suppresses a
     * re-show on its own, but this covers the window between opening the screen and the player
     * dismissing it — and, more to the point, keeps a player who did NOT opt out from meeting the
     * same warning every time they back out of the world list.
     */
    private static boolean openedThisSession = false;

    private DpiBypassPromptHandler() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (openedThisSession) return;

        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen titleScreen)) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }

        if (openDelayRemaining < 0) {
            arm(titleScreen);
            return;
        }

        openDelayRemaining--;
        if (openDelayRemaining > 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        // Re-check: the player could have opted out from another surface during the delay.
        if (parent == null || mc.screen != parent || !shouldPrompt()) return;

        String tool = DpiBypassDetector.detectNow();
        if (tool == null) return;
        openedThisSession = true;
        LOGGER.info("[DungeonTrain] DPI-bypass: warning the player about {}", tool);
        mc.setScreen(new DpiBypassPromptScreen(parent, tool));
    }

    /** Start the probe if it hasn't run, and arm the delay once its result says there is something to say. */
    private static void arm(TitleScreen titleScreen) {
        if (ClientDisplayConfig.isDpiBypassWarningOptedOut()) return;
        if (!DiscordPresenceClientConfig.isGranted()) return;

        if (!DpiBypassDetector.hasResult()) {
            if (!probeStarted) {
                probeStarted = true;
                Util.ioPool().execute(DpiBypassDetector::detectNow);
            }
            return; // Nothing to decide until the probe lands; the next tick asks again.
        }

        if (!shouldPrompt()) return;
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
    }

    /**
     * Whether there is a warning to give. Safe to call from the client thread once
     * {@link DpiBypassDetector#hasResult()} is true — the probe is cached by then.
     */
    private static boolean shouldPrompt() {
        return !ClientDisplayConfig.isDpiBypassWarningOptedOut()
                && DiscordPresenceClientConfig.isGranted()
                && DpiBypassDetector.hasResult()
                && DpiBypassDetector.detectNow() != null;
    }
}
