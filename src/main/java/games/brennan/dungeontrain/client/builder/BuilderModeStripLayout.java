package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;

/**
 * Pure geometry for the Open screen's mode strip — every builder mode on one row, the selected one
 * large and captioned, the rest small thumbnails beside it.
 *
 * <p>The sibling of {@link BuilderGridLayout} and {@link BuilderTemplateGridLayout}, split out for
 * the same reason: this arithmetic has to survive GUI scale 1 (a very wide, short viewport) through
 * scale 4 (a small one) without tiles overlapping each other or running off the edge, and that is
 * cheap to test here and tedious to eyeball in-game at four scales.</p>
 *
 * <p>The strip replaced a single tile that <em>cycled</em> through the modes, so the one choice
 * deciding which wall of photos you were looking at was the one thing on the screen you could not
 * see. Showing all of them costs the row nothing vertically — {@link #height()} is the selected
 * tile's height, exactly what the cycling tile occupied — so the template grid below keeps its
 * full budget.</p>
 *
 * <p>Slot order <em>is</em> menu order: slot {@code n} is always {@code BuilderMode.values()[n]}.
 * The selection expands where it already sits rather than moving to a fixed middle, so a mode stays
 * where you last saw it and only its size changes. The row's outer bounds hold still while that
 * happens — one large tile and the rest small comes to the same {@link #stripWidth()} whichever one
 * is chosen — so the strip doesn't jump on the screen as the big tile slides along it.</p>
 */
record BuilderModeStripLayout(int originX, int topY, int selectedSlot,
                              int selectedWidth, int selectedHeight,
                              int smallWidth, int smallHeight, int gap) {

    /** One slot per mode. Derived so a fifth mode joins the strip rather than falling off it. */
    static final int SLOTS = BuilderMode.values().length;

    static final int GAP = 4;

    private static final int SIDE_MARGIN = 16;

    /** How tall a thumbnail is against the selected tile, before any shrinking to fit. */
    private static final int SMALL_HEIGHT_NUMERATOR = 5;
    private static final int SMALL_HEIGHT_DENOMINATOR = 8;

    /** Floors, so a narrow window yields small tiles rather than invisible or negative ones. */
    private static final int MIN_SMALL_WIDTH = 24;
    private static final int MIN_SELECTED_WIDTH = 120;

    /**
     * @param screenWidth    screen width in GUI pixels
     * @param topY           first Y the strip may occupy
     * @param selectedSlot   which slot is expanded — the selected mode's own place in the menu order
     * @param selectedWidth  how wide the large tile wants to be — the width of the controls below it
     * @param selectedHeight the row's height, which never changes: the grid underneath is budgeted
     *                       against it, so a strip that grew would push the grid down
     */
    static BuilderModeStripLayout of(int screenWidth, int topY, int selectedSlot,
                                     int selectedWidth, int selectedHeight) {
        int smalls = Math.max(0, SLOTS - 1);
        int gaps = GAP * Math.max(0, SLOTS - 1);
        int content = Math.max(1, screenWidth - 2 * SIDE_MARGIN - gaps);

        // Thumbnails are 16:9 like the template cells below them, because they hold the same kind
        // of picture.
        int smallWidth = Math.max(1,
                selectedHeight * SMALL_HEIGHT_NUMERATOR / SMALL_HEIGHT_DENOMINATOR) * 16 / 9;
        int selected = selectedWidth;

        if (selected + smalls * smallWidth > content) {
            // Thumbnails give way first. The selected tile matching the controls beneath it — and
            // the tile the other builder screens show — is the part worth keeping longest; a
            // thumbnail only has to stay recognisable.
            smallWidth = Math.max(MIN_SMALL_WIDTH, smalls == 0 ? smallWidth : (content - selected) / smalls);
            if (selected + smalls * smallWidth > content) {
                selected = Math.max(MIN_SELECTED_WIDTH, content - smalls * smallWidth);
            }
        }
        int smallHeight = Math.min(selectedHeight, Math.max(1, smallWidth * 9 / 16));

        int stripWidth = selected + smalls * smallWidth + gaps;
        int originX = Math.max(0, (screenWidth - stripWidth) / 2);

        return new BuilderModeStripLayout(originX, topY, clampSlot(selectedSlot), selected,
                selectedHeight, smallWidth, smallHeight, GAP);
    }

    /**
     * Keep the expanded slot inside the row.
     *
     * <p>An out-of-range slot would otherwise mean no tile grows and the strip silently comes out
     * all-thumbnails — a wrong picture rather than a crash, which is the harder kind to notice.</p>
     */
    private static int clampSlot(int slot) {
        return Math.max(0, Math.min(slot, SLOTS - 1));
    }

    boolean isSelected(int slot) {
        return slot == selectedSlot;
    }

    int widthFor(int slot) {
        return isSelected(slot) ? selectedWidth : smallWidth;
    }

    int heightFor(int slot) {
        return isSelected(slot) ? selectedHeight : smallHeight;
    }

    /**
     * Walked rather than indexed, because one slot is a different width from the rest — and with
     * four of them, a loop that is obviously right beats a closed form that needs checking.
     */
    int xFor(int slot) {
        int x = originX;
        for (int i = 0; i < slot; i++) {
            x += widthFor(i) + gap;
        }
        return x;
    }

    /** Thumbnails ride the centre line of the big tile, so the row reads as one band. */
    int yFor(int slot) {
        return topY + (selectedHeight - heightFor(slot)) / 2;
    }

    /** The row's height — the selected tile's, since nothing else in it is taller. */
    int height() {
        return selectedHeight;
    }

    /**
     * The row's full extent. No hit-testing lives here, unlike the grid layouts — each tile is its
     * own {@link BuilderTileButton}, so the widget already answers "is the cursor on me?" and a
     * second copy of that arithmetic would only be something to drift out of step with.
     */
    int stripWidth() {
        return selectedWidth + Math.max(0, SLOTS - 1) * (smallWidth + gap);
    }
}
