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
    // The hack-report side of the palette: the same three roles as the green ones, in warning red,
    // so a flagged tile reads as the opposite of a recommended one at a glance.
    private static final int REP_BG       = 0xFF5A2A26;
    private static final int REP_BORDER   = 0xFF8E4238;
    private static final int REP_TEXT     = 0xFFFFFFFF;
    private static final int FLAGGED_BORDER = 0xFF5A302A;
    private static final int FLAGGED_TEXT = 0xFFC08A80;
    private static final int ICON_IDLE    = 0xFF6E6455;
    private static final int ICON_HOVER   = 0xFFD4614E;
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

    /** The ⚠ affordance drawn in a tile's right edge, and the click target reserved for it. */
    private static final String HACK_GLYPH = "\u26A0";
    private static final int ICON_W = 10;

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
    private final List<Hit> hackHits = new ArrayList<>();
    private Hit sendButton;
    /** Tooltip queued while the grid is scissored, drawn once the clip is off. */
    private Component pendingTooltip;
    private int tooltipX, tooltipY;

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
        hackHits.clear();
        pendingTooltip = null;
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

            boolean isAskTile = i == tiles.size();
            ModRecState.Tile tile = isAskTile ? null : tiles.get(i);
            String modId = isAskTile ? ModRecState.REQUEST_ID : tile.modId();
            String label = isAskTile
                    ? Component.translatable("gui.dungeontrain.death.modrec.not_installed").getString()
                    : tile.displayName();
            boolean sent = !isAskTile && tile.sent();
            boolean flagged = !isAskTile && tile.reported();
            // A sent request is a receipt, not a control: there is nothing to change about a mod the
            // player doesn't have, so it draws but registers no hit and can't be selected.
            boolean clickable = isAskTile || !tile.request();
            boolean selected = clickable && state.isSelected(modId);
            boolean reporting = selected && state.isReportingHack();
            boolean inside = mouseX >= tx && mouseX < tx + cellW
                    && mouseY >= ty && mouseY < ty + TILE_H
                    && mouseY >= gridTop && mouseY < gridBottom;
            // Only a real, installed mod can be flagged: the "not installed" tile names a mod the
            // player doesn't have, and a sent request is a receipt rather than a control.
            boolean hackable = !isAskTile && clickable;
            int iconX = tx + cellW - ICON_W - 2;
            boolean iconHover = hackable && inside && mouseX >= iconX;
            boolean hover = clickable && inside && !iconHover;

            drawTile(g, tx, ty, cellW, label, sent, selected, hover, isAskTile || !clickable,
                    flagged, reporting, hackable, iconHover);
            if (clickable) hits.add(new Hit(modId, tx, ty, cellW, TILE_H));
            if (hackable) {
                hackHits.add(new Hit(modId, iconX, ty, ICON_W + 2, TILE_H));
                if (iconHover) {
                    pendingTooltip = Component.translatable("gui.dungeontrain.death.modrec.hack_hint");
                    tooltipX = mouseX;
                    tooltipY = mouseY;
                }
            }
        }
        g.disableScissor();
        // Drawn after the clip so a tooltip on the top or bottom row isn't sliced by the viewport.
        if (pendingTooltip != null) {
            g.renderTooltip(font, pendingTooltip, tooltipX, tooltipY);
        }

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

            Component label = Component.translatable(state.isReportingHack()
                    ? "gui.dungeontrain.death.modrec.report"
                    : "gui.dungeontrain.death.modrec.send");
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
                          boolean sent, boolean selected, boolean hover, boolean muted,
                          boolean flagged, boolean reporting, boolean hackable, boolean iconHover) {
        int bg = reporting ? REP_BG : (selected ? SEL_BG : TILE_BG);
        int border = reporting ? REP_BORDER
                : selected ? SEL_BORDER
                : flagged ? FLAGGED_BORDER
                : sent ? SENT_BORDER
                : hover ? TILE_HOVER : TILE_BORDER;
        int text = reporting ? REP_TEXT
                : selected ? SEL_TEXT
                : flagged ? FLAGGED_TEXT
                : sent ? SENT_TEXT
                : muted ? ASK_TEXT : TILE_TEXT;

        g.fill(x, y, x + w, y + TILE_H, fade.applyAsInt(bg));
        drawBorder(g, x, y, w, TILE_H, border);

        int textY = y + (TILE_H - font.lineHeight) / 2 + 1;
        if (hackable) {
            g.drawString(font, HACK_GLYPH, x + w - ICON_W, textY,
                    fade.applyAsInt(iconHover || reporting ? ICON_HOVER : ICON_IDLE), false);
        }

        // A flagged tile keeps a ⚠ where a recommended one keeps its ✓ — same receipt, opposite sign.
        String shown = flagged ? HACK_GLYPH + " " + label : (sent ? "✓ " + label : label);
        shown = trim(shown, w - 8 - (hackable ? ICON_W : 0));
        g.drawString(font, shown, x + 4, textY, fade.applyAsInt(text), false);
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

    /**
     * The tile whose ⚠ icon is under the cursor, or null. Must be tested before {@link #tileAt},
     * since the icon sits inside its tile's rect.
     */
    public String hackAt(double mx, double my) {
        if (my < gridTop || my >= gridBottom) return null;
        for (Hit h : hackHits) {
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
