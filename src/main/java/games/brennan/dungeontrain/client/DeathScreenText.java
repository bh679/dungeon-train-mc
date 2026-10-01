package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * How the death screen ({@link NarrativeDeathScreen}) draws its words and its train, shared so the
 * translation editor's death-screen preview draws them the same way — same wrap, offsets, colours
 * and white number figures — rather than an imitation that drifts.
 *
 * <p>Every draw takes the colour already faded (the screen fades its whole UI in); the preview
 * passes colours as they are.</p>
 */
public final class DeathScreenText {

    public static final int OVERLAY = 0xF2090A0D;
    public static final int QUESTION = 0xFFE0B56A;
    public static final int NARR = 0xFFC7BDA7;
    public static final int KICKER = 0xFF8A7C60;
    public static final int SUBLINE = 0xFF948A70;
    static final int RED = 0xFFFF5555;
    static final int RAIL = 0xFF43454E;
    static final int INF = 0xFF5A5C66;

    /** Content column: at most this wide, and 40px in from a narrow window's edges. */
    public static final int MAX_CONTENT_WIDTH = 360;
    public static final int CONTENT_TOP = 40;
    /** The kicker line's step, and the train's. */
    public static final int KICKER_STEP = 14;
    public static final int TRAIN_STEP = 46;

    /** The server wraps number words in these so the screen can show the figures white. */
    public static final char NUM_START = '\u0001';
    public static final char NUM_END = '\u0002';

    private DeathScreenText() {}

    /**
     * A narration string as a Component, the spans between number sentinels coloured white so the
     * figures pop against the muted text. Plain strings (no sentinels) pass straight through.
     */
    public static Component styled(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (raw.indexOf(NUM_START) < 0) return Component.literal(raw);
        MutableComponent out = Component.empty();
        StringBuilder buf = new StringBuilder();
        boolean inNum = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == NUM_START) {
                flushPiece(out, buf, false);
                inNum = true;
            } else if (c == NUM_END) {
                flushPiece(out, buf, true);
                inNum = false;
            } else {
                buf.append(c);
            }
        }
        flushPiece(out, buf, inNum);
        return out;
    }

    private static void flushPiece(MutableComponent out, StringBuilder buf, boolean white) {
        if (buf.length() == 0) return;
        MutableComponent piece = Component.literal(buf.toString());
        if (white) piece.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)));
        out.append(piece);
        buf.setLength(0);
    }

    /** {@code text} wrapped to {@code w - 8} and centred on {@code cx}; returns the y below it. */
    public static int drawCentered(GuiGraphics g, Font font, Component text, int cx, int w, int y, int argb) {
        List<FormattedCharSequence> lines = font.split(text, w - 8);
        for (FormattedCharSequence line : lines) {
            int lw = font.width(line);
            g.drawString(font, line, cx - lw / 2, y, argb, false);
            y += font.lineHeight + 2;
        }
        return y;
    }

    public static int drawQuestion(GuiGraphics g, Font font, String text, int cx, int w, int y, int argb) {
        if (text == null || text.isEmpty()) return y;
        return drawCentered(g, font, styled(text), cx, w, y + 2, argb) + 2;
    }

    public static int drawNarration(GuiGraphics g, Font font, String text, int cx, int w, int y, int argb) {
        if (text == null || text.isEmpty()) return y;
        return drawCentered(g, font, styled(text), cx, w, y + 4, argb);
    }

    /** One centred line, unwrapped — the kicker over each page. */
    public static void drawCenteredLine(GuiGraphics g, Font font, Component c, int cx, int y, int argb) {
        g.drawString(font, c, cx - font.width(c) / 2, y, argb, false);
    }

    /**
     * The train across the content column, one carriage on the first page growing to a full rail by
     * the last, trailing off into a fade on every page but the last.
     *
     * @param fade applied to every colour drawn
     */
    public static void drawTrain(GuiGraphics g, Font font, int left, int w, int y, int advance, int pageCount,
                                 IntUnaryOperator fade) {
        int railY = y + 30;
        g.fill(left + 2, railY, left + w - 2, railY + 2, fade.applyAsInt(RAIL));
        int carW = 22, carH = 14, gap = 4, spacing = carW + gap;
        int startX = left + 6;
        int rightEdge = left + w - 14;            // leave room for the ∞
        int slots = Math.max(1, (rightEdge - startX) / spacing);
        // "Full" is the whole rail — the fade tail does NOT count toward it, so the final screen
        // fills completely (only the ∞ beyond), while earlier screens trail off into the fade.
        boolean lastPage = advance >= pageCount - 1;
        int full = slots;
        int solid = pageCount > 1
                ? Math.round(1f + (full - 1) * (float) advance / (pageCount - 1))
                : full;
        if (solid < 1) solid = 1;
        if (solid > full) solid = full;
        for (int i = 0; i < solid; i++) {
            int cxp = startX + i * spacing;
            if (cxp + carW > rightEdge) break;
            g.fill(cxp, railY - carH, cxp + carW, railY, fade.applyAsInt(0xFF33353E));
            g.fill(cxp, railY - carH, cxp + carW, railY - carH + 2, fade.applyAsInt(RED));
            g.fill(cxp + 4, railY - carH + 4, cxp + 9, railY - carH + 9, fade.applyAsInt(0xFF14151A));
            g.fill(cxp + 13, railY - carH + 4, cxp + 18, railY - carH + 9, fade.applyAsInt(0xFF14151A));
        }
        if (!lastPage) {
            int[] tail = { 0x8033353E, 0x4D2B2C33, 0x2624252B };
            int fadeX = startX + solid * spacing;
            for (int j = 0; j < tail.length; j++) {
                int cxp = fadeX + j * spacing;
                int fw = Math.min(cxp + carW, rightEdge);
                if (fw <= cxp) break;
                int fh = carH - 2 - j * 3;
                if (fh < 5) fh = 5;
                g.fill(cxp, railY - fh, fw, railY, fade.applyAsInt(tail[j]));
            }
        }
        g.drawString(font, "∞", left + w - 12, railY - 8, fade.applyAsInt(INF), false);
    }
}
