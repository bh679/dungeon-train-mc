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
 * <p>Hidden on {@code en_us} in a release build: English is the source language, so there is
 * nothing to translate into, and the players who see the button are exactly the ones playing in a
 * machine-translated locale. A dev build shows it anyway and points it at the dev target locale,
 * so the editor can be tested without making every button in it unreadable.</p>
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
        TranslationContributor.refreshOnce();

        // On a release build this is the player's own language. On a dev build with the game in
        // English it is the dev target instead (see TranslationTarget) — the chrome stays readable
        // while the language under test is not.
        String target = TranslationTarget.resolveForClient();
        if (target.isEmpty()) {
            return; // English on a release build — the source language has nothing to translate
        }
        // ...and again for the language being EDITED, when that is not the one being displayed.
        // fetchOnce() above only ever covers the display locale, so on a dev build the editor would
        // otherwise have no idea which of its rows an operator had already approved. Cached, not
        // installed, when the two differ — see ApprovedTranslationsFetcher#accept.
        ApprovedTranslationsFetcher.fetchOnceFor(target);

        AbstractWidget language = findWidget(event, LANGUAGE_KEY);
        if (language == null) {
            LOGGER.debug("Translate button: language button not found; skipping.");
            return;
        }

        SpriteIconButton button = SpriteIconButton.builder(
                Component.translatable("gui.dungeontrain.translate.button"),
                b -> mc.setScreen(new TranslationScreen(titleScreen, target)),
                true)
            .width(BUTTON_SIZE)
            .sprite(EDIT_SPRITE, SPRITE_W, SPRITE_H)
            .build();
        button.setPosition(language.getX(), language.getY() - BUTTON_SIZE - GAP);
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.translatable("gui.dungeontrain.translate.button.tooltip", target)));
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
