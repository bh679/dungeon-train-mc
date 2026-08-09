package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The "what you have sent in" panel: one row per submission, newest first.
 *
 * <p>Answers the question a translator actually has after pressing Submit — did it land? Each row
 * carries the date, how many strings went, who it credited, and where it got to. Sorted newest
 * first because the one you just sent is the one you came to look at.</p>
 *
 * <p>Same hand-rolled scrolling shape as {@link TranslationListWidget}, for the same reason:
 * nothing in this codebase subclasses {@code ObjectSelectionList}, and rows here are read-only,
 * so there is nothing a vanilla list would add.</p>
 */
public final class TranslationSubmissionList extends AbstractWidget {

    private static final int PAD = 4;
    private static final int SCROLLBAR_W = 3;
    private static final int ROW_LINES = 2;

    private static final int BG = 0x66000000;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int SCROLLBAR = 0x80AAB0BE;
    private static final int DATE_COLOUR = 0xFFFFFFFF;
    private static final int CREDIT_COLOUR = 0xFFA0A0A0;

    private final Font font;
    private List<TranslationSubmission> rows = List.of();
    private int scroll;
    private boolean loading = true;

    public TranslationSubmissionList(Font font, int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.translate.sent.title"));
        this.font = font;
    }

    /** Replace the rows. Ends the loading state even when the list comes back empty. */
    public void setRows(List<TranslationSubmission> newRows) {
        this.rows = newRows == null ? List.of() : newRows;
        this.loading = false;
        this.scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int rowHeight() {
        return font.lineHeight * ROW_LINES + PAD * 2;
    }

    private int totalHeight() {
        return rows.size() * rowHeight();
    }

    private int maxScroll() {
        return Math.max(0, totalHeight() - height);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(getX(), getY(), getX() + width, getY() + height, BG);
        if (rows.isEmpty()) {
            Component empty = loading
                ? Component.translatable("gui.dungeontrain.translate.sent.loading")
                : Component.translatable("gui.dungeontrain.translate.sent.empty");
            for (var line : font.split(empty, width - PAD * 2)) {
                g.drawCenteredString(font, line, getX() + width / 2,
                    getY() + height / 2 - font.lineHeight / 2, CREDIT_COLOUR);
                break; // one line is enough for either message; the panel is narrow
            }
            return;
        }

        int rowH = rowHeight();
        int textWidth = width - PAD * 2 - SCROLLBAR_W - 2;
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        int first = Math.max(0, scroll / rowH);
        int last = Math.min(rows.size() - 1, (scroll + height) / rowH);
        for (int i = first; i <= last; i++) {
            renderRow(g, rows.get(i), i, getY() + i * rowH - scroll, rowH, textWidth);
        }
        g.disableScissor();
        renderScrollbar(g);
    }

    private void renderRow(GuiGraphics g, TranslationSubmission row, int index, int rowY, int rowH,
                           int textWidth) {
        if ((index & 1) == 1) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_ALT);
        }
        int textX = getX() + PAD;
        int lineY = rowY + PAD;

        // Line 1: when, and how much — the two facts that identify a submission at a glance.
        String head = row.date() + "  ·  "
            + Component.translatable("gui.dungeontrain.translate.sent.units", row.units()).getString();
        g.drawString(font, font.plainSubstrByWidth(head, textWidth), textX, lineY, DATE_COLOUR, false);
        lineY += font.lineHeight;

        // Line 2: the status, right-aligned so a column of them scans, and who it credited.
        Component status = row.statusLine().copy().withStyle(row.statusColour());
        int statusW = font.width(status);
        g.drawString(font, status, getX() + width - SCROLLBAR_W - 3 - statusW, lineY, 0xFFFFFFFF, false);
        String credit = font.plainSubstrByWidth(row.creditLine().getString(),
            Math.max(8, textWidth - statusW - 6));
        g.drawString(font, credit, textX, lineY, CREDIT_COLOUR, false);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxScroll() == 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (scrollY * font.lineHeight * 3), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
            Component.translatable("gui.dungeontrain.translate.sent.narration", rows.size()));
    }
}
