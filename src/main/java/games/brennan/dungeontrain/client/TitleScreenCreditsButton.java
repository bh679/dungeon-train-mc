package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.credits.CreditsScreen;
import games.brennan.dungeontrain.client.menu.CreditsIconButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds a small <b>Credits</b> icon button (a vanilla book) to the title screen,
 * opening {@link CreditsScreen}.
 *
 * <p>The icon stacks directly <b>above the vanilla accessibility button</b> — and
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
