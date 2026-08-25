package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The English a translator is working from, and the reviewer's reply about it, in a block that
 * scrolls.
 *
 * <p>{@link TranslationEditScreen} used to draw both by hand and cut them off at six lines and
 * four — which meant the longest strings in the mod, the book variants, could not be read at all
 * by the person being asked to translate them. Nothing is dropped now: the block grows to fit
 * (see {@link TranslationSourceLayout}) and scrolls past that.</p>
 *
 * <p>Same hand-rolled scrolling shape as {@link TranslationSubmissionList} and
 * {@link TranslationListWidget}, sharing their {@link ListScrollbar}. Read-only, so it takes no
 * keyboard focus — Tab from this screen belongs to the edit box, which is why the player opened
 * it.</p>
 */
public final class TranslationSourcePane extends AbstractWidget {

    private static final int GAP = 4;
    private static final int SCROLLBAR_W = ListScrollbar.WIDTH;
    private static final int SOURCE_COLOUR = 0xFFFFFFFF;
    /** The reviewer's reply — the explorer's own note colour, and the list widget's ● tag. */
    private static final int REPLY_COLOUR = 0xFFE8A33D;

    private final Font font;
    private final Component heading;
    private final int headingColour;
    private final List<FormattedCharSequence> sourceLines;
    private final Component replyBy;
    private final List<FormattedCharSequence> replyLines;
    private final ListScrollbar scrollbar = new ListScrollbar();
    private final int contentHeight;

    private int scroll;

    /**
     * Wrap the text for a pane {@code width} wide. Split from the constructor because the screen
     * has to know how tall the wrapped text wants to be before it can decide how tall to make the
     * pane — see {@link TranslationSourceLayout#viewportHeight}.
     */
    public static TranslationSourcePane wrap(Font font, int width, Component heading,
                                             int headingColour, String source,
                                             Component replyBy, String reply) {
        // Always wrap as though the bar were there. Wrapping to the full width when it is absent
        // would make the text reflow the moment scrolling appeared, which is a feedback loop.
        int textWidth = Math.max(8, width - SCROLLBAR_W - 2);
        return new TranslationSourcePane(font, width, heading, headingColour,
            font.split(FormattedText.of(source), textWidth),
            replyBy,
            reply == null || reply.isEmpty()
                ? List.<FormattedCharSequence>of()
                : font.split(FormattedText.of(reply), textWidth));
    }

    private TranslationSourcePane(Font font, int width, Component heading, int headingColour,
                                  List<FormattedCharSequence> sourceLines, Component replyBy,
                                  List<FormattedCharSequence> replyLines) {
        super(0, 0, width, font.lineHeight, heading);
        this.font = font;
        this.heading = heading;
        this.headingColour = headingColour;
        this.sourceLines = List.copyOf(sourceLines);
        this.replyBy = replyBy == null ? CommonComponents.EMPTY : replyBy;
        this.replyLines = List.copyOf(replyLines);
        this.contentHeight = TranslationSourceLayout.contentHeight(
            font.lineHeight, GAP, this.sourceLines.size(), this.replyLines.size());
    }

    /** How tall the wrapped text wants to be, before any cap is applied. */
    public int contentHeight() {
        return contentHeight;
    }

    /** Place the pane once the screen has worked out how much room it can spare. */
    public void place(int x, int y, int height) {
        setX(x);
        setY(y);
        setHeight(height);
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return TranslationSourceLayout.maxScroll(contentHeight, height);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        int y = getY() - scroll;
        g.drawString(font, heading, getX(), y, headingColour, false);
        y += font.lineHeight + GAP;
        for (FormattedCharSequence line : sourceLines) {
            g.drawString(font, line, getX(), y, SOURCE_COLOUR, false);
            y += font.lineHeight;
        }
        if (!replyLines.isEmpty()) {
            y += GAP;
            g.drawString(font, replyBy, getX(), y, REPLY_COLOUR, false);
            y += font.lineHeight;
            for (FormattedCharSequence line : replyLines) {
                g.drawString(font, line, getX(), y, SOURCE_COLOUR, false);
                y += font.lineHeight;
            }
        }
        g.disableScissor();
        scrollbar.render(g, getX(), getY(), width, height, contentHeight, scroll, maxScroll());
    }

    /**
     * Only the bar is clickable. There are no rows here to select, so a press anywhere else is
     * left to fall through to the screen rather than swallowed.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || !isMouseOver(mouseX, mouseY) || maxScroll() == 0
            || !scrollbar.isOverTrack(mouseX, getX(), width)) {
            return false;
        }
        scrollbar.begin();
        scroll = scrollbar.scrollFor(mouseY, getY(), height, contentHeight, maxScroll());
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
                                double dragY) {
        if (!scrollbar.isDragging()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        scroll = scrollbar.scrollFor(mouseY, getY(), height, contentHeight, maxScroll());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        scrollbar.end();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxScroll() == 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (scrollY * font.lineHeight * 3), 0, maxScroll());
        return true;
    }

    /** Nothing here to type into, so Tab passes straight over it to the edit box. */
    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return null;
    }

    /**
     * Read it out in full, however long it is. A narrator that stopped where the old six-line cap
     * did would reproduce the bug this pane exists to fix.
     */
    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        StringBuilder text = new StringBuilder(heading.getString());
        appendAll(text, sourceLines);
        if (!replyLines.isEmpty()) {
            text.append(". ").append(replyBy.getString());
            appendAll(text, replyLines);
        }
        output.add(NarratedElementType.TITLE, Component.literal(text.toString()));
    }

    private static void appendAll(StringBuilder text, List<FormattedCharSequence> lines) {
        for (FormattedCharSequence line : lines) {
            text.append(' ');
            line.accept((index, style, codePoint) -> {
                text.appendCodePoint(codePoint);
                return true;
            });
        }
    }
}
