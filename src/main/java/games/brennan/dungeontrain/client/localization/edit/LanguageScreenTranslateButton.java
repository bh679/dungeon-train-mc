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
 * The editor's second doorway: in the footer row of the vanilla Language screen, between vanilla's
 * "Font Settings" and Done.
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

    /** Vanilla's own spacing between the two footer buttons ({@code LinearLayout.horizontal().spacing(8)}). */
    private static final int GAP = 8;
    /** Each button takes 30% of the screen, so the row of three fills 90% plus the gaps. */
    private static final int WIDTH_PERCENT = 30;
    private static final int MIN_WIDTH = 60;
    /** The vanilla button that opens font settings, to the left of Done in the same footer row. */
    private static final Component FONT_KEY = Component.translatable("options.font");

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

        // Vanilla's footer here is a horizontal row of TWO buttons — "Font Settings" and Done — not
        // Done alone (LanguageSelectScreen#addFooter). Anchoring to Done and placing ourselves beside
        // it therefore lands on top of the font button, so the whole row is re-laid-out as three.
        AbstractWidget font = findWidget(event, FONT_KEY);

        // A labelled button, not an icon. This is a vanilla options screen, whose vocabulary is wide
        // labelled buttons; icons earn their place in the editor, where they save a crowded row, and
        // read as an afterthought pinned to someone else's footer here.
        Button button = Button.builder(
                Component.translatable("gui.dungeontrain.translate.button"),
                b -> Minecraft.getInstance().setScreen(new TranslationScreen(screen, target)))
            .bounds(0, done.getY(), MIN_WIDTH, done.getHeight())
            .tooltip(Tooltip.create(
                Component.translatable("gui.dungeontrain.translate.button.tooltip", target)))
            .build();

        // Equal thirds, centred as one row, with vanilla's own 8px spacing. Sized off the screen
        // rather than off Done's 150 so the row scales with the window; clamped so it can never
        // overflow at a small size or a large GUI scale. init() re-runs on resize, so a window
        // change re-derives the whole row rather than accumulating offsets.
        AbstractWidget[] row = font != null
            ? new AbstractWidget[] {font, button, done}
            : new AbstractWidget[] {button, done};
        int w = Math.max(MIN_WIDTH, screen.width * WIDTH_PERCENT / 100);
        int span = w * row.length + GAP * (row.length - 1);
        if (span > screen.width) {
            w = Math.max(1, (screen.width - GAP * (row.length - 1)) / row.length);
            span = w * row.length + GAP * (row.length - 1);
        }
        int x = (screen.width - span) / 2;
        for (AbstractWidget widget : row) {
            widget.setWidth(w);
            widget.setX(x);
            widget.setY(done.getY());
            x += w + GAP;
        }
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
