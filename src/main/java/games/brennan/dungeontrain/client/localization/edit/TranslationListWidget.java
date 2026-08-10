package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.function.Consumer;

/**
 * The scrolling list of translation units.
 *
 * <p>A focused {@link AbstractWidget} with its own clipping and scrollbar, following
 * {@code ChatMessageList} — nothing in this codebase subclasses {@code ObjectSelectionList}, and
 * a fixed-height row list over a few thousand entries wants virtualised rendering anyway (only
 * the visible rows are laid out, so the list costs the same whether it holds 20 units or 2000).
 * </p>
 *
 * <p>Three lines per row, in the order a translator reads them: the key (what to look up), the
 * English (what it means), and the current translation (what to fix). Anything shorter forces
 * the translator into the edit screen just to find out which string a row is.</p>
 */
public final class TranslationListWidget extends AbstractWidget {

    private static final int PAD = 4;
    private static final int SCROLLBAR_W = 3;
    private static final int ROW_LINES = 3;

    private static final int BG = 0x66000000;
    private static final int ROW_HOVER = 0x33FFFFFF;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int SCROLLBAR = 0x80AAB0BE;
    private static final int KEY_COLOUR = 0xFF7F7F7F;
    private static final int SOURCE_COLOUR = 0xFFFFFFFF;
    private static final int SHIPPED_COLOUR = 0xFFA0A0A0;
    /** Green: this player has an override on the row. */
    private static final int EDITED_COLOUR = 0xFF7FDD7F;
    /** The same blue the language list's AI-fraction ring uses. */
    private static final int AI_COLOUR = 0xFF5B9BD5;
    private static final String AI_TAG = "AI";

    private final Font font;
    private final Consumer<TranslationUnit> onSelect;

    private List<TranslationUnit> units = List.of();
    /** The overrides for the locale being EDITED, which on a dev build is not the one displayed. */
    private TranslationEdits edits = TranslationEdits.empty("");
    /** Just the relay-approved slice of the above — what the AI badge is decided against. */
    private TranslationEdits approved = TranslationEdits.empty("");
    private int scroll;

    public TranslationListWidget(Font font, int x, int y, int width, int height,
                                 Consumer<TranslationUnit> onSelect) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.translate.list"));
        this.font = font;
        this.onSelect = onSelect;
    }

    /** The override layer rows render against — set before {@link #setUnits} on every refresh. */
    public void setEdits(TranslationEdits newEdits) {
        this.edits = newEdits == null ? TranslationEdits.empty("") : newEdits;
    }

    /**
     * The relay-approved layer, which decides the AI badge — a string an operator has released is
     * no longer machine translation nobody has read, whatever the jar's provenance said at build
     * time. Set alongside {@link #setEdits}.
     */
    public void setApproved(TranslationEdits newApproved) {
        this.approved = newApproved == null ? TranslationEdits.empty("") : newApproved;
    }

    /** Replace the visible rows, keeping the scroll position where it still makes sense. */
    public void setUnits(List<TranslationUnit> newUnits) {
        this.units = newUnits == null ? List.of() : newUnits;
        this.scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    public int rowCount() {
        return units.size();
    }

    private int rowHeight() {
        return font.lineHeight * ROW_LINES + PAD * 2;
    }

    private int totalHeight() {
        return units.size() * rowHeight();
    }

    private int maxScroll() {
        return Math.max(0, totalHeight() - height);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(getX(), getY(), getX() + width, getY() + height, BG);
        if (units.isEmpty()) {
            Component empty = Component.translatable("gui.dungeontrain.translate.empty");
            g.drawCenteredString(font, empty, getX() + width / 2,
                getY() + height / 2 - font.lineHeight / 2, SHIPPED_COLOUR);
            return;
        }

        int rowH = rowHeight();
        int textWidth = width - PAD * 2 - SCROLLBAR_W - 2;
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        // Only the rows intersecting the viewport are laid out — the list can hold every key in
        // every namespace plus every book field without the render cost tracking that.
        int first = Math.max(0, scroll / rowH);
        int last = Math.min(units.size() - 1, (scroll + height) / rowH);
        for (int i = first; i <= last; i++) {
            int rowY = getY() + i * rowH - scroll;
            renderRow(g, units.get(i), i, rowY, rowH, textWidth, mouseX, mouseY);
        }
        g.disableScissor();
        renderScrollbar(g);
    }

    private void renderRow(GuiGraphics g, TranslationUnit unit, int index, int rowY, int rowH,
                           int textWidth, int mouseX, int mouseY) {
        boolean hovered = isMouseOver(mouseX, mouseY)
            && mouseY >= rowY && mouseY < rowY + rowH;
        if (hovered) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_HOVER);
        } else if ((index & 1) == 1) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_ALT);
        }

        String override = unit.type() == TranslationUnit.Type.BOOK
            ? edits.books().get(unit.id()) : edits.lang().get(unit.id());
        boolean edited = override != null;

        int textX = getX() + PAD;
        int lineY = rowY + PAD;

        // Line 1: the key, plus the AI badge right-aligned so the eye can scan a column of them.
        g.drawString(font, font.plainSubstrByWidth(unit.label(), textWidth - 16),
            textX, lineY, KEY_COLOUR, false);
        if (TranslationFilters.needsHuman(unit, approved)) {
            int tagX = getX() + width - SCROLLBAR_W - 3 - font.width(AI_TAG);
            g.drawString(font, AI_TAG, tagX, lineY, AI_COLOUR, false);
        }
        lineY += font.lineHeight;

        // Line 2: the English source (or a note when the mod ships none to compare against).
        String source = unit.source().isEmpty()
            ? Component.translatable("gui.dungeontrain.translate.no_source").getString()
            : unit.source();
        g.drawString(font, oneLine(source, textWidth), textX, lineY,
            unit.source().isEmpty() ? SHIPPED_COLOUR : SOURCE_COLOUR, false);
        lineY += font.lineHeight;

        // Line 3: what the player currently sees — their override if they have one.
        String current = edited ? override : unit.shipped();
        g.drawString(font, oneLine(current, textWidth), textX, lineY,
            edited ? EDITED_COLOUR : SHIPPED_COLOUR, false);
    }

    /** Collapse newlines so a multi-paragraph book variant still occupies exactly one line. */
    private String oneLine(String text, int maxWidth) {
        return font.plainSubstrByWidth(text.replace('\n', ' ').replace('\r', ' '), maxWidth);
    }

    private void renderScrollbar(GuiGraphics g) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int trackX = getX() + width - SCROLLBAR_W - 1;
        int thumbH = Math.max(12, (int) ((long) height * height / totalHeight()));
        int thumbY = getY() + (int) ((long) (height - thumbH) * scroll / max);
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, SCROLLBAR);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0 || !isMouseOver(mouseX, mouseY) || units.isEmpty()) {
            return false;
        }
        int index = (int) ((mouseY - getY() + scroll) / rowHeight());
        if (index < 0 || index >= units.size()) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        onSelect.accept(units.get(index));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxScroll() == 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (scrollY * font.lineHeight * 3), 0, maxScroll());
        return true;
    }

    /** Scroll so {@code index} is visible — used when jumping to the next unreviewed unit. */
    public void scrollTo(int index) {
        if (index < 0 || index >= units.size()) {
            return;
        }
        scroll = Mth.clamp(index * rowHeight() - height / 2, 0, maxScroll());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
            Component.translatable("gui.dungeontrain.translate.list.narration", units.size()));
    }
}
