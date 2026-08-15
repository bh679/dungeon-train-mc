package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.DimensionalCarriageSizes;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
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
 *       {@code default} guaranteed first. A name that is a <b>sub-variant</b>
 *       of another claims no Z slot: it sits {@code +X} of its parent
 *       instead, so a group grows away from the row rather than through it.</li>
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
     * Z baseline for the track-side row — sourced from {@link
     * EditorLayout#TRACKS_FIRST_Z}. Sits past every other view's Z range
     * (CARRIAGES extends through the parts grid; CONTENTS sits between)
     * so {@link #locate} can never claim a position that also belongs to
     * a parts, contents, or carriage plot.
     */
    public static final int Z_BASELINE = EditorLayout.TRACKS_FIRST_Z;

    /**
     * X start of each category's column. Defined in declaration order:
     * track tile → tunnels → stairs → pillars. Each value is the previous
     * column's start plus that column's max X size plus
     * {@link EditorLayout#GAP}.
     */
    public static final int X_TRACK = 0;
    public static final int X_TUNNELS = X_TRACK + 4 + EditorLayout.GAP;          // 4 = TILE_LENGTH
    public static final int X_STAIRS = X_TUNNELS + TunnelPlacer.LENGTH + EditorLayout.GAP; // +10+5 = 19
    public static final int X_PILLARS = X_STAIRS + 3 + EditorLayout.GAP;         // +3+5 = 27 (stairs xSize=3)
    /**
     * Dimensional carriages sit past the pillar column (pillar xSize = 1). Their own category, so nothing is
     * ever stamped here at the same time as the track-side kinds — the editor clears every plot on
     * a category switch — but the column keeps the two apart when reading the layout.
     */
    public static final int X_PORTALS = X_PILLARS + 1 + EditorLayout.GAP;        // +1+5 = 33

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
        BlockPos child = subVariantOrigin(kind, name, dims);
        if (child != null) return child;
        int x = categoryX(kind);
        int y = stackY(kind, dims);
        int z = variantZ(kind, name, dims);
        return new BlockPos(x, y, z);
    }

    /**
     * Plot origin for a sub-variant, or null when {@code name} is top-level.
     *
     * <p>A row of a given kind runs along {@code +Z}, so a parent's members run along {@code +X}
     * from it — the two axes stay independent, and a group grows away from its neighbours rather
     * than into them. Each member's step is its <b>own</b> footprint plus the gap, because a portal
     * room is whatever size its author made it and a uniform stride would let a widened member grow
     * into the next one.</p>
     *
     * <p>Only the last category's column can afford to grow on {@code +X}, which today is exactly
     * the one kind that has groups ({@link TrackKind#DIMENSIONAL_CARRIAGE}, at {@link #X_PORTALS}).</p>
     */
    private static BlockPos subVariantOrigin(TrackKind kind, String name, CarriageDims dims) {
        java.util.Optional<String> parent = TrackVariantGroupStore.findParentOf(kind, name);
        if (parent.isEmpty()) return null;
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> group =
            TrackVariantGroupStore.get(kind, parent.get());
        if (group.isEmpty()) return null;
        int index = group.get().indexOf(name);
        if (index < 0) return null;

        int x = categoryX(kind) + footprint(kind, parent.get(), dims).getX() + EditorLayout.GAP;
        for (int i = 0; i < index; i++) {
            x += footprint(kind, group.get().members().get(i).id(), dims).getX() + EditorLayout.GAP;
        }
        return new BlockPos(x, stackY(kind, dims), variantZ(kind, parent.get(), dims));
    }

    /** {@link Vec3i} footprint of one stamped instance. Forwards to {@link TrackKind#dims}. */
    public static Vec3i footprint(TrackKind kind, CarriageDims dims) {
        return kind.dims(dims);
    }

    /**
     * Footprint of one stamped instance of {@code (kind, name)}.
     *
     * <p>Identical to {@link #footprint(TrackKind, CarriageDims)} except for a
     * {@link TrackKind#freeSizeAboveFloor()} kind, where the size belongs to the individual variant
     * rather than the kind — a dimensional carriage is whatever size its author made it.</p>
     */
    public static Vec3i footprint(TrackKind kind, String name, CarriageDims dims) {
        if (!kind.freeSizeAboveFloor()) return kind.dims(dims);
        return DimensionalCarriageSizes.sizeOf(name, dims);
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
                Vec3i fp = footprint(kind, name, dims);
                if (containsWithMargin(pos, origin, fp)) {
                    return new TrackPlotLocator.PlotInfo(kind, name, origin, fp);
                }
            }
        }
        return null;
    }

    /**
     * Z offset of {@code name} within {@code kind}'s row. {@code default} is slot 0.
     *
     * <p>Every kind but one has a fixed per-variant footprint, so slots are a uniform stride. A
     * {@link TrackKind#freeSizeAboveFloor()} kind does not: a dimensional carriage is as wide as its author
     * made it, and a uniform stride sized off the kind would let a widened room grow straight into
     * its neighbour's plot. Those rows are packed from each variant's own {@link #slotZ} instead —
     * a reserved span that only grows, in {@link #SLOT_STEP} jumps, once a room closes to within
     * {@link #SLOT_MIN_CLEARANCE} blocks of filling it. Most resizes therefore move nothing.</p>
     *
     * <p>Callers that change a size or the registered name set must clear every plot in the row
     * <b>before</b> the change and restamp after, because the change moves the later plots — see
     * {@code DimensionalCarriageEditor.relayout}.</p>
     */
    public static int variantZ(TrackKind kind, String name, CarriageDims dims) {
        // Top-level names only — a sub-variant claims no Z slot of its own (it sits +X of its
        // parent), so its parent's row position stays where it would have been without it.
        List<String> names = TrackVariantGroupStore.topLevelNames(kind);
        int idx = names.indexOf(name);
        if (idx < 0) idx = 0;

        if (kind.freeSizeAboveFloor()) {
            int z = Z_BASELINE;
            for (int i = 0; i < idx; i++) {
                z += slotZ(kind, names.get(i), dims);
            }
            return z;
        }

        int step = footprint(kind, dims).getZ() + EditorLayout.GAP;
        return Z_BASELINE + idx * step;
    }

    /**
     * Least air a plot keeps between its far edge and the next plot's slot before the row has to
     * make more room.
     *
     * <p>Three blocks rather than one: a single block of clearance reads as touching from inside
     * the plot, and the whole point of the slot is that the author never sees their room about to
     * collide with the next one.</p>
     */
    public static final int SLOT_MIN_CLEARANCE = 3;

    /**
     * How much a slot grows by when a plot outgrows it.
     *
     * <p>Deliberately coarse. The row is re-stamped whenever a slot changes, so quantising means an
     * ordinary resize moves nothing at all — a room can widen freely inside its slot, and only the
     * tap that would close to within {@link #SLOT_MIN_CLEARANCE} of the neighbour pays for shifting
     * the rest of the row. Buying ten blocks at a time then keeps the next nine taps free too.</p>
     */
    public static final int SLOT_STEP = 10;

    /**
     * Z span reserved for one plot of a {@link TrackKind#freeSizeAboveFloor()} kind, gap included.
     *
     * <p>Starts at the kind's own footprint plus {@link EditorLayout#GAP} — so a row of
     * default-sized rooms lays out exactly as it did before sizes were authorable — and grows in
     * {@link #SLOT_STEP} jumps once a room needs more.</p>
     */
    public static int slotZ(TrackKind kind, String name, CarriageDims dims) {
        int base = kind.dims(dims).getZ() + EditorLayout.GAP;
        int needed = footprint(kind, name, dims).getZ() + SLOT_MIN_CLEARANCE;
        if (needed <= base) return base;
        int steps = (needed - base + SLOT_STEP - 1) / SLOT_STEP;
        return base + steps * SLOT_STEP;
    }

    /**
     * Y baseline for {@code kind}'s plots. Categories that group multiple
     * kinds (tunnels, pillars) stack their kinds vertically.
     */
    public static int stackY(TrackKind kind, CarriageDims dims) {
        return switch (kind) {
            case TILE -> Y_BASELINE;
            case TUNNEL_SECTION -> Y_BASELINE;
            case TUNNEL_PORTAL -> Y_BASELINE + TunnelPlacer.HEIGHT + EditorLayout.GAP;
            case ADJUNCT_STAIRS -> Y_BASELINE;
            case ADJUNCT_STAIRS_ENTRANCE -> Y_BASELINE
                + games.brennan.dungeontrain.track.PillarAdjunct.STAIRS.ySize() + EditorLayout.GAP;
            case PILLAR_BOTTOM -> Y_BASELINE;
            case PILLAR_MIDDLE -> Y_BASELINE + PillarSection.BOTTOM.height() + EditorLayout.GAP;
            case PILLAR_TOP -> Y_BASELINE
                + PillarSection.BOTTOM.height() + EditorLayout.GAP
                + PillarSection.MIDDLE.height() + EditorLayout.GAP;
            case DIMENSIONAL_CARRIAGE -> Y_BASELINE;
        };
    }

    /** X column for {@code kind}'s category. */
    public static int categoryX(TrackKind kind) {
        return switch (kind) {
            case TILE -> X_TRACK;
            case TUNNEL_SECTION, TUNNEL_PORTAL -> X_TUNNELS;
            case ADJUNCT_STAIRS, ADJUNCT_STAIRS_ENTRANCE -> X_STAIRS;
            case PILLAR_TOP, PILLAR_MIDDLE, PILLAR_BOTTOM -> X_PILLARS;
            case DIMENSIONAL_CARRIAGE -> X_PORTALS;
        };
    }

    private static boolean containsWithMargin(BlockPos pos, BlockPos origin, Vec3i fp) {
        // +2 Y headroom above cage top so a player who landed on top via the
        // new on-top-by-default teleport still counts as inPlot — see
        // CarriageEditor.plotContaining for the same pattern.
        return pos.getX() >= origin.getX() - 1
            && pos.getX() <= origin.getX() + fp.getX()
            && pos.getY() >= origin.getY() - 1
            && pos.getY() <= origin.getY() + fp.getY() + 2
            && pos.getZ() >= origin.getZ() - 1
            && pos.getZ() <= origin.getZ() + fp.getZ();
    }
}
