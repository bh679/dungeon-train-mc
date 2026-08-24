package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The scrollbar shared by the translation screen's two lists — the strings on the left and the
 * submissions column on the right.
 *
 * <p>Both widgets drew the same three-pixel thumb and neither could be grabbed: a click anywhere
 * inside the widget went straight to the row underneath, so the bar was decoration. The geometry
 * was already duplicated between them, so the drag handling lives here once rather than being
 * copied a second time.</p>
 *
 * <p>The grab area is deliberately wider than the drawn track. Three pixels is not a mouse target,
 * and the alternative — drawing a bar fat enough to hit — costs the rows width they need more.</p>
 */
final class ListScrollbar {

    /** Width of the drawn thumb. Widgets size their text column against this. */
    static final int WIDTH = 3;
    /** How far left of the track a click still counts as aiming at it. */
    private static final int GRAB_PAD = 3;
    /** Never let the thumb shrink below something you can actually grab. */
    private static final int MIN_THUMB_H = 12;
    private static final int COLOUR = 0x80AAB0BE;

    /** True between pressing the bar and letting go — see {@code mouseDragged} in both widgets. */
    private boolean dragging;

    boolean isDragging() {
        return dragging;
    }

    void begin() {
        dragging = true;
    }

    void end() {
        dragging = false;
    }

    private static int trackX(int x, int width) {
        return x + width - WIDTH - 1;
    }

    /** Is the cursor aiming at the bar rather than at the row behind it? */
    boolean isOverTrack(double mouseX, int x, int width) {
        return mouseX >= trackX(x, width) - GRAB_PAD;
    }

    private static int thumbHeight(int height, int totalHeight) {
        return Math.max(MIN_THUMB_H, (int) ((long) height * height / Math.max(1, totalHeight)));
    }

    /**
     * The scroll offset that puts the thumb under the cursor, centred on it — so pressing the
     * track jumps to roughly what you pointed at, and a drag follows the mouse from there.
     */
    int scrollFor(double mouseY, int y, int height, int totalHeight, int maxScroll) {
        int thumbH = thumbHeight(height, totalHeight);
        int travel = height - thumbH;
        if (travel <= 0 || maxScroll <= 0) {
            return 0;
        }
        double thumbTop = mouseY - y - thumbH / 2.0;
        return Mth.clamp((int) Math.round(thumbTop * maxScroll / travel), 0, maxScroll);
    }

    /** Draw the thumb. Callers skip this when there is nothing to scroll. */
    void render(GuiGraphics g, int x, int y, int width, int height, int totalHeight, int scroll,
                int maxScroll) {
        if (maxScroll <= 0) {
            return;
        }
        int thumbH = thumbHeight(height, totalHeight);
        int thumbY = y + (int) ((long) (height - thumbH) * scroll / maxScroll);
        int left = trackX(x, width);
        g.fill(left, thumbY, left + WIDTH, thumbY + thumbH, COLOUR);
    }
}
