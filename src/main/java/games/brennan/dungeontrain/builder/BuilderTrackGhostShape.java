package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Train Builder draws around an open track template that isn't there in blocks.
 *
 * <p>A track template on its own tells you very little. A pillar section is a slab; what you need to
 * judge is whether the line sitting on top of it looks right, and for a section that isn't the cap
 * that means seeing the rest of the column too. A track tile is four blocks of a line that runs
 * three hundred; what you need to judge is the seam. So the surroundings are drawn — the rest of the
 * column, and the line where it would really be.</p>
 *
 * <p><b>Drawn, never stamped.</b> Nothing here touches the world, which is what makes a ghost a
 * ghost: it cannot be broken, cannot be walked on, cannot end up in the saved template, and cannot
 * confuse the dirty check. That is the whole reason this is a geometry table and not a second call
 * to a placer — the alternative is real blocks you then have to defend from the builder in three
 * separate places.</p>
 *
 * <p>The track's two rows are <b>modelled</b> rather than read from the authored template: a bed
 * across the corridor and rails on it. Templates live behind {@code ServerLevel} and the client
 * cannot see one, which is the same call {@code BuilderGhostSlots} makes for the flatbed pad. A
 * heavily authored track therefore ghosts as its plain shape — under-drawing is the safe direction
 * for a silhouette aid to be wrong in.</p>
 *
 * <p>Pure, so the column arithmetic is testable without a client or a level.</p>
 */
public final class BuilderTrackGhostShape {

    /** How far along X the ghosted line runs either side of the plot. */
    public static final int LINE_RADIUS_X = 48;

    private BuilderTrackGhostShape() {}

    /**
     * The cells to draw for an open template, in world space.
     *
     * <p>Empty for the kinds authored in the corridor — a tile or a tunnel already has the real
     * thing on the plot, so the renderer repeats <em>that</em> down the line instead of a model of
     * it, and gets the template being edited rather than an approximation of it.</p>
     */
    public static List<BlockPos> cells(TrackKind kind, BoundingBox plot, CarriageDims dims) {
        if (kind == null || plot == null || BuilderTrackPlot.inCorridor(kind)) {
            return List.of();
        }
        List<BlockPos> cells = new ArrayList<>();
        int columnTop = appendColumn(cells, kind, plot);
        appendLine(cells, plot, columnTop + 1, dims);
        return cells;
    }

    /**
     * Stack the pillar sections this one doesn't include on top of it.
     *
     * <p>A real column is laid {@code bottom → middles → top} with the cap at {@code bedY - 1}, so
     * what sits above the section you are editing is every section after it in that order. A
     * {@code BOTTOM} therefore gets a middle and a top; a {@code MIDDLE} gets a top; a {@code TOP}
     * gets nothing, because it already is the cap.</p>
     *
     * <p>Modelled as solid slabs of the section's own height across the plot's footprint — the shape
     * {@code TrackPalette.PILLAR} falls back to. Stairs get no column at all: they bolt onto a
     * pillar from the side rather than stacking into one.</p>
     *
     * @return the Y of the column's top row — the plot's own top when nothing was added
     */
    private static int appendColumn(List<BlockPos> cells, TrackKind kind, BoundingBox plot) {
        int top = plot.maxY();
        for (PillarSection section : sectionsAbove(kind)) {
            for (int dy = 1; dy <= section.height(); dy++) {
                for (int x = plot.minX(); x <= plot.maxX(); x++) {
                    for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                        cells.add(new BlockPos(x, top + dy, z));
                    }
                }
            }
            top += section.height();
        }
        return top;
    }

    /** Which sections a real column would stack above this one, in the order they stack. */
    static List<PillarSection> sectionsAbove(TrackKind kind) {
        return switch (kind) {
            case PILLAR_BOTTOM -> List.of(PillarSection.MIDDLE, PillarSection.TOP);
            case PILLAR_MIDDLE -> List.of(PillarSection.TOP);
            // TOP is already the cap; the adjuncts hang off a column rather than stacking into one.
            case PILLAR_TOP, ADJUNCT_STAIRS, ADJUNCT_STAIRS_ENTRANCE -> List.of();
            case TILE, TUNNEL_SECTION, TUNNEL_PORTAL -> List.of();
        };
    }

    /**
     * The line itself: a bed across the corridor with rails on it, running away on both X.
     *
     * <p>Centred on the plot rather than on the world's corridor, because the plot is what it is
     * there to meet — a pillar spans the corridor's width, so the two line up, and a staircase
     * narrower than the corridor still gets a line wide enough to read as one.</p>
     *
     * @param bedY the row the bed sits on — one above whatever the column's cap turned out to be
     */
    private static void appendLine(List<BlockPos> cells, BoundingBox plot, int bedY, CarriageDims dims) {
        int width = Math.max(dims.width(), plot.getZSpan());
        int centreZ = (plot.minZ() + plot.maxZ()) / 2;
        int minZ = centreZ - width / 2;
        int maxZ = minZ + width - 1;
        // The rails sit one in from each edge of the bed, matching the fallback TrackGenerator
        // paints when a tile template has nothing to say about a column.
        int railNear = minZ + 1;
        int railFar = maxZ - 1;

        int centreX = (plot.minX() + plot.maxX()) / 2;
        for (int x = centreX - LINE_RADIUS_X; x <= centreX + LINE_RADIUS_X; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                cells.add(new BlockPos(x, bedY, z));
            }
            cells.add(new BlockPos(x, bedY + 1, railNear));
            if (railFar != railNear) {
                cells.add(new BlockPos(x, bedY + 1, railFar));
            }
        }
    }
}
