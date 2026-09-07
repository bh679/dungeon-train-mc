package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.ui.ListScrollbar;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.function.Consumer;

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
    private static final int SCROLLBAR_W = ListScrollbar.WIDTH;
    private static final int ROW_LINES = 2;

    private static final int BG = 0x66000000;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int DATE_COLOUR = 0xFFFFFFFF;
    private static final int CREDIT_COLOUR = 0xFFA0A0A0;

    private static final int ROW_SELECTED = 0x55FFFFFF;
    private static final int ROW_HOVER = 0x33FFFFFF;

    private final Font font;
    /**
     * Null when rows are read-only; set when picking a submission opens its contents. Called with
     * {@code null} when the player clicks the selected row again to put it back down.
     */
    private final Consumer<TranslationSubmission> onSelect;
    private final ListScrollbar scrollbar = new ListScrollbar();

    private List<TranslationSubmission> rows = List.of();
    private int scroll;
    private boolean loading = true;
    private int selected = -1;

    public TranslationSubmissionList(Font font, int x, int y, int width, int height) {
        this(font, x, y, width, height, null);
    }

    public TranslationSubmissionList(Font font, int x, int y, int width, int height,
                                     Consumer<TranslationSubmission> onSelect) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.translate.sent.title"));
        this.font = font;
        this.onSelect = onSelect;
    }

    /** Replace the rows. Ends the loading state even when the list comes back empty. */
    public void setRows(List<TranslationSubmission> newRows) {
        this.rows = newRows == null ? List.of() : newRows;
        this.loading = false;
        this.selected = -1;
        this.scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    /**
     * Select the first row and report it — the screen opens on the working batch, which is always
     * row 0, so the column always has a current context to hand the left pane.
     */
    public void selectFirst() {
        if (onSelect != null && !rows.isEmpty()) {
            selected = 0;
            onSelect.accept(rows.get(0));
        }
    }

    /**
     * Grow or shrink to make room for something below — the Submit button, which only exists while
     * the working batch is picked. Re-clamps the scroll, since a taller list can leave it past the
     * end and a shorter one can leave a gap under the last row.
     */
    public void resizeTo(int newHeight) {
        if (newHeight == height) {
            return;
        }
        setHeight(Math.max(rowHeight(), newHeight));
        scroll = Mth.clamp(scroll, 0, maxScroll());
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
            renderRow(g, rows.get(i), i, getY() + i * rowH - scroll, rowH, textWidth, mouseX, mouseY);
        }
        g.disableScissor();
        scrollbar.render(g, getX(), getY(), width, height, totalHeight(), scroll, maxScroll());
    }

    private void renderRow(GuiGraphics g, TranslationSubmission row, int index, int rowY, int rowH,
                           int textWidth, int mouseX, int mouseY) {
        boolean hovered = onSelect != null && isMouseOver(mouseX, mouseY)
            && mouseY >= rowY && mouseY < rowY + rowH;
        if (index == selected) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_SELECTED);
        } else if (hovered) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_HOVER);
        } else if ((index & 1) == 1) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_ALT);
        }
        int textX = getX() + PAD;
        int lineY = rowY + PAD;

        // Line 1: when, and how much — the two facts that identify a submission at a glance. The
        // working batch has no date (it has not been sent), so it leads with the count alone rather
        // than a dangling separator.
        String count = Component.translatable(
            "gui.dungeontrain.translate.sent.units", row.units()).getString();
        String head = row.date().isEmpty() ? count : row.date() + "  ·  " + count;
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (onSelect == null || !visible || !active || button != 0
            || !isMouseOver(mouseX, mouseY) || rows.isEmpty()) {
            return false;
        }
        // The bar first, and only where there is one: a press on the track is aimed at the
        // scrollbar, not at the row it happens to be drawn over.
        if (maxScroll() > 0 && scrollbar.isOverTrack(mouseX, getX(), width)) {
            scrollbar.begin();
            scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
            return true;
        }
        int index = (int) ((mouseY - getY() + scroll) / rowHeight());
        if (index < 0 || index >= rows.size()) {
            return false;
        }
        playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
        // Clicking the row you are already on puts it down again. Without this there is no way back
        // out of a submission to the work still to do — the column is the navigation now, so it has
        // to be able to say "nothing", not just "something else".
        if (index == selected) {
            selected = -1;
            onSelect.accept(null);
            return true;
        }
        selected = index;
        onSelect.accept(rows.get(index));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
                                double dragY) {
        if (!scrollbar.isDragging()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
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

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
            Component.translatable("gui.dungeontrain.translate.sent.narration", rows.size()));
    }
}
