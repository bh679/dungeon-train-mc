package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.support.BookPromoScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Dev-only doorway to {@link BookPromoScreen} — buying a community book a better place in the shared
 * pool. Gated on {@link DungeonTrain#isDevBuild()}, so on a {@code main} build the icon is never
 * added and players see nothing: the pricing, the copy and the relay-side fulfilment are all still
 * being worked out, and this exists so they can be looked at in a real client meanwhile.
 *
 * <p>An icon rather than a labelled button, and stacked <b>above the vanilla language button</b> —
 * the same column the dev-only translation editor uses, one slot higher, so the two dev affordances
 * read as a pair and neither crowds the Options row or the "Localized by …" credit below.</p>
 *
 * <p>The anchor is deliberately a <b>vanilla</b> widget rather than DT's own translate icon. Two
 * {@code Init.Post} handlers have no defined order between them, so anchoring to the other icon would
 * make this position depend on which ran first (the problem {@link TitleScreenCreditsButton} has to
 * solve with {@code EventPriority.LOWEST}); measuring from the language button instead lands the same
 * place either way. Both icons are dev-only, so they appear and disappear together.</p>
 *
 * <p>If the language button is gone (another mod rewrote the menu) this skips quietly rather than
 * inventing a slot.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenBookPromoButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component LANGUAGE_KEY = Component.translatable("options.language");
    private static final Component NARRATION = Component.translatable("gui.dungeontrain.book_promo.button");
    private static final Component TOOLTIP = Component.translatable("gui.dungeontrain.book_promo.button.tooltip");

    /**
     * Realms' newspaper icon — the closest vanilla has to "featured". Reusing a vanilla sprite rather
     * than shipping our own is what the menu-chat ({@code icon/invite}) and translate
     * ({@code icon/draft_report}) buttons do.
     */
    private static final ResourceLocation NEWS_SPRITE = ResourceLocation.withDefaultNamespace("icon/news");
    private static final int SPRITE_W = 14;
    private static final int SPRITE_H = 14;
    private static final int BUTTON_SIZE = 20;
    private static final int GAP = 4;
    /** Slots above the language button: 1 is the translate icon's, so this takes 2. */
    private static final int SLOTS_ABOVE = 2;

    private TitleScreenBookPromoButton() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        if (!DungeonTrain.isDevBuild()) {
            return; // release build — the page is not offered to players yet
        }

        // The page's buttons are built from the relay's checkout route, and the title screen is where
        // a client with no world loaded does its relay work. Idempotent per session.
        OfficialLinks.ensureFetched();

        AbstractWidget language = findWidget(event, LANGUAGE_KEY);
        if (language == null) {
            LOGGER.debug("Book promo button: language button not found; skipping.");
            return;
        }

        SpriteIconButton button = SpriteIconButton.builder(
                        NARRATION,
                        b -> {
                            UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_BOOK_PROMO);
                            Minecraft.getInstance().setScreen(new BookPromoScreen(titleScreen));
                        },
                        true)
                .width(BUTTON_SIZE)
                .sprite(NEWS_SPRITE, SPRITE_W, SPRITE_H)
                .build();
        button.setPosition(language.getX(), language.getY() - SLOTS_ABOVE * (BUTTON_SIZE + GAP));
        button.setTooltip(Tooltip.create(TOOLTIP));
        event.addListener(button);
    }

    private static AbstractWidget findWidget(ScreenEvent.Init.Post event, Component message) {
        for (var listener : event.getScreen().children()) {
            if (listener instanceof AbstractWidget widget && message.equals(widget.getMessage())) {
                return widget;
            }
        }
        return null;
    }
}
