package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
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
    /** Vanilla insets a button's label by 2px each side before it starts scrolling it. */
    private static final int TEXT_INSET = 6;
    /** The vanilla button that opens font settings, to the left of Done in the same footer row. */
    private static final Component FONT_KEY = Component.translatable("options.font");
    private static final Component LABEL = Component.translatable("gui.dungeontrain.translate.button");

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

        // Init.Post can fire more than once for the same screen, and a second button would be one
        // this pass does not lay out — left wherever the previous pass put it, under the row. So an
        // existing one is re-used and re-placed rather than joined by a twin.
        AbstractWidget existing = findWidget(event, LABEL);

        // A labelled button, not an icon. This is a vanilla options screen, whose vocabulary is wide
        // labelled buttons; icons earn their place in the editor, where they save a crowded row, and
        // read as an afterthought pinned to someone else's footer here.
        // Through the prompt rather than straight into the editor: the list selection here is pending
        // until Done, so the language you are pointing at and the one you are running in can disagree.
        Button button = existing instanceof Button reused ? reused : Button.builder(
                LABEL,
                b -> LanguageSwitchPrompt.openEditor(screen, target))
            .bounds(0, done.getY(), MIN_WIDTH, done.getHeight())
            .tooltip(Tooltip.create(
                Component.translatable("gui.dungeontrain.translate.button.tooltip", target)))
            .build();

        // Equal thirds, centred as one row, with vanilla's own 8px spacing. Sized off the screen
        // rather than off Done's 150 so the row scales with the window; clamped so it can never
        // overflow at a small size or a large GUI scale. init() re-runs on resize, so a window
        // change re-derives the whole row rather than accumulating offsets.
        AbstractWidget[] all = font != null
            ? new AbstractWidget[] {font, button, done}
            : new AbstractWidget[] {button, done};
        int w = Math.max(MIN_WIDTH, screen.width * WIDTH_PERCENT / 100);
        int span = span(w, all.length, screen.width);
        if (span > screen.width) {
            w = Math.max(1, (screen.width - GAP * (all.length - 1)) / all.length);
            span = span(w, all.length, screen.width);
        }

        // German turns "Font Settings" into "Schriftarteinstellungen" — at a third of the screen the
        // labels stop fitting and the row reads as mush. Rather than let vanilla scroll three labels
        // at once, our button drops onto a line of its own above the row, which leaves the two vanilla
        // buttons half the span each: wide enough for the long words in every language we ship.
        if (fits(all, w)) {
            layoutRow(all, w, span, screen.width, done.getY());
        } else if (!wrapOntoOwnLine(screen, button, font, done, span)) {
            layoutRow(all, w, span, screen.width, done.getY()); // no room to wrap — one row it is
        }
        if (existing == null) {
            event.addListener(button);
        }
    }

    /** Places widgets left to right as one centred row of equal-width cells. */
    private static void layoutRow(AbstractWidget[] row, int w, int span, int screenWidth, int y) {
        int x = (screenWidth - span) / 2;
        for (AbstractWidget widget : row) {
            widget.setWidth(w);
            widget.setX(x);
            widget.setY(y);
            x += w + GAP;
        }
    }

    /**
     * Our button on a line of its own, in a strip taken from the bottom of the language list, with
     * vanilla's two buttons sharing the row below it.
     *
     * @return false when there is no list to take the strip from, and the caller should stay on one row
     */
    private static boolean wrapOntoOwnLine(LanguageSelectScreen screen, Button button,
                                           AbstractWidget font, AbstractWidget done, int span) {
        ObjectSelectionList<?> list = LanguageSwitchPrompt.languageList(screen);
        if (list == null) {
            return false;
        }
        int rowHeight = done.getHeight();
        int strip = rowHeight + GAP;

        // Vanilla's row keeps its place; the extra line goes above the footer, in space the list gives
        // back. repositionElements() restores the list to full height on every init, so this shrinks
        // from the full height each time rather than compounding.
        int listBottom = list.getY() + list.getHeight();
        list.updateSizeAndPosition(list.getWidth(), Math.max(rowHeight, list.getHeight() - strip),
            list.getY());

        AbstractWidget[] below = font != null ? new AbstractWidget[] {font, done}
                                              : new AbstractWidget[] {done};
        int w = below.length == 2 ? (span - GAP) / 2 : span;
        layoutRow(below, w, span(w, below.length, screen.width), screen.width, done.getY());

        button.setWidth(span);
        button.setX((screen.width - span) / 2);
        button.setY(listBottom - strip);
        return true;
    }

    /** Whether every label sits inside its cell — vanilla insets the text by 2px on each side. */
    private static boolean fits(AbstractWidget[] row, int w) {
        Font font = Minecraft.getInstance().font;
        for (AbstractWidget widget : row) {
            if (font.width(widget.getMessage()) + TEXT_INSET > w) {
                return false;
            }
        }
        return true;
    }

    private static int span(int cellWidth, int cells, int screenWidth) {
        return Math.min(screenWidth, cellWidth * cells + GAP * (cells - 1));
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
