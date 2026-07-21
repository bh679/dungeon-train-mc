package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.support.SupportScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Replaces the vanilla <b>Minecraft Realms</b> button on the title screen with
 * a Dungeon Train <b>Support the Mod</b> button that opens {@link SupportScreen}
 * — the "ways to help" hub (Patreon, affiliate, share links, feedback/Discord).
 *
 * <p>Realms is a paid first-party service irrelevant to a modded single-player
 * experience, so its slot is the natural home for the support call-to-action.
 * The Realms button is located by matching its label
 * ({@code Component.translatable("menu.online")}), removed via
 * {@link ScreenEvent.Init#removeListener}, and a {@link DarkTintedButton} is
 * added in its exact bounds so the layout is unchanged.</p>
 *
 * <p>Independent of {@link TitleScreenLayoutHandler} (which reshuffles the
 * Mods/Options/Quit row and never touches Realms), so the two
 * {@link ScreenEvent.Init.Post} subscribers don't conflict. If the Realms
 * button can't be found (Realms disabled, or another mod already rewrote the
 * menu) this logs a warning and leaves the menu untouched rather than
 * inventing a slot.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenSupportButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The vanilla Realms button label — "Minecraft Realms". */
    private static final Component REALMS_KEY = Component.translatable("menu.online");
    private static final Component SUPPORT_LABEL = Component.translatable("gui.dungeontrain.support.button");

    private TitleScreenSupportButton() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        Button realms = findButton(event, REALMS_KEY);
        if (realms == null) {
            LOGGER.warn("TitleScreenSupportButton: could not locate the Realms button; skipping support button.");
            return;
        }

        int x = realms.getX();
        int y = realms.getY();
        int w = realms.getWidth();
        int h = realms.getHeight();

        event.removeListener(realms);
        event.addListener(new DarkTintedButton(x, y, w, h, SUPPORT_LABEL,
                b -> Minecraft.getInstance().setScreen(new SupportScreen(titleScreen))));
        LOGGER.info("TitleScreenSupportButton: replaced Realms button with Support button.");
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
