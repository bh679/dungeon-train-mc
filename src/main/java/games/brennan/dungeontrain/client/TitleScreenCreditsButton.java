package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.chat.MenuChatButtonHandler;
import games.brennan.dungeontrain.client.credits.CreditsScreen;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.BilibiliIconButton;
import games.brennan.dungeontrain.client.menu.CreditsIconButton;
import games.brennan.dungeontrain.client.menu.DiscordIconButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.net.URI;

/**
 * Owns DT's title-screen icon column: a <b>Credits</b> button (a vanilla book) opening
 * {@link CreditsScreen}, and a <b>Discord</b> logomark one slot above it opening the invite
 * — the compact form of what used to be a text button in the Train Editor row, which now
 * carries Video Tools instead (see {@code TitleScreenLayoutHandler}).
 *
 * <p>Both live in this one handler because two subscribers at the same
 * {@link EventPriority#LOWEST} have unspecified relative order, so a separate Discord
 * handler could run first, fail to find the Credits button, and stack on top of it. One
 * handler computes the anchor once and stacks upward from it.</p>
 *
 * <p>The column stacks directly <b>above the vanilla accessibility button</b> — and
 * above DT's main-menu chat (envelope) icon when that is showing, since the chat
 * icon itself sits one slot above accessibility (see
 * {@code client.chat.MenuChatButtonHandler}). Both anchors are located by their
 * narration message in {@code event.getListenersList()}. The handler runs at
 * {@link EventPriority#LOWEST} so it fires after the chat handler has (or hasn't)
 * added its widget, making the "above the chat icon when present" decision reliable
 * despite otherwise-unspecified {@code Init.Post} handler order.</p>
 *
 * <p>A third icon — the <b>Bilibili</b> logomark — rides one slot above Discord, and only on a
 * Chinese-language client ({@link ClientLanguage#isChinese()}). Discord is blocked in mainland
 * China, so for those players the Discord mark below it is a dead end; Bilibili is the community
 * link that actually opens. It is added rather than substituted, because the gate is language and
 * not location.</p>
 *
 * <p>If the accessibility button can't be found (e.g. another mod removed it), the
 * icon falls back to the top-right corner so Credits is always reachable. No-ops on
 * any non-{@link TitleScreen}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenCreditsButton {

    private static final Component NARRATION = Component.translatable("gui.dungeontrain.credits.title");
    private static final Component TOOLTIP = Component.translatable("gui.dungeontrain.credits.button.tooltip");

    private static final Component DISCORD_NARRATION =
            Component.translatable("gui.dungeontrain.discord_button");

    private static final Component BILIBILI_NARRATION =
            Component.translatable("gui.dungeontrain.bilibili_button");

    /** Vanilla accessibility button narration (iconOnly TitleScreen variant) — our anchor. */
    private static final Component ACCESSIBILITY_KEY = Component.translatable("options.accessibility");
    /** DT's main-menu chat (envelope) icon narration, when present. */
    private static final Component CHAT_KEY = Component.translatable("gui.dungeontrain.menu_chat.button");

    private static final int SIZE = 20;
    private static final int GAP = 4;
    private static final int MARGIN = 4;

    private TitleScreenCreditsButton() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        int x;
        int y;
        AbstractWidget accessibility = findWidget(event, ACCESSIBILITY_KEY);
        if (accessibility != null) {
            // Sit above the chat icon when it is actually showing, else above accessibility.
            AbstractWidget chat = findWidget(event, CHAT_KEY);
            int topY = (chat != null && chat.visible) ? chat.getY() : accessibility.getY();
            x = accessibility.getX();
            y = topY - SIZE - GAP;
        } else {
            // No accessibility button to anchor to — keep Credits reachable in the top-right corner.
            x = titleScreen.width - MARGIN - SIZE;
            y = MARGIN;
        }

        CreditsIconButton button = new CreditsIconButton(x, y, SIZE, NARRATION,
                b -> Minecraft.getInstance().setScreen(new CreditsScreen(titleScreen)));
        button.setTooltip(Tooltip.create(TOOLTIP));
        event.addListener(button);

        // Discord sits one slot above Credits, as a logomark rather than the word it used to be
        // in the Train Editor row (that slot is Video Tools now). Added from this handler rather
        // than its own on purpose: two subscribers at the same LOWEST priority have unspecified
        // order, so a separate handler could run before this one, fail to find the Credits button
        // and stack on top of it. One handler, one anchor computation, no race.
        //
        // If the player opted out of the developer welcome popup, the icon pulses so they can
        // still find their way to the channel without being re-prompted by a modal — standing
        // down while the menu-chat envelope pulses over unread messages, one pulse at a time.
        DiscordIconButton discord = new DiscordIconButton(x, y - SIZE - GAP, SIZE,
                DISCORD_NARRATION, b -> openDiscord(titleScreen),
                ClientDisplayConfig.isDeveloperPopupOptedOut(),
                MenuChatButtonHandler::hasUnreadPulse);
        discord.setTooltip(Tooltip.create(DISCORD_NARRATION));
        event.addListener(discord);

        // Bilibili, one slot further up — but only on a Chinese-language client. Discord is blocked
        // in mainland China, so for those players the icon directly below this one leads nowhere;
        // this is the community link that actually opens. Above rather than instead of Discord: the
        // gate is the client's LANGUAGE, not its location (Minecraft exposes no country), so a
        // Chinese-language client outside China would lose a working link if this replaced it.
        if (ClientLanguage.isChinese()) {
            BilibiliIconButton bilibili = new BilibiliIconButton(x, y - 2 * (SIZE + GAP), SIZE,
                    BILIBILI_NARRATION, b -> openBilibili(titleScreen));
            bilibili.setTooltip(Tooltip.create(BILIBILI_NARRATION));
            event.addListener(bilibili);
        }
    }

    /** Open the Bilibili channel through the vanilla confirm screen, returning to the title screen. */
    private static void openBilibili(Screen parent) {
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_BILIBILI);
        // Read at click time so a relay-served rotation still applies after the menu was built.
        String bilibiliUrl = OfficialLinks.bilibili();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_BILIBILI, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(bilibiliUrl));
            }
            Minecraft.getInstance().setScreen(parent);
        }, bilibiliUrl, true));
    }

    /** Open the Discord invite through the vanilla confirm screen, returning to the title screen. */
    private static void openDiscord(Screen parent) {
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_DISCORD);
        // Read at click time so a relay-served rotation still applies after the menu was built.
        String discordUrl = OfficialLinks.discord();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_DISCORD, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(discordUrl));
            }
            Minecraft.getInstance().setScreen(parent);
        }, discordUrl, true));
    }

    private static AbstractWidget findWidget(ScreenEvent.Init.Post event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget && message.equals(widget.getMessage())) {
                return widget;
            }
        }
        return null;
    }
}
