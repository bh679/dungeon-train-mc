package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.menu.PatreonIconButton;
import games.brennan.dungeontrain.client.support.SupportScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
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
 * Replaces the vanilla <b>Minecraft Realms</b> button on the title screen with
 * a row split between a <b>Support the Mod</b> button (opening {@link
 * SupportScreen}) and a compact <b>Patreon</b> icon button — together filling
 * the exact bounds the Realms button occupied.
 *
 * <p>Realms is a paid first-party service irrelevant to a modded single-player
 * experience, so its slot is the natural home for the support call-to-action.
 * The Realms button is located by matching its label
 * ({@code Component.translatable("menu.online")}), removed via
 * {@link ScreenEvent.Init#removeListener}, and the two replacements are added in
 * its place. Both the Support button and the Patreon icon open through vanilla's
 * {@link ConfirmLinkScreen} where a URL is involved.</p>
 *
 * <p>Independent of {@link TitleScreenLayoutHandler} (which reshuffles the
 * Mods/Options/Quit row and never touches Realms). If the Realms button can't be
 * found (Realms disabled, or another mod already rewrote the menu) this logs a
 * warning and leaves the menu untouched rather than inventing a slot.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenSupportButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The vanilla Realms button label — "Minecraft Realms". */
    private static final Component REALMS_KEY = Component.translatable("menu.online");
    private static final Component SUPPORT_LABEL = Component.translatable("gui.dungeontrain.support.button");
    private static final Component PATREON_NARRATION = Component.translatable("gui.dungeontrain.support.patreon_icon");
    private static final Component PATREON_TOOLTIP = Component.translatable("gui.dungeontrain.support.patreon_icon.tooltip");

    private static final int GAP = 4;

    private TitleScreenSupportButton() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        Button realms = findButton(event, REALMS_KEY);
        if (realms == null) {
            LOGGER.warn("TitleScreenSupportButton: could not locate the Realms button; skipping support/patreon buttons.");
            return;
        }

        int x = realms.getX();
        int y = realms.getY();
        int w = realms.getWidth();
        int h = realms.getHeight();
        event.removeListener(realms);

        // Split the Realms slot: Support the Mod fills the row, a square Patreon
        // icon (row-height) sits at the right — together exactly the old bounds.
        int iconW = h;
        int supportW = w - iconW - GAP;

        event.addListener(new DarkTintedButton(x, y, supportW, h, SUPPORT_LABEL,
                b -> Minecraft.getInstance().setScreen(new SupportScreen(titleScreen))));

        PatreonIconButton patreon = new PatreonIconButton(x + supportW + GAP, y, iconW,
                PATREON_NARRATION, b -> openPatreon(titleScreen));
        patreon.setTooltip(Tooltip.create(PATREON_TOOLTIP));
        event.addListener(patreon);

        LOGGER.info("TitleScreenSupportButton: replaced Realms button with Support + Patreon.");
    }

    private static void openPatreon(Screen parent) {
        // Read at click time so a relay-served rotation still applies after the menu was built.
        String patreonUrl = OfficialLinks.patreon();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(patreonUrl));
            }
            Minecraft.getInstance().setScreen(parent);
        }, patreonUrl, true));
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
