package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.List;

/**
 * Single source of truth for the track-side editor plot layout.
 *
 * <p>Layout rules — uniform {@link EditorLayout#GAP} block gap between every
 * adjacent template footprint, no exceptions:</p>
 *
 * <ul>
 *   <li><b>X axis</b> partitions categories. Order: track tile, tunnels,
 *       stairs adjunct, pillars. Each category occupies a column whose
 *       width is {@code maxXSize(category) + GAP} so the next category
 *       starts {@link EditorLayout#GAP} blocks past the wide-side edge of
 *       the previous one.</li>
 *   <li><b>Y axis</b> stacks the multiple {@link TrackKind}s grouped under
 *       one category. Tunnels: {@code SECTION} at the baseline, {@code
 *       PORTAL} above with a {@link EditorLayout#GAP}-block gap. Pillars:
 *       {@code BOTTOM}, {@code MIDDLE}, {@code TOP} stacked physically with
 *       a {@code GAP}-block gap between each.</li>
 *   <li><b>Z axis</b> spaces the registered variant names of one
 *       {@link TrackKind} side-by-side. {@code default} sits at
 *       {@code Z=0}, with each subsequent name offset by
 *       {@code footprint.z + GAP}. Names follow
 *       {@link TrackVariantRegistry#namesFor} order — alphabetical with
 *       {@code default} guaranteed first.</li>
 * </ul>
 *
 * <p>Every track-side editor ({@link TrackEditor}, {@link PillarEditor},
 * {@link TunnelEditor}, {@link AdjunctStairsEditor}) delegates plot
 * positioning here so the layout stays consistent and the {@link #locate}
 * single-pass containment check can resolve any plot to a
 * {@code (kind, name)} pair without polling each editor.</p>
 */
public final class TrackSidePlots {

    /** Shared baseline Y for all track-side editor plots. */
    public static final int Y_BASELINE = 250;

    /**
     * X start of each category's column. Defined in declaration order:
     * track tile → tunnels → stairs → pillars. Each value is the previous
     * column's start plus that column's max X size plus
     * {@link EditorLayout#GAP}.
     */
    public static final int X_TRACK = 0;
    public static final int X_TUNNELS = X_TRACK + 4 + EditorLayout.GAP;          // 4 = TILE_LENGTH
    public static final int X_STAIRS = X_TUNNELS + TunnelTemplate.LENGTH + EditorLayout.GAP; // +10+5 = 19
    public static final int X_PILLARS = X_STAIRS + 3 + EditorLayout.GAP;         // +3+5 = 27 (stairs xSize=3)

    private TrackSidePlots() {}

    /**
     * Plot origin for the synthetic-default variant of {@code kind}.
     * Equivalent to {@link #plotOrigin(TrackKind, String, CarriageDims)
     * plotOrigin(kind, TrackKind.DEFAULT_NAME, dims)}.
     */
    public static BlockPos plotOriginDefault(TrackKind kind, CarriageDims dims) {
        return plotOrigin(kind, TrackKind.DEFAULT_NAME, dims);
    }

    /**
     * Plot origin for {@code (kind, name)}. {@code name} must be registered
     * (or be {@link TrackKind#DEFAULT_NAME}); pass anything else and the Z
     * offset falls back to slot 0 — caller should validate beforehand.
     */
    public static BlockPos plotOrigin(TrackKind kind, String name, CarriageDims dims) {
        int x = categoryX(kind);
        int y = stackY(kind, dims);
        int z = variantZ(kind, name, dims);
        return new BlockPos(x, y, z);
    }

    /** {@link Vec3i} footprint of one stamped instance. Forwards to {@link TrackKind#dims}. */
    public static Vec3i footprint(TrackKind kind, CarriageDims dims) {
        return kind.dims(dims);
    }

    /**
     * Test whether {@code pos} lies inside any track-side plot, including
     * the 1-block outline-cage margin used by every editor's
     * {@code plotContaining}. Returns the resolved
     * {@code (kind, name, origin, footprint)} or null.
     *
     * <p>Iterates every {@link TrackKind} and every registered name for
     * each — bounded by the registry size, ~O(7 × namesPerKind), no I/O.
     * Cheap enough to run once per player per tick from the variant
     * overlay renderer.</p>
     */
    public static TrackPlotLocator.PlotInfo locate(BlockPos pos, CarriageDims dims) {
        for (TrackKind kind : TrackKind.values()) {
            List<String> names = TrackVariantRegistry.namesFor(kind);
            for (String name : names) {
                BlockPos origin = plotOrigin(kind, name, dims);
                Vec3i fp = footprint(kind, dims);
                if (containsWithMargin(pos, origin, fp)) {
                    return new TrackPlotLocator.PlotInfo(kind, name, origin, fp);
                }
            }
        }
        return null;
    }

    /** Z offset of {@code name} within {@code kind}'s row. {@code default} is slot 0. */
    public static int variantZ(TrackKind kind, String name, CarriageDims dims) {
        List<String> names = TrackVariantRegistry.namesFor(kind);
        int idx = names.indexOf(name);
        if (idx < 0) idx = 0;
        int step = footprint(kind, dims).getZ() + EditorLayout.GAP;
        return idx * step;
    }

    /**
     * Y baseline for {@code kind}'s plots. Categories that group multiple
     * kinds (tunnels, pillars) stack their kinds vertically.
     */
    public static int stackY(TrackKind kind, CarriageDims dims) {
        return switch (kind) {
            case TILE -> Y_BASELINE;
            case TUNNEL_SECTION -> Y_BASELINE;
            case TUNNEL_PORTAL -> Y_BASELINE + TunnelTemplate.HEIGHT + EditorLayout.GAP;
            case ADJUNCT_STAIRS -> Y_BASELINE;
            case PILLAR_BOTTOM -> Y_BASELINE;
            case PILLAR_MIDDLE -> Y_BASELINE + PillarSection.BOTTOM.height() + EditorLayout.GAP;
            case PILLAR_TOP -> Y_BASELINE
                + PillarSection.BOTTOM.height() + EditorLayout.GAP
                + PillarSection.MIDDLE.height() + EditorLayout.GAP;
        };
    }

    /** X column for {@code kind}'s category. */
    public static int categoryX(TrackKind kind) {
        return switch (kind) {
            case TILE -> X_TRACK;
            case TUNNEL_SECTION, TUNNEL_PORTAL -> X_TUNNELS;
            case ADJUNCT_STAIRS -> X_STAIRS;
            case PILLAR_TOP, PILLAR_MIDDLE, PILLAR_BOTTOM -> X_PILLARS;
        };
    }

    private static boolean containsWithMargin(BlockPos pos, BlockPos origin, Vec3i fp) {
        return pos.getX() >= origin.getX() - 1
            && pos.getX() <= origin.getX() + fp.getX()
            && pos.getY() >= origin.getY() - 1
            && pos.getY() <= origin.getY() + fp.getY()
            && pos.getZ() >= origin.getZ() - 1
            && pos.getZ() <= origin.getZ() + fp.getZ();
    }
}
