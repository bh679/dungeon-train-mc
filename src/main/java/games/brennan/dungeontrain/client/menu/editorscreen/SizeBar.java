package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A thin bar along the bottom of a relay build's tile: how much of the upload limit its stored
 * blocks take.
 *
 * <p>Measured against {@link BuilderRelayUpload#MAX_BLOCKS_CHARS}, the size past which an upload is
 * refused, because that is the "max" a builder can actually run into. No numbers on the tile: the
 * sheet beside the grid says the exact size for the build that is picked.</p>
 */
final class SizeBar {

    static final int GREEN = 0xFF5CCF6A;
    static final int AMBER = 0xFFE8A33A;
    static final int RED = 0xFFE0524A;
    private static final int TRACK = 0xB0000000;
    private static final int HEIGHT = 3;
    private static final int INSET = 3;

    private SizeBar() {}

    /** Share of the upload limit, 0..1; anything at or past the limit is a full bar. */
    static float fraction(long chars) {
        if (chars <= 0) return 0F;
        return (float) Math.min(1.0, chars / (double) BuilderRelayUpload.MAX_BLOCKS_CHARS);
    }

    /** Green under half, amber to 85%, red from there: red means one more room may not fit. */
    static int colour(float fraction) {
        if (fraction >= 0.85F) return RED;
        if (fraction >= 0.5F) return AMBER;
        return GREEN;
    }

    /** Draw the bar on a square tile at {@code x,y}; nothing when the relay sent no length. */
    static void draw(GuiGraphics g, int x, int y, int size, long chars) {
        if (chars <= 0) return;
        int left = x + INSET;
        int width = size - 2 * INSET;
        int top = y + size - INSET - HEIGHT;
        if (width <= 0) return;
        float f = fraction(chars);
        int filled = Math.max(1, Math.round(width * f));
        g.fill(left, top, left + width, top + HEIGHT, TRACK);
        g.fill(left, top, left + filled, top + HEIGHT, colour(f));
    }
}
