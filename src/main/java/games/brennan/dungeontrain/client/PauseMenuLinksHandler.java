package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.support.SupportScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.net.URI;

/**
 * Replaces the pause menu's vanilla <b>Feedback</b> | <b>Report Bugs</b> row with
 * <b>Discord</b> | <b>Support the Mod</b> — the same two calls-to-action the title
 * screen carries, brought to where a player actually spends their time.
 *
 * <p>Both community and support links previously lived only on the title screen
 * ({@link TitleScreenLayoutHandler}, {@link TitleScreenSupportButton}), which a player
 * never sees again once a run is underway. The vanilla row this takes over points at
 * Mojang's feedback site and bug tracker — the wrong destination during a modded run
 * (Dungeon Train ships its own bug-report survey on the death screen), which makes it
 * the natural slot, exactly as Realms is on the title screen.</p>
 *
 * <p>Kept separate from {@link PauseMenuLayoutHandler}, which owns the Save-and-Quit slot
 * and is singleplayer-only; this row is shown in both. If the two vanilla buttons can't
 * be located — a multiplayer server publishing {@linkplain net.minecraft.server.ServerLinks
 * server links} swaps that row for Feedback + Links, or another mod rewrote the menu —
 * this logs a warning and leaves the menu untouched rather than inventing a slot.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PauseMenuLinksHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla "Feedback" — opens Mojang's feedback site. */
    private static final Component SEND_FEEDBACK_KEY = Component.translatable("menu.sendFeedback");
    /** Vanilla "Report Bugs" — opens Mojang's bug tracker. */
    private static final Component REPORT_BUGS_KEY = Component.translatable("menu.reportBugs");

    private static final Component DISCORD_LABEL = Component.translatable("gui.dungeontrain.discord_button");
    private static final Component SUPPORT_LABEL = Component.translatable("gui.dungeontrain.support.button");

    /**
     * Whether the swap has already been reported this session. Unlike the title screen, the pause
     * menu is rebuilt every time a player presses ESC (and again on every window resize), so an
     * unconditional log line would bury the rest of the client log in identical entries. The
     * warning path stays unconditional on purpose — a menu that stops being found part-way through
     * a session is exactly the thing worth seeing repeated.
     */
    private static boolean loggedSwap;

    private PauseMenuLinksHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
            return;
        }
        // F3+ESC pauses without building any menu — nothing to replace, and warning here
        // would fire every time a player freezes the game to line up a screenshot.
        if (!pauseScreen.showsPauseMenu()) {
            return;
        }

        // A client that launched straight into a world may never have built the title
        // screen, so this can be the first chance to pick up relay-rotated links.
        OfficialLinks.ensureFetched();

        Button feedback = findButton(event, SEND_FEEDBACK_KEY);
        Button reportBugs = findButton(event, REPORT_BUGS_KEY);
        if (feedback == null || reportBugs == null) {
            LOGGER.warn("PauseMenuLinks: could not locate the Feedback/Report Bugs row (feedback={}, reportBugs={}); skipping Discord/Support buttons.",
                    feedback != null, reportBugs != null);
            return;
        }

        int discordX = feedback.getX();
        int discordY = feedback.getY();
        int discordW = feedback.getWidth();
        int discordH = feedback.getHeight();

        int supportX = reportBugs.getX();
        int supportY = reportBugs.getY();
        int supportW = reportBugs.getWidth();
        int supportH = reportBugs.getHeight();

        event.removeListener(feedback);
        event.removeListener(reportBugs);

        event.addListener(new DarkTintedButton(discordX, discordY, discordW, discordH,
                DISCORD_LABEL, b -> openDiscord(pauseScreen)));

        event.addListener(new DarkTintedButton(supportX, supportY, supportW, supportH, SUPPORT_LABEL, b -> {
            // The headline funnel metric, same as on the title screen: how many players
            // tap "Support the Mod" at all — here, mid-run rather than from the menu.
            UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_SUPPORT);
            Minecraft.getInstance().setScreen(new SupportScreen(pauseScreen));
        }));

        if (!loggedSwap) {
            loggedSwap = true;
            LOGGER.info("PauseMenuLinks: replaced Feedback/Report Bugs with Discord + Support the Mod.");
        }
    }

    private static void openDiscord(Screen parent) {
        UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_DISCORD);
        // Read at click time so a relay-served rotation still applies after the menu was built.
        String discordUrl = OfficialLinks.discord();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_DISCORD, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(discordUrl));
            }
            Minecraft.getInstance().setScreen(parent);
        }, discordUrl, true));
    }

    private static Button findButton(ScreenEvent.Init.Post event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }
}
