package games.brennan.dungeontrain.client.menu;

/**
 * Shared panel layout constants used by both the renderer and the raycast.
 * All values are in world units (blocks).
 *
 * <p>Panel center = {@link CommandMenuState#anchorPos()}. Rows stack
 * vertically from the top of the panel downward along {@link
 * CommandMenuState#anchorUp()}. The header (breadcrumb + typing status) is
 * a fixed-height band at the top; entries fill the remaining rows.
 */
public final class CommandMenuLayout {

    /** Horizontal extent of the panel. ~15 chars at the chosen text scale. */
    public static final double PANEL_WIDTH = 1.6;

    /** Header band height (breadcrumb + typing indicator). */
    public static final double HEADER_HEIGHT = 0.35;

    /** Height of a single entry row, including padding. */
    public static final double ROW_HEIGHT = 0.22;

    /** Small vertical gap between entries. */
    public static final double ROW_GAP = 0.0;

    /** Panel total depth (thickness along the normal). Thin — essentially a decal. */
    public static final double PANEL_DEPTH = 0.02;

    /** Text scale factor (world units per font pixel). */
    public static final double TEXT_SCALE = 1.0 / 100.0;

    // ---------------------------------------------------------------
    // Screen-space (pixel) layout.
    //
    // The menu renders as a Minecraft Screen rather than a world-space
    // decal. The world-unit constants above are kept because
    // MenuScreen#panelWidth is declared in them and ~90 screen classes
    // return values on that scale; PX_PER_UNIT converts. Treat the
    // panelWidth() contract as "width units", not blocks.
    // ---------------------------------------------------------------

    /** Pixels per world unit of {@link MenuScreen#panelWidth()}. 1.6 units → 200px. */
    public static final double PX_PER_UNIT = 125.0;

    /** Row height in pixels. Matches a vanilla button. */
    public static final int ROW_H = 20;

    /** Vertical gap between rows, in pixels. */
    public static final int ROW_GAP_PX = 2;

    /** Header band (breadcrumb) height in pixels. */
    public static final int HEADER_H = 24;

    /** Padding inside the panel edge, in pixels. */
    public static final int PANEL_PAD = 6;

    /** Gap between the main panel and the side panel, in pixels. */
    public static final int SIDE_GAP_PX = 8;

    /**
     * Height reserved at the bottom of the screen for the vanilla hotbar, which
     * keeps rendering behind us. The panel centres in the space above it so it
     * never covers the slots the player is scrolling through.
     */
    public static final int HOTBAR_RESERVE = 48;

    /** Convert a {@link MenuScreen#panelWidth()} value to pixels. */
    public static int panelPixelWidth(double units) {
        return (int) Math.round(units * PX_PER_UNIT);
    }

    /** Total pixel height of a panel with {@code entryCount} rows. */
    public static int pixelHeight(int entryCount) {
        return HEADER_H + Math.max(1, entryCount) * (ROW_H + ROW_GAP_PX) + PANEL_PAD;
    }

    /** Y of the top edge of row {@code i}, relative to the panel's top edge. */
    public static int rowTop(int rowIndex) {
        return HEADER_H + rowIndex * (ROW_H + ROW_GAP_PX);
    }

    private CommandMenuLayout() {}

    /** Total panel height given an entry count. */
    public static double totalHeight(int entryCount) {
        return HEADER_HEIGHT + Math.max(1, entryCount) * (ROW_HEIGHT + ROW_GAP);
    }

    /**
     * Y-coordinate (along {@code anchorUp}, relative to {@code anchorPos}) of
     * the center of row {@code i}. The top of the panel is at +totalHeight/2;
     * rows grow downward from below the header band.
     */
    public static double rowCenterY(int rowIndex, int entryCount) {
        double top = totalHeight(entryCount) / 2.0;
        double afterHeader = top - HEADER_HEIGHT;
        return afterHeader - (rowIndex + 0.5) * (ROW_HEIGHT + ROW_GAP);
    }

    /** Y-coordinate of the header band center (for breadcrumb text). */
    public static double headerCenterY(int entryCount) {
        double top = totalHeight(entryCount) / 2.0;
        return top - HEADER_HEIGHT / 2.0;
    }
}
