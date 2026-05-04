package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateType;

import java.util.Locale;

/**
 * A decoration attached to a bridge-pillar column — distinct from
 * {@link PillarSection}, which covers the three tiles of the pillar column
 * itself (TOP / MIDDLE / BOTTOM stacked 1×H×W slabs).
 *
 * <p>Adjuncts have an arbitrary {@code x×y×z} footprint and are placed
 * <em>alongside</em> the pillar, outside the track corridor. The sole member
 * today is {@link #STAIRS}, a 3×8×3 prism that spawns every second pillar
 * with alternating mirror so the staircase always leads "outward" from the
 * track.</p>
 *
 * <p>Adjunct templates live in the same {@code pillars/} NBT directory as
 * sections but with an {@code adjunct_} filename prefix so the same
 * three-tier store (config → bundled → fallback) applies without risk of
 * cross-registering an adjunct as a section.</p>
 */
public enum PillarAdjunct implements TemplateType {
    STAIRS(3, 8, 3);

    private final int xSize;
    private final int ySize;
    private final int zSize;

    PillarAdjunct(int xSize, int ySize, int zSize) {
        this.xSize = xSize;
        this.ySize = ySize;
        this.zSize = zSize;
    }

    /** Extent along the X axis (along the direction of track travel). */
    public int xSize() {
        return xSize;
    }

    /** Extent along the Y axis (vertical). */
    public int ySize() {
        return ySize;
    }

    /** Extent along the Z axis (outward from the track corridor). */
    public int zSize() {
        return zSize;
    }

    /**
     * Filename stem for this adjunct's NBT file — {@code stairs}. Combined
     * with the {@code adjunct_} prefix by {@code PillarTemplateStore} to
     * keep adjuncts namespaced from section files in the same directory.
     */
    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public TemplateKind kind() {
        // Stairs is the only adjunct today and the user-facing taxonomy
        // treats it as its own template kind. Future non-stairs adjuncts
        // would either get their own TemplateKind or revert this to a
        // generic ADJUNCT kind.
        return TemplateKind.STAIRS;
    }
}
