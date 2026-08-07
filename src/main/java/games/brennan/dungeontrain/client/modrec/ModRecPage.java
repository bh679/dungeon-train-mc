package games.brennan.dungeontrain.client.modrec;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Draws and hit-tests the death screen's Mod Recommendations page: a scrollable grid of the
 * player's non-Dungeon-Train mods, ordered by {@link ModRecState}, plus the pinned "something not
 * installed…" request tile.
 *
 * <p>Lives outside {@link games.brennan.dungeontrain.client.NarrativeDeathScreen} to keep an
 * already-large screen from growing another page's worth of layout. It borrows only what it needs
 * from its host — the font and the transition's alpha ramp ({@code fade}) — and owns its own
 * scroll offset and rectangles, so the screen's contribution is a call to {@link #draw} and four
 * input forwards.</p>
 *
 * <p>The grid clips to a viewport rather than growing the page, so the layout is stable whether
 * the player runs two extra mods or forty; when a tile is selected the viewport shrinks to make
 * room for the comment box and the Send row rather than the page scrolling as a whole.</p>
 */
public final class ModRecPage {

    // Palette — the same values the host screen uses for its survey tiles and buttons.
    private static final int TILE_BG      = 0xFF1A1813;
    private static final int TILE_BORDER  = 0xFF4A443C;
    private static final int TILE_TEXT    = 0xFFCDBB95;
    private static final int TILE_HOVER   = 0xFF6E6455;
    private static final int SEL_BG       = 0xFF3C6B41;
    private static final int SEL_BORDER   = 0xFF5C9162;
    private static final int SEL_TEXT     = 0xFFFFFFFF;
    private static final int SENT_BORDER  = 0xFF34503A;
    private static final int SENT_TEXT    = 0xFF7FAE84;
    private static final int ASK_TEXT     = 0xFF948A70;
    private static final int SUBLINE      = 0xFF948A70;
    private static final int SCROLLBAR_BG = 0xFF1C1D21;
    private static final int SCROLLBAR_FG = 0xFF4A443C;
    private static final int BTN_ON_BG    = 0xFF3C6B41;
    private static final int BTN_ON_LIGHT = 0xFF5C9162;
    private static final int BTN_OFF_BG   = 0xFF2A2B2D;
    private static final int BTN_OFF_TEXT = 0xFF6A6C70;
    private static final int BTN_DARK     = 0xFF1E1F21;
    private static final int BTN_TEXT     = 0xFFEAEAEA;

    private static final int COLS = 3;
    private static final int GAP = 4;
    private static final int TILE_H = 22;
    private static final int SCROLL_STEP = TILE_H + GAP;

    /** A drawn tile and where it landed, for hit-testing. {@code modId} may be {@link ModRecState#REQUEST_ID}. */
    public record Hit(String modId, int x, int y, int w, int h) {
        public boolean has(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Font font;
    private final IntUnaryOperator fade;

    private int scrollY = 0;
    private int maxScroll = 0;
    private int gridTop, gridBottom, gridLeft, gridRight;
    private final List<Hit> hits = new ArrayList<>();
    private Hit sendButton;

    public ModRecPage(Font font, IntUnaryOperator fade) {
        this.font = font;
        this.fade = fade;
    }

    /** Where the comment box should sit this frame, or null when nothing is selected. */
    private int commentBoxY = -1;
    private int commentBoxX = -1;
    private int commentBoxW = 0;
    /** Where the requested-name box should sit, or -1 when the request tile isn't selected. */
    private int nameBoxY = -1;

    public int commentBoxX() { return commentBoxX; }
    public int commentBoxY() { return commentBoxY; }
    public int commentBoxW() { return commentBoxW; }
    public int nameBoxY() { return nameBoxY; }

    /**
     * Draw the grid and the input row. Returns the y below everything drawn.
     *
     * @param footerTop y of the screen's button row — the grid never grows past it
     */
    public int draw(GuiGraphics g, ModRecState state, int left, int w, int cx, int y,
                    int footerTop, int mouseX, int mouseY) {
        hits.clear();
        sendButton = null;
        commentBoxY = -1;
        nameBoxY = -1;

        List<ModRecState.Tile> tiles = state.tiles();
        boolean selecting = state.selected() != null;
        boolean requesting = state.isRequesting();

        // Reserve the input row's height up-front so selecting a tile shrinks the grid instead of
        // pushing the comment box down over the footer.
        int reserved = 8;
        if (selecting) {
            reserved += 16 + 6;                  // comment box
            reserved += 18 + 6;                  // send row
            if (requesting) reserved += 16 + 4;  // requested-name box
        }

        gridLeft = left;
        gridRight = left + w;
        gridTop = y;
        gridBottom = Math.max(gridTop + TILE_H, footerTop - 10 - reserved);

        int cellW = (w - (COLS - 1) * GAP) / COLS;
        int count = tiles.size() + 1;                       // + the pinned request tile
        int rows = (count + COLS - 1) / COLS;
        int totalH = rows * TILE_H + Math.max(0, rows - 1) * GAP;
        int viewportH = Math.max(0, gridBottom - gridTop);
        maxScroll = Math.max(0, totalH - viewportH);
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        g.enableScissor(gridLeft, gridTop, gridRight, gridBottom);
        for (int i = 0; i < count; i++) {
            int col = i % COLS, row = i / COLS;
            int tx = left + col * (cellW + GAP);
            int ty = gridTop + row * (TILE_H + GAP) - scrollY;
            if (ty + TILE_H < gridTop || ty > gridBottom) continue;   // fully scrolled out

            boolean isRequestTile = i == tiles.size();
            String modId = isRequestTile ? ModRecState.REQUEST_ID : tiles.get(i).modId();
            String label = isRequestTile
                    ? Component.translatable("gui.dungeontrain.death.modrec.not_installed").getString()
                    : tiles.get(i).displayName();
            boolean sent = !isRequestTile && tiles.get(i).sent();
            boolean selected = state.isSelected(modId);
            boolean hover = mouseX >= tx && mouseX < tx + cellW && mouseY >= ty && mouseY < ty + TILE_H
                    && mouseY >= gridTop && mouseY < gridBottom;

            drawTile(g, tx, ty, cellW, label, sent, selected, hover, isRequestTile);
            hits.add(new Hit(modId, tx, ty, cellW, TILE_H));
        }
        g.disableScissor();

        if (maxScroll > 0) {
            int barX = gridRight + 2;
            g.fill(barX, gridTop, barX + 2, gridBottom, fade.applyAsInt(SCROLLBAR_BG));
            int thumbH = Math.max(8, viewportH * viewportH / Math.max(1, totalH));
            int thumbY = gridTop + (viewportH - thumbH) * scrollY / maxScroll;
            g.fill(barX, thumbY, barX + 2, thumbY + thumbH, fade.applyAsInt(SCROLLBAR_FG));
        }

        int below = gridBottom + 6;
        if (selecting) {
            commentBoxW = Math.min(w, 256);
            commentBoxX = cx - commentBoxW / 2;
            if (requesting) {
                nameBoxY = below;
                below += 16 + 4;
            }
            commentBoxY = below;
            below += 16 + 6;

            Component label = Component.translatable("gui.dungeontrain.death.modrec.send");
            int bw = Math.max(60, font.width(label) + 24), bh = 18;
            int bx = cx - bw / 2;
            boolean live = state.canSend();
            drawBevel(g, bx, below, bw, bh, label,
                    live ? BTN_ON_BG : BTN_OFF_BG,
                    live ? BTN_ON_LIGHT : BTN_OFF_BG,
                    BTN_DARK,
                    live ? BTN_TEXT : BTN_OFF_TEXT);
            if (live) sendButton = new Hit("", bx, below, bw, bh);
            below += bh + 4;
        } else {
            String key = state.sentCount() > 0
                    ? "gui.dungeontrain.death.modrec.again"
                    : "gui.dungeontrain.death.modrec.hint";
            drawCentered(g, Component.translatable(key), cx, below, w, SUBLINE);
            below += font.lineHeight + 4;
        }
        return below;
    }

    private void drawTile(GuiGraphics g, int x, int y, int w, String label,
                          boolean sent, boolean selected, boolean hover, boolean request) {
        int bg = selected ? SEL_BG : TILE_BG;
        int border = selected ? SEL_BORDER : (sent ? SENT_BORDER : (hover ? TILE_HOVER : TILE_BORDER));
        int text = selected ? SEL_TEXT : (sent ? SENT_TEXT : (request ? ASK_TEXT : TILE_TEXT));

        g.fill(x, y, x + w, y + TILE_H, fade.applyAsInt(bg));
        drawBorder(g, x, y, w, TILE_H, border);

        String shown = sent ? "✓ " + label : label;
        shown = trim(shown, w - 8);
        g.drawString(font, shown, x + 4, y + (TILE_H - font.lineHeight) / 2 + 1,
                fade.applyAsInt(text), false);
    }

    /** Shorten to fit {@code maxW}, ending in "…" — mod names are long and the tiles are narrow. */
    private String trim(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String ellipsis = "…";
        int budget = maxW - font.width(ellipsis);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (font.width(b.toString() + s.charAt(i)) > budget) break;
            b.append(s.charAt(i));
        }
        return b + ellipsis;
    }

    // ---- input ----

    /** The tile under the cursor, or null. Ignores clicks outside the clipped viewport. */
    public String tileAt(double mx, double my) {
        if (my < gridTop || my >= gridBottom) return null;
        for (Hit h : hits) {
            if (h.has(mx, my)) return h.modId();
        }
        return null;
    }

    /** Whether the (live) Send button is under the cursor. A disabled Send has no rect at all. */
    public boolean sendAt(double mx, double my) {
        return sendButton != null && sendButton.has(mx, my);
    }

    /** Wheel over the grid scrolls it. Returns true when the scroll was consumed. */
    public boolean scroll(double mx, double my, double dy) {
        if (maxScroll <= 0) return false;
        if (mx < gridLeft || mx >= gridRight + 4 || my < gridTop || my >= gridBottom) return false;
        scrollY = Mth.clamp(scrollY - (int) Math.round(dy) * SCROLL_STEP, 0, maxScroll);
        return true;
    }

    /** Reset the scroll offset — called when the page is (re)entered. */
    public void resetScroll() {
        scrollY = 0;
    }

    // ---- local drawing primitives (mirrors of the host screen's, so this class stands alone) ----

    private void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c = fade.applyAsInt(color);
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private void drawBevel(GuiGraphics g, int x, int y, int w, int h, Component text,
                           int bg, int light, int dark, int textColor) {
        g.fill(x, y, x + w, y + h, fade.applyAsInt(bg));
        g.fill(x, y, x + w, y + 2, fade.applyAsInt(light));
        g.fill(x, y, x + 2, y + h, fade.applyAsInt(light));
        g.fill(x, y + h - 2, x + w, y + h, fade.applyAsInt(dark));
        g.fill(x + w - 2, y, x + w, y + h, fade.applyAsInt(dark));
        g.drawString(font, text, x + w / 2 - font.width(text) / 2,
                y + (h - font.lineHeight) / 2 + 1, fade.applyAsInt(textColor), false);
    }

    private void drawCentered(GuiGraphics g, Component text, int cx, int y, int w, int color) {
        int faded = fade.applyAsInt(color);
        for (var line : font.split(text, w - 8)) {
            g.drawString(font, line, cx - font.width(line) / 2, y, faded, false);
            y += font.lineHeight + 2;
        }
    }
}
