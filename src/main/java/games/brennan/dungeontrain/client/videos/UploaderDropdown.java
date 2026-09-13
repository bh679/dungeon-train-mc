package games.brennan.dungeontrain.client.videos;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * The suggestion list under the Videos page's uploader box: every uploader when the box is empty,
 * narrowing as you type, one click to pick. Drawn by the screen <i>after</i> its widgets so it sits
 * over the video list, and asked about clicks <i>before</i> them so it gets first refusal — which is
 * why it is a plain helper the screen drives rather than a widget in the children list, where render
 * order and click order are the same order.
 *
 * <p>Owns nothing but geometry and scroll; the rows are handed in each frame so a catalogue that
 * loads while the box is focused shows up without a re-open.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class UploaderDropdown {

    private static final int MAX_ROWS = 8;
    private static final int PAD = 3;
    private static final int BG = 0xF0101010;
    private static final int BORDER = 0xFF5A5A5A;
    private static final int ROW_HOVER = 0x40FFFFFF;
    private static final int TEXT = 0xFFE0E0E0;
    /** Same height vanilla lifts tooltips to — above every widget on the screen. */
    private static final float Z = 400.0F;

    private final Font font;
    private final Consumer<String> onPick;

    private int x;
    private int y;
    private int width;
    private List<String> rows = List.of();
    private int scroll;
    private boolean open;

    public UploaderDropdown(Font font, Consumer<String> onPick) {
        this.font = font;
        this.onPick = onPick;
    }

    /** Anchor the list's top-left and width — directly under the box it completes. */
    public void place(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    public void setOpen(boolean open) {
        if (this.open != open) scroll = 0;
        this.open = open;
    }

    public boolean isOpen() {
        return open && !rows.isEmpty();
    }

    /** Replace the suggestions (already filtered to the typed text). */
    public void setRows(List<String> rows) {
        this.rows = rows == null ? List.of() : rows;
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int rowHeight() {
        return font.lineHeight + PAD * 2;
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS, rows.size());
    }

    private int height() {
        return visibleRows() * rowHeight() + 2;
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - MAX_ROWS);
    }

    public boolean isMouseOver(double mx, double my) {
        return isOpen() && mx >= x && mx < x + width && my >= y && my < y + height();
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (!isOpen()) return;
        // Lifted the way tooltips are: the list's text sits a fraction above z=0 (the font renderer
        // offsets glyphs from their shadow), so a same-depth fill fails the depth test against it and
        // the titles print straight through the panel. Drawing everything here at tooltip height
        // puts the panel — and its own text — over whatever the list drew.
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, Z);
        try {
            draw(g, mouseX, mouseY);
        } finally {
            g.pose().popPose();
        }
    }

    private void draw(GuiGraphics g, int mouseX, int mouseY) {
        int h = height();
        g.fill(x, y, x + width, y + h, BG);
        g.fill(x, y, x + width, y + 1, BORDER);
        g.fill(x, y + h - 1, x + width, y + h, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + width - 1, y, x + width, y + h, BORDER);
        int rowH = rowHeight();
        for (int i = 0; i < visibleRows(); i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) break;
            int rowY = y + 1 + i * rowH;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowH;
            if (hovered) g.fill(x + 1, rowY, x + width - 1, rowY + rowH, ROW_HOVER);
            g.drawString(font, font.plainSubstrByWidth(rows.get(idx), width - 2 * PAD - 2),
                    x + 1 + PAD, rowY + PAD, TEXT);
        }
        // More below / above than fits: a one-pixel cue at the edge, since there is no scrollbar here.
        if (scroll + visibleRows() < rows.size()) g.fill(x + width / 2 - 3, y + h - 2, x + width / 2 + 3, y + h - 1, TEXT);
        if (scroll > 0) g.fill(x + width / 2 - 3, y + 1, x + width / 2 + 3, y + 2, TEXT);
    }

    /** True when the click landed on a row (and was acted on) or anywhere else inside the list. */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isMouseOver(mx, my)) return false;
        if (button != 0) return true;
        int idx = scroll + (int) ((my - y - 1) / rowHeight());
        if (idx >= 0 && idx < rows.size()) {
            onPick.accept(rows.get(idx));
        }
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!isMouseOver(mx, my) || maxScroll() == 0) return false;
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
        return true;
    }
}
