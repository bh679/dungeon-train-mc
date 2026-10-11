package games.brennan.dungeontrain.item;

import javax.annotation.Nullable;

/**
 * Where each of a {@link VariantClipboardItem}'s variants sits in the mini block grid drawn on
 * its slot icon ({@code client.VariantClipboardDecorator}): {@link #PER_ROW} cells across the
 * 16-px icon, filling the bottom row first and stacking upward, at most {@link #MAX_ROWS} rows so
 * the ghost cube above stays readable. Kept apart from the renderer so the order is testable.
 */
public final class VariantClipboardIconLayout {

    /** Width / height of the slot icon in GUI pixels. */
    public static final int ICON = 16;
    public static final int PER_ROW = 8;
    public static final int CELL = ICON / PER_ROW;
    public static final int MAX_ROWS = 4;
    public static final int MAX_CELLS = PER_ROW * MAX_ROWS;

    private VariantClipboardIconLayout() {}

    /**
     * Top-left of cell {@code index} relative to the icon's top-left, as {@code {dx, dy}};
     * {@code null} past {@link #MAX_CELLS} (or for a negative index).
     */
    public static @Nullable int[] cellOrigin(int index) {
        if (index < 0 || index >= MAX_CELLS) return null;
        int col = index % PER_ROW;
        int row = index / PER_ROW;
        return new int[] {col * CELL, ICON - CELL - row * CELL};
    }
}
