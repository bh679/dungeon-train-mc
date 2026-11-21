package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;

/**
 * Where {@link AdvancementPerksButton} sits on the advancements screen: a row under the window's
 * bottom-right corner, the same on every tab. Works on vanilla's screen and Better Advancements', whose
 * window bounds {@code BetterAdvancementsScreenBoundsMixin} reports through {@link #reportWindow}.
 */
public final class AdvancementWindowRow {

    private static final int VANILLA_WINDOW_WIDTH = 252;
    private static final int VANILLA_WINDOW_HEIGHT = 140;
    static final int GAP = 2;

    /** Better Advancements' window, as its last frame drew it ({@code right} is 0 until reported). */
    private static int baRight;
    private static int baBottom;

    private AdvancementWindowRow() {}

    /** Better Advancements' renderWindow reports its window here every frame. */
    public static void reportWindow(int left, int top, int right, int bottom) {
        baRight = right;
        baBottom = bottom;
    }

    /** The window's right edge and the row's top. */
    record Row(int right, int top) {}

    /**
     * A row {@code height} tall below the window's bottom-right corner when there is room, else tucked
     * inside it; null until Better Advancements has reported its window.
     */
    static Row row(Screen screen, int height) {
        int right;
        int bottom;
        if (screen instanceof AdvancementsScreen) {
            right = (screen.width + VANILLA_WINDOW_WIDTH) / 2;
            bottom = (screen.height + VANILLA_WINDOW_HEIGHT) / 2;
        } else {
            if (baRight <= 0) return null;
            right = baRight;
            bottom = baBottom;
        }
        int top = bottom + GAP + height <= screen.height ? bottom + GAP : bottom - height - GAP * 3;
        return new Row(right, top);
    }
}
