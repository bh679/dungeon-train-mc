package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Surfaces {@link PoliticalFilterPromptScreen} once, on the title screen, for the players
 * {@link PoliticalFilterPrefs#shouldPrompt()} identifies.
 *
 * <p>On the TITLE screen rather than on world join, because the answer has to be in place before any
 * community content can be served — by the time a book is in a chest it is too late to ask.</p>
 *
 * <p>Opens after a short tick delay (the same trick {@link DeveloperWelcomePopupHandler} uses) and
 * bails if the player navigated away meanwhile, so it never steals a click from someone already
 * heading into a world. It also can't collide with Discord Presence's network-consent card:
 * {@code shouldPrompt()} requires consent to have been GRANTED already, so DP's screen is finished
 * with by the time this one is eligible.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PoliticalFilterPromptHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks to wait after the title screen initialises before opening. */
    private static final int OPEN_DELAY_TICKS = 24;

    /** Ticks remaining until the prompt opens; {@code -1} means "not armed". */
    private static int openDelayRemaining = -1;

    /** Title screen captured when the delay was armed — parent for the prompt, and the navigate-away check. */
    private static TitleScreen pendingParent = null;

    /**
     * Set once the prompt has been opened this session. The stored answer normally suppresses any
     * re-show on its own, but this covers the window between opening the screen and the player
     * actually answering it — without it, returning to the title screen with the prompt still
     * unanswered would arm a second one.
     */
    private static boolean openedThisSession = false;

    private PoliticalFilterPromptHandler() {}

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
        openDelayRemaining = -1;
        pendingParent = null;
        // Re-check: the player could have answered from the options screen during the delay.
        if (parent == null || mc.screen != parent || !PoliticalFilterPrefs.shouldPrompt()) return;
        openedThisSession = true;
        LOGGER.info("[DungeonTrain] PoliticalFilter: showing the one-time filter choice");
        mc.setScreen(new PoliticalFilterPromptScreen(parent));
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (openedThisSession || openDelayRemaining > 0) return;
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) return;
        if (!PoliticalFilterPrefs.shouldPrompt()) return;
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
    }
}
