package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Puts the translation editor on the title screen, stacked directly above the vanilla language
 * button — where a player already goes to think about what language they are playing in, and
 * right beside the "Localized by …" credit that tells them who did the translating.
 *
 * <p>Anchors to the language button by its translated label the way every other DT title-screen
 * hook does, and skips quietly if another mod has rewritten the menu and it is gone. Stacking
 * above (rather than beside) is what keeps it clear of both the Options row to its right and the
 * credit label immediately below it.</p>
 *
 * <p>Hidden on {@code en_us}: English is the source language, so there is nothing to translate
 * into. That check is the reason this is worth a button at all — the players who see it are
 * exactly the ones playing in a machine-translated locale.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenTranslateButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component LANGUAGE_KEY = Component.translatable("options.language");
    /**
     * Realms' pencil-and-paper icon. Reusing a vanilla sprite rather than shipping our own keeps
     * this consistent with the menu-chat button, which borrows {@code icon/invite} the same way.
     */
    private static final ResourceLocation EDIT_SPRITE =
        ResourceLocation.withDefaultNamespace("icon/draft_report");
    private static final int SPRITE_W = 15;
    private static final int SPRITE_H = 15;
    private static final int BUTTON_SIZE = 20;
    private static final int GAP = 4;

    private TitleScreenTranslateButton() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        // The title screen is where a client with no world loaded does its relay work, so it is
        // where both halves of the translation exchange happen. Unconditional: a player who
        // submitted in German and then switched to English still has a queue to drain.
        TranslationOutbox.get().flush();
        ApprovedTranslationsFetcher.fetchOnce();

        String locale = mc.getLanguageManager().getSelected();
        if (!TranslationScreen.isEditable(locale)) {
            return; // English is the source language — nothing to translate into
        }

        AbstractWidget language = findWidget(event, LANGUAGE_KEY);
        if (language == null) {
            LOGGER.debug("Translate button: language button not found; skipping.");
            return;
        }

        SpriteIconButton button = SpriteIconButton.builder(
                Component.translatable("gui.dungeontrain.translate.button"),
                b -> mc.setScreen(new TranslationScreen(titleScreen, locale)),
                true)
            .width(BUTTON_SIZE)
            .sprite(EDIT_SPRITE, SPRITE_W, SPRITE_H)
            .build();
        button.setPosition(language.getX(), language.getY() - BUTTON_SIZE - GAP);
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.translatable("gui.dungeontrain.translate.button.tooltip", locale)));
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
