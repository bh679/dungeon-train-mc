package games.brennan.dungeontrain.track;

import java.util.Locale;

/**
 * One of three editable sections of a bridge-pillar column: the decorative cap
 * ({@link #TOP}), the repeating middle slab ({@link #MIDDLE}), and the base
 * that sits on the ground ({@link #BOTTOM}).
 *
 * <p>Pillar column placement is {@code bottom → middles → top} from the ground
 * up. When a column is shorter than {@code bottom.height() + top.height()},
 * the bottom is placed first (truncated from the top if needed) and whatever
 * space remains is filled with the top template, truncated from its bottom
 * rows so the decorative cap stays at the column's top.</p>
 *
 * <p>Section heights are fixed here — the bundled NBT templates ship at these
 * sizes, so changing the constants is a save-format change, not a template
 * change.</p>
 */
public enum PillarSection {
    TOP(4),
    MIDDLE(1),
    BOTTOM(3);

    private final int height;

    PillarSection(int height) {
        this.height = height;
    }

    /** Vertical extent in blocks. */
    public int height() {
        return height;
    }

    /**
     * Filename stem for this section's NBT file — {@code top}, {@code middle},
     * {@code bottom}. Combined with the {@code pillar_} prefix by
     * {@code PillarTemplateStore} to keep the pillar files namespaced away
     * from the carriage templates in the same directory.
     */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
