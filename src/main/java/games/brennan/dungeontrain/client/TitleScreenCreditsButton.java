package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.credits.CreditsScreen;
import games.brennan.dungeontrain.client.menu.CreditsIconButton;
import games.brennan.dungeontrain.client.menu.ItemGlyphIconButton;
import games.brennan.dungeontrain.client.videotools.VideoToolsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Owns DT's title-screen icon column: a <b>Credits</b> button (a vanilla book) opening
 * {@link CreditsScreen}, and a <b>Video Tools</b> button (a spyglass) one slot above it
 * opening {@link VideoToolsScreen} — the filming guide for content creators.
 *
 * <p>Both live in this one handler because two subscribers at the same
 * {@link EventPriority#LOWEST} have unspecified relative order, so a separate Video Tools
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
 * <p>If the accessibility button can't be found (e.g. another mod removed it), the
 * icon falls back to the top-right corner so Credits is always reachable. No-ops on
 * any non-{@link TitleScreen}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenCreditsButton {

    private static final Component NARRATION = Component.translatable("gui.dungeontrain.credits.title");
    private static final Component TOOLTIP = Component.translatable("gui.dungeontrain.credits.button.tooltip");

    private static final Component VIDEO_TOOLS_NARRATION =
            Component.translatable("gui.dungeontrain.video_tools.title");
    private static final Component VIDEO_TOOLS_TOOLTIP =
            Component.translatable("gui.dungeontrain.video_tools.button.tooltip");

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

        // Video Tools sits one slot above Credits. Added here rather than from its own
        // handler on purpose: two subscribers at the same LOWEST priority have unspecified
        // order, so a separate handler could run before this one, fail to find the Credits
        // button and stack on top of it. One handler, one anchor computation, no race.
        ItemGlyphIconButton videoTools = new ItemGlyphIconButton(x, y - SIZE - GAP, SIZE, Items.SPYGLASS,
                VIDEO_TOOLS_NARRATION, b -> {
                    UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_VIDEO_TOOLS);
                    Minecraft.getInstance().setScreen(new VideoToolsScreen(titleScreen));
                });
        videoTools.setTooltip(Tooltip.create(VIDEO_TOOLS_TOOLTIP));
        event.addListener(videoTools);
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
