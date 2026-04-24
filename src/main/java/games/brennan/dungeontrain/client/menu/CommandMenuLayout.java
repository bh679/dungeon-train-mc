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
