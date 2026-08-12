package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
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

    /**
     * One ghost block: where it goes, and what it is made of.
     *
     * <p>The state is carried rather than assumed because the ghosts are drawn with the block's own
     * model and texture — a rail has to ghost as a rail, not as a stone brick with a rail's name.
     * The states are {@link TrackPalette}'s, which is what {@code TrackGenerator} paints with when a
     * template has nothing to say about a column, so the modelled ghost is the game's own fallback
     * rather than a guess made here.</p>
     */
    public record Cell(BlockPos pos, BlockState state) {}

    private BuilderTrackGhostShape() {}

    /**
     * The cells to draw for an open template, in world space.
     *
     * <p>Empty for the kinds authored in the corridor — a tile or a tunnel already has the real
     * thing on the plot, so the renderer repeats <em>that</em> down the line instead of a model of
     * it, and gets the template being edited rather than an approximation of it.</p>
     */
    public static List<Cell> cells(TrackKind kind, BoundingBox plot, CarriageDims dims) {
        if (kind == null || plot == null || BuilderTrackPlot.inCorridor(kind)) {
            return List.of();
        }
        List<Cell> cells = new ArrayList<>();
        int columnTop = appendColumn(cells, kind, plot);
        appendLine(cells, kind, plot, columnTop + 1, dims);
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
    private static int appendColumn(List<Cell> cells, TrackKind kind, BoundingBox plot) {
        int top = plot.maxY();
        for (PillarSection section : sectionsAbove(kind)) {
            for (int dy = 1; dy <= section.height(); dy++) {
                for (int x = plot.minX(); x <= plot.maxX(); x++) {
                    for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                        cells.add(new Cell(new BlockPos(x, top + dy, z), TrackPalette.PILLAR));
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
     * <p>Placed against the plot rather than at the world's corridor, because the plot is what it is
     * there to meet — but <em>where</em> against it depends on how the piece attaches. A pillar is
     * under the line and spans the corridor's width, so the line sits centred on top of it. A
     * staircase is beside the line, so the line runs past its edge at the same height.</p>
     *
     * @param bedY the row the bed sits on — one above whatever the column's cap turned out to be
     */
    private static void appendLine(List<Cell> cells, TrackKind kind, BoundingBox plot,
                                   int bedY, CarriageDims dims) {
        int width = Math.max(dims.width(), plot.getZSpan());
        int minZ;
        if (isAdjunct(kind)) {
            // Beside it, not over it. A staircase is stamped alongside the pillar and outside the
            // corridor, leading outward from the line — so the line runs past its inward edge, and
            // drawing it overhead would put the stairs under the track they are meant to climb to.
            minZ = plot.minZ() - width;
        } else {
            int centreZ = (plot.minZ() + plot.maxZ()) / 2;
            minZ = centreZ - width / 2;
        }
        int maxZ = minZ + width - 1;
        // The rails sit one in from each edge of the bed, matching the fallback TrackGenerator
        // paints when a tile template has nothing to say about a column.
        int railNear = minZ + 1;
        int railFar = maxZ - 1;

        int centreX = (plot.minX() + plot.maxX()) / 2;
        for (int x = centreX - LINE_RADIUS_X; x <= centreX + LINE_RADIUS_X; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                cells.add(new Cell(new BlockPos(x, bedY, z), TrackPalette.BED));
            }
            cells.add(new Cell(new BlockPos(x, bedY + 1, railNear), TrackPalette.RAIL));
            if (railFar != railNear) {
                cells.add(new Cell(new BlockPos(x, bedY + 1, railFar), TrackPalette.RAIL));
            }
        }
    }

    /** Whether this kind hangs off the side of a pillar rather than stacking into one. */
    static boolean isAdjunct(TrackKind kind) {
        return kind == TrackKind.ADJUNCT_STAIRS || kind == TrackKind.ADJUNCT_STAIRS_ENTRANCE;
    }
}
