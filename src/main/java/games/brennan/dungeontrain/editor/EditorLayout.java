package games.brennan.dungeontrain.editor;

/**
 * Shared layout constants for every editor (carriage, contents, parts,
 * track-side). The single {@link #GAP} value is the empty-block separation
 * between any two adjacent template footprints — same on every axis,
 * across every editor — so the world above {@code y=250} reads as a
 * uniform grid.
 */
public final class EditorLayout {

    /**
     * Empty-block gap between adjacent template footprints. Outline cages
     * sit one block past each footprint edge, so two adjacent plots'
     * bedrock cages are separated by ({@code GAP - 2}) air blocks
     * (i.e. for {@code GAP = 5} → 3 visible air blocks between cages).
     */
    public static final int GAP = 5;

    private EditorLayout() {}
}
