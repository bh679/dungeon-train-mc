package games.brennan.dungeontrain.client.builder;

/**
 * Row/column arithmetic for the Train Builder pause menu.
 *
 * <p>Vanilla's pause menu is a {@code GridLayout} that has already been arranged by the time
 * {@code ScreenEvent.Init.Post} fires, so rebuilding it means positioning widgets by hand. Every
 * coordinate is derived from the Back-to-Game button's own slot rather than hardcoded, so the menu
 * follows vanilla if its geometry ever changes — the same slot-inheritance idiom
 * {@code PauseMenuLayoutHandler} uses.</p>
 *
 * <p>Pure, so the half-width split and the row stride can be pinned in tests instead of eyeballed
 * in-game at three window sizes.</p>
 */
public final class BuilderPauseMenuLayout {

    /** Vanilla's inter-button padding ({@code PauseScreen.BUTTON_PADDING}). */
    public static final int GAP = 4;

    /**
     * Horizontal space between two half-width buttons. Vanilla's grid pads <em>each</em> cell by
     * {@link #GAP}, so the visible channel between them is two paddings wide: 98 + 8 + 98 = 204,
     * its {@code BUTTON_WIDTH_HALF} and {@code BUTTON_WIDTH_FULL}. Using one gap here would make
     * the builder rows two pixels wider than every vanilla row above and below them.
     */
    public static final int INNER_GAP = GAP * 2;

    private BuilderPauseMenuLayout() {}

    /** Y of row {@code index}, counting the Back-to-Game row as 0. */
    public static int rowY(int firstRowY, int rowHeight, int index) {
        return firstRowY + index * (rowHeight + GAP);
    }

    public static int leftHalfWidth(int slotWidth) {
        return (slotWidth - INNER_GAP) / 2;
    }

    /**
     * The right half absorbs the odd pixel, so a full-width slot of odd width is spanned exactly
     * rather than leaving a one-pixel gutter at the edge.
     */
    public static int rightHalfWidth(int slotWidth) {
        return slotWidth - leftHalfWidth(slotWidth) - INNER_GAP;
    }

    public static int rightHalfX(int slotX, int slotWidth) {
        return slotX + leftHalfWidth(slotWidth) + INNER_GAP;
    }

    /** Width of the tools panel — one half-slot, so it lines up with the menu's own half buttons. */
    public static int panelWidth(int slotWidth) {
        return leftHalfWidth(slotWidth);
    }

    /**
     * Total width of the menu plus its panel.
     *
     * <p>The two are positioned as one block so the result still reads centred; leaving the menu
     * where vanilla put it and hanging the panel off its right edge makes the whole screen look
     * off-balance.</p>
     */
    public static int combinedWidth(int slotWidth) {
        return slotWidth + INNER_GAP + panelWidth(slotWidth);
    }

    /** True when there's room for the panel without squeezing or overlapping the menu. */
    public static boolean fitsSidePanel(int screenWidth, int slotWidth) {
        return screenWidth >= combinedWidth(slotWidth);
    }

    /**
     * X of the main column. Unchanged from vanilla's own centring when the panel doesn't fit —
     * a missing panel shouldn't move the menu.
     */
    public static int mainColumnX(int screenWidth, int slotWidth, int vanillaX) {
        return fitsSidePanel(screenWidth, slotWidth)
                ? (screenWidth - combinedWidth(slotWidth)) / 2
                : vanillaX;
    }

    public static int panelX(int screenWidth, int slotWidth, int vanillaX) {
        return mainColumnX(screenWidth, slotWidth, vanillaX) + slotWidth + INNER_GAP;
    }

    /**
     * Space between groups of tools in the side panel. Big enough to read as a break, small enough
     * that four groups don't push the last one off a short viewport — a skipped full row (24 px)
     * left canyons between them.
     */
    public static final int GROUP_GAP = 8;

    /** Space between mirror cells — tighter than {@link #GAP}, since they read as one control. */
    public static final int MIRROR_CELL_GAP = 2;

    /** Nominal width of one of {@code count} mirror cells sharing a panel row. */
    public static int mirrorCellWidth(int panelWidth, int count) {
        return (panelWidth - MIRROR_CELL_GAP * (count - 1)) / count;
    }

    /**
     * Width of cell {@code index}. The last one absorbs the division remainder so the row spans
     * the panel exactly — the same rounding rule as the half-width split, which is what stopped
     * the builder rows being two pixels wider than vanilla's.
     */
    public static int mirrorCellWidthAt(int panelWidth, int count, int index) {
        int cell = mirrorCellWidth(panelWidth, count);
        return index == count - 1
                ? panelWidth - index * (cell + MIRROR_CELL_GAP)
                : cell;
    }

    public static int mirrorCellX(int panelX, int panelWidth, int count, int index) {
        return panelX + index * (mirrorCellWidth(panelWidth, count) + MIRROR_CELL_GAP);
    }

    /**
     * Whether the bottom row splits to reveal Exit Game.
     *
     * <p>Same rule as the title screen's editor reveal: a developer affordance, and only on a
     * developer build. {@code branch} is {@code VersionInfo.BRANCH}, baked at build time; a null
     * or unknown branch counts as dev, because hiding a dev affordance from a developer is the
     * worse error.</p>
     */
    public static boolean shouldRevealExitGame(String branch, boolean shiftDown) {
        return shiftDown && !"main".equals(branch);
    }
}
