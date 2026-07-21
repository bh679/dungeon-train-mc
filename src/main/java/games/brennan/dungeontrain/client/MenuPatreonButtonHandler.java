package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.MenuChatButtonHandler;
import games.brennan.dungeontrain.client.menu.PatreonIconButton;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.net.URI;

/**
 * Adds a small Patreon icon button to the title screen, in the bottom-left
 * affordance stack: directly above the menu-chat envelope when that's showing
 * ({@link MenuChatButtonHandler#isChatButtonVisible()}), otherwise in the
 * envelope's slot directly above the vanilla accessibility button. Opens the
 * Patreon page through vanilla's {@link ConfirmLinkScreen}.
 *
 * <p>Anchored to the accessibility button (a 20×20 {@code SpriteIconButton})
 * exactly like {@link MenuChatButtonHandler}; if that button can't be found
 * (another mod rewrote the menu) it skips quietly rather than guessing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class MenuPatreonButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PATREON_URL = "https://www.patreon.com/brennanhatton";

    private static final int BUTTON_SIZE = 20; // matches the accessibility + chat buttons
    private static final int GAP = 4;
    private static final Component ACCESSIBILITY_KEY = Component.translatable("options.accessibility");
    private static final Component NARRATION = Component.translatable("gui.dungeontrain.support.patreon_icon");
    private static final Component TOOLTIP = Component.translatable("gui.dungeontrain.support.patreon_icon.tooltip");

    private MenuPatreonButtonHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        AbstractWidget accessibility = findWidget(event, ACCESSIBILITY_KEY);
        if (accessibility == null) {
            LOGGER.debug("Menu Patreon: accessibility button not found; skipping the Patreon button.");
            return;
        }

        // One slot above accessibility, or two when the chat envelope occupies slot one.
        int slots = MenuChatButtonHandler.isChatButtonVisible() ? 2 : 1;
        int x = accessibility.getX();
        int y = accessibility.getY() - slots * (BUTTON_SIZE + GAP);
        PatreonIconButton button = new PatreonIconButton(x, y, BUTTON_SIZE, NARRATION, b -> openPatreon(titleScreen));
        button.setTooltip(Tooltip.create(TOOLTIP));
        event.addListener(button);
    }

    private static void openPatreon(Screen parent) {
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(PATREON_URL));
            }
            Minecraft.getInstance().setScreen(parent);
        }, PATREON_URL, true));
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
