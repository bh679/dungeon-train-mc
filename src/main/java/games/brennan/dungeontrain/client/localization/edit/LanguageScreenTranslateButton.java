package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * The editor's second doorway: beside Done on the vanilla Language screen.
 *
 * <p>This is the screen where someone is already thinking about what language they play in, and —
 * if it is one of the seventeen the mod ships machine-translated — where they are most likely to
 * have just noticed it reads oddly. Offering the editor at that moment is the whole point; the
 * title screen's button is for the player who already knows it exists.</p>
 *
 * <p>Targets the language the game is CURRENTLY DISPLAYING, not whichever row is highlighted in the
 * list: a selection there is not applied until Done, and handing someone an editor for a language
 * they have not switched to is the dev-build path, not something to put in front of a player
 * mid-choice.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LanguageScreenTranslateButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int GAP = 4;

    private LanguageScreenTranslateButton() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof LanguageSelectScreen screen)) {
            return;
        }
        String target = TranslationTarget.resolveForClient();
        if (target.isEmpty()) {
            return; // English on a release build — the source language has nothing to translate
        }

        AbstractWidget done = findWidget(event, CommonComponents.GUI_DONE);
        if (done == null) {
            // Vanilla builds this screen through a HeaderAndFooterLayout, and another mod may have
            // rebuilt the footer. Skip rather than guess a position — a button floating in the
            // middle of someone else's layout is worse than no button.
            LOGGER.debug("Translate button: Done not found on the language screen; skipping.");
            return;
        }

        // A labelled button the size of Done, not an icon. This is a vanilla options screen, whose
        // vocabulary is wide labelled buttons; icons earn their place in the editor, where they save
        // a crowded row, and read as an afterthought pinned to someone else's footer here.
        // Measured off Done itself rather than the vanilla 150x20, so it matches whatever this
        // screen actually laid out — including under another mod's layout.
        int w = done.getWidth();
        Button button = Button.builder(
                Component.translatable("gui.dungeontrain.translate.button"),
                b -> Minecraft.getInstance().setScreen(new TranslationScreen(screen, target)))
            .bounds(0, done.getY(), w, done.getHeight())
            .tooltip(Tooltip.create(
                Component.translatable("gui.dungeontrain.translate.button.tooltip", target)))
            .build();

        // Done is centred on its own; a same-width button beside it would leave the two visibly
        // off-centre, so they are placed as a pair the way vanilla lays out its own Cancel/Done
        // footers. init() re-runs on resize, so this survives a window change.
        int left = (screen.width - (w * 2 + GAP)) / 2;
        button.setX(left);
        done.setX(left + w + GAP);
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
