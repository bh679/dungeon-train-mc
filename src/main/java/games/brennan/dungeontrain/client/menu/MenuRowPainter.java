package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws and hit-tests one menu row — the cell painter every screen-space panel shares.
 *
 * <p>Extracted from {@link CommandMenuGuiScreen} so the inventory-style editor screen can draw the
 * same {@link CommandMenuEntry} rows in its own panes without a second copy of the colours, the
 * split/triple/quad decomposition, or the typing-field treatment. Everything here is stateless and
 * takes its inputs explicitly: which row is hovered, and whether a typing field is open on it.</p>
 *
 * <p>The renderer and the hit-test read the same {@link #cellsOf} / {@link #cellBoundaries}, so
 * what is drawn and what a click lands on cannot disagree.</p>
 */
public final class MenuRowPainter {

    // Colours carried over from the world-space renderer so the menu reads the same everywhere.
    public static final int CELL_IDLE     = 0x30FFFFFF;
    public static final int CELL_HOVER    = 0xB0FFCC33;
    public static final int TOGGLE_ON     = 0x8040AA40;
    public static final int TOGGLE_OFF    = 0x40FFFFFF;
    public static final int SAVED_GREY    = 0x40808080;
    public static final int HIGHLIGHT     = 0x80FFAA33;
    public static final int TYPING_BG     = 0xB033FF99;
    public static final int TEXT_NORMAL   = 0xFFFFFFFF;
    public static final int TEXT_ON_HOVER = 0xFF000000;
    public static final int TEXT_HEADER   = 0xFFFFEEBB;

    /** Horizontal padding inside a cell before its label starts. */
    public static final int CELL_PAD_X = 2;

    /** An open typing field: which cell it sits on and what has been typed so far. */
    public record Typing(int rowIdx, int subIdx, String buffer) {
        public boolean at(int row, int sub) {
            return rowIdx == row && subIdx == sub;
        }
    }

    private MenuRowPainter() {}

    // ------------------------------------------------------------------
    // Row decomposition
    // ------------------------------------------------------------------

    /** The cells a row splits into, left to right; a single-cell row is itself. */
    public static CommandMenuEntry[] cellsOf(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Split s) {
            return new CommandMenuEntry[] { s.leftEntry(), s.rightEntry() };
        }
        if (entry instanceof CommandMenuEntry.Triple t) {
            return new CommandMenuEntry[] { t.leftEntry(), t.middleEntry(), t.rightEntry() };
        }
        if (entry instanceof CommandMenuEntry.Quad q) {
            return new CommandMenuEntry[] { q.e1(), q.e2(), q.e3(), q.e4() };
        }
        return new CommandMenuEntry[] { entry };
    }

    /** Row-relative split fractions for a multi-cell row; empty for single-cell rows. */
    public static double[] cellBoundaries(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Split s) {
            return new double[] { s.leftFraction() };
        }
        if (entry instanceof CommandMenuEntry.Triple t) {
            return new double[] { t.leftFraction(), t.middleEnd() };
        }
        if (entry instanceof CommandMenuEntry.Quad q) {
            return new double[] { q.boundary1(), q.boundary2(), q.boundary3() };
        }
        return new double[0];
    }

    /** The text a cell shows — a Toggle may append its state. */
    public static String labelFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t) {
            return t.showStateText() ? t.label() + (t.state() ? " [ON]" : " [OFF]") : t.label();
        }
        return entry.label();
    }

    /** Base tint for a cell's state, or 0 for the plain idle fill. */
    public static int baseTintFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t)     return t.state() ? TOGGLE_ON : TOGGLE_OFF;
        if (entry instanceof CommandMenuEntry.SaveAction s) return s.saved() ? SAVED_GREY : TOGGLE_ON;
        if (entry instanceof CommandMenuEntry.Label)        return 0;
        if (entry instanceof CommandMenuEntry.Run r     && r.highlighted())  return HIGHLIGHT;
        if (entry instanceof CommandMenuEntry.Stay s    && s.highlighted())  return HIGHLIGHT;
        if (entry instanceof CommandMenuEntry.DrillIn d && d.highlighted())  return HIGHLIGHT;
        if (entry instanceof CommandMenuEntry.ClientAction c && c.highlighted()) return HIGHLIGHT;
        return 0;
    }

    // ------------------------------------------------------------------
    // Hit-test
    // ------------------------------------------------------------------

    /**
     * Which cell of {@code row} sits under {@code mouseX}, or -1 when none is clickable there.
     *
     * <p>Labels and already-saved SaveActions miss deliberately so a dispatcher never sees a click
     * on them. The caller has already matched the row vertically.</p>
     */
    public static int hitCell(CommandMenuEntry row, int mouseX, int left, int right) {
        if (row == null || row instanceof CommandMenuEntry.Label) return -1;
        if (mouseX < left || mouseX >= right) return -1;

        CommandMenuEntry[] cells = cellsOf(row);
        double[] bounds = cellBoundaries(row);
        int usable = right - left;

        int sub = 0;
        for (int c = 0; c < bounds.length; c++) {
            if (mouseX >= left + (int) Math.round(bounds[c] * usable)) sub = c + 1;
        }

        CommandMenuEntry cell = cells[Math.min(sub, cells.length - 1)];
        if (cell instanceof CommandMenuEntry.Label) return -1;
        if (cell instanceof CommandMenuEntry.SaveAction sa && sa.saved()) return -1;
        return sub;
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    /** Draw a whole row between {@code left} and {@code right}, {@code rowH} tall from {@code top}. */
    public static void drawRow(
        GuiGraphics gg, Font font, CommandMenuEntry entry,
        int left, int top, int right, int rowH,
        int rowIndex, boolean hovered, int hoveredSub, Typing typing
    ) {
        int usable = right - left;
        double[] bounds = cellBoundaries(entry);
        CommandMenuEntry[] cells = cellsOf(entry);

        if (cells.length == 1) {
            drawCell(gg, font, cells[0], left, top, right, rowH, hovered, rowIndex, 0, typing);
            return;
        }

        int prev = left;
        for (int c = 0; c < cells.length; c++) {
            int edge = (c == cells.length - 1)
                ? right
                : left + (int) Math.round(bounds[c] * usable);
            drawCell(gg, font, cells[c], prev, top, edge, rowH,
                hovered && hoveredSub == c, rowIndex, c, typing);
            prev = edge;
        }
    }

    /** Draw one cell: tint by state, typing field if open here, centred and truncated label. */
    public static void drawCell(
        GuiGraphics gg, Font font, CommandMenuEntry entry,
        int x1, int top, int x2, int rowH,
        boolean hovered, int rowIndex, int subIdx, Typing typing
    ) {
        int bottom = top + rowH;
        boolean isLabel = entry instanceof CommandMenuEntry.Label;
        int textY = top + (rowH - font.lineHeight) / 2;

        if (typing != null && typing.at(rowIndex, subIdx)) {
            gg.fill(x1, top, x2, bottom, TYPING_BG);
            drawLabel(gg, font, typing.buffer() + "_", (x1 + x2) / 2, textY, TEXT_ON_HOVER, false);
            return;
        }

        int tint;
        if (hovered && !isLabel) {
            tint = CELL_HOVER;
        } else {
            int base = baseTintFor(entry);
            tint = base != 0 ? base : (isLabel ? 0 : CELL_IDLE);
        }
        if (tint != 0) gg.fill(x1, top, x2, bottom, tint);

        boolean dark = hovered && !isLabel;
        String label = labelFor(entry);
        int color = dark ? TEXT_ON_HOVER : TEXT_NORMAL;
        int avail = (x2 - x1) - CELL_PAD_X * 2;
        if (avail > 0 && font.width(label) > avail) {
            label = font.plainSubstrByWidth(label, avail);
        }
        // Shadow only under light text: a black label's shadow reads as smeared, doubled text.
        drawLabel(gg, font, label, (x1 + x2) / 2, textY, color, !dark);
    }

    /** Centred text with explicit shadow control ({@code drawCenteredString} always shadows). */
    public static void drawLabel(GuiGraphics gg, Font font, String text, int centerX, int y,
                                 int color, boolean shadow) {
        gg.drawString(font, text, centerX - font.width(text) / 2, y, color, shadow);
    }
}
