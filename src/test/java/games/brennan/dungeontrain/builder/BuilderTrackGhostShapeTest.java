package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the ghosted line and column land around an open track template.
 *
 * <p>The arithmetic worth pinning is the stack: a real column is laid {@code bottom → middles → top}
 * with the bed one row above the cap, so which sections a section gets above it — and therefore how
 * high the line floats — is a fact about the game, not a rendering preference. Get it wrong and the
 * ghost quietly teaches a builder the wrong join.</p>
 */
final class BuilderTrackGhostShapeTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    private static BoundingBox plotFor(TrackKind kind) {
        return BuilderTrackPlot.volume(kind, DIMS);
    }

    private static List<BlockPos> positions(List<BuilderTrackGhostShape.Cell> cells) {
        return cells.stream().map(BuilderTrackGhostShape.Cell::pos).collect(Collectors.toList());
    }

    private static int lowestGhostY(List<BuilderTrackGhostShape.Cell> cells) {
        return cells.stream().mapToInt(c -> c.pos().getY()).min().orElseThrow();
    }

    private static int highestGhostY(List<BuilderTrackGhostShape.Cell> cells) {
        return cells.stream().mapToInt(c -> c.pos().getY()).max().orElseThrow();
    }

    @Test
    @DisplayName("A column section gets exactly the sections a real column stacks above it")
    void theColumnStacksInOrder() {
        assertEquals(List.of(PillarSection.MIDDLE, PillarSection.TOP),
                BuilderTrackGhostShape.sectionsAbove(TrackKind.PILLAR_BOTTOM));
        assertEquals(List.of(PillarSection.TOP),
                BuilderTrackGhostShape.sectionsAbove(TrackKind.PILLAR_MIDDLE));
        // Already the cap — there is nothing above a top but the line itself.
        assertTrue(BuilderTrackGhostShape.sectionsAbove(TrackKind.PILLAR_TOP).isEmpty());
        // Stairs bolt onto a column from the side rather than stacking into one.
        assertTrue(BuilderTrackGhostShape.sectionsAbove(TrackKind.ADJUNCT_STAIRS).isEmpty());
        assertTrue(BuilderTrackGhostShape.sectionsAbove(TrackKind.ADJUNCT_STAIRS_ENTRANCE).isEmpty());
    }

    @Test
    @DisplayName("The bed lands one row above the column's cap, whichever section is open")
    void theBedSitsOnTheColumn() {
        for (TrackKind kind : new TrackKind[]{
                TrackKind.PILLAR_BOTTOM, TrackKind.PILLAR_MIDDLE, TrackKind.PILLAR_TOP}) {
            BoundingBox plot = plotFor(kind);
            int stacked = BuilderTrackGhostShape.sectionsAbove(kind).stream()
                    .mapToInt(PillarSection::height).sum();
            List<BuilderTrackGhostShape.Cell> cells = BuilderTrackGhostShape.cells(kind, plot, DIMS);

            int bedY = plot.maxY() + stacked + 1;
            assertTrue(positions(cells).contains(new BlockPos(plot.minX(), bedY, plot.minZ())),
                    kind.id() + ": no bed at y=" + bedY);
            // And the rails one above that — the top of everything drawn.
            assertEquals(bedY + 1, highestGhostY(cells), kind.id() + ": rails should cap the ghost");
        }
    }

    @Test
    @DisplayName("The ghosted column starts immediately above the plot, with no gap")
    void theColumnIsContinuous() {
        BoundingBox plot = plotFor(TrackKind.PILLAR_BOTTOM);
        List<BuilderTrackGhostShape.Cell> cells =
                BuilderTrackGhostShape.cells(TrackKind.PILLAR_BOTTOM, plot, DIMS);
        // A gap here would read as two separate objects rather than one column.
        assertEquals(plot.maxY() + 1, lowestGhostY(cells));
        int capY = plot.maxY() + PillarSection.MIDDLE.height() + PillarSection.TOP.height();
        for (int y = plot.maxY() + 1; y <= capY; y++) {
            final int row = y;
            assertTrue(cells.stream().anyMatch(c -> c.pos().getY() == row),
                    "column has a hole at y=" + row);
        }
    }

    @Test
    @DisplayName("Stairs get the line at the top of the plot and no column under it")
    void stairsGetALineButNoColumn() {
        BoundingBox plot = plotFor(TrackKind.ADJUNCT_STAIRS);
        List<BuilderTrackGhostShape.Cell> cells =
                BuilderTrackGhostShape.cells(TrackKind.ADJUNCT_STAIRS, plot, DIMS);
        assertFalse(cells.isEmpty());
        // Nothing between the plot and the line: the first thing drawn is the bed.
        assertEquals(plot.maxY() + 1, lowestGhostY(cells));
        assertEquals(plot.maxY() + 2, highestGhostY(cells));
    }

    @Test
    @DisplayName("The stairs' line runs beside them, not over them")
    void stairsGetTheLineToOneSide() {
        BoundingBox plot = plotFor(TrackKind.ADJUNCT_STAIRS);
        List<BuilderTrackGhostShape.Cell> cells =
                BuilderTrackGhostShape.cells(TrackKind.ADJUNCT_STAIRS, plot, DIMS);
        int maxGhostZ = cells.stream().mapToInt(c -> c.pos().getZ()).max().orElseThrow();

        // Worldgen stamps a staircase alongside the pillar, outside the corridor, leading outward
        // from the line — so nothing of the line may sit over the plot's own footprint, or the
        // stairs would be under the track they exist to climb to.
        assertTrue(maxGhostZ < plot.minZ(),
                "line reaches z=" + maxGhostZ + ", which is over the plot starting at " + plot.minZ());
        assertEquals(plot.minZ() - 1, maxGhostZ, "the line should run right up against the stairs");
    }

    @Test
    @DisplayName("A pillar's line does sit over it — it is what holds the line up")
    void pillarsGetTheLineOverhead() {
        BoundingBox plot = plotFor(TrackKind.PILLAR_TOP);
        List<BuilderTrackGhostShape.Cell> cells =
                BuilderTrackGhostShape.cells(TrackKind.PILLAR_TOP, plot, DIMS);
        int centreZ = (plot.minZ() + plot.maxZ()) / 2;
        assertTrue(cells.stream().anyMatch(c -> c.pos().getZ() == centreZ),
                "nothing drawn over the middle of the pillar");
    }

    @Test
    @DisplayName("The line runs away on both sides of the plot")
    void theLineRunsBothWays() {
        BoundingBox plot = plotFor(TrackKind.PILLAR_TOP);
        List<BuilderTrackGhostShape.Cell> cells =
                BuilderTrackGhostShape.cells(TrackKind.PILLAR_TOP, plot, DIMS);
        int centreX = (plot.minX() + plot.maxX()) / 2;
        assertTrue(cells.stream().anyMatch(c -> c.pos().getX() < centreX),
                "nothing drawn back up the line");
        assertTrue(cells.stream().anyMatch(c -> c.pos().getX() > centreX),
                "nothing drawn down the line");
        assertEquals(centreX - BuilderTrackGhostShape.LINE_RADIUS_X,
                cells.stream().mapToInt(c -> c.pos().getX()).min().orElseThrow());
    }

    @Test
    @DisplayName("Corridor kinds model nothing — the real plot is repeated instead")
    void corridorKindsAreDrawnFromThePlotItself() {
        // A tile and a tunnel are authored in the corridor, so the template is already on the plot
        // and the renderer repeats that rather than an approximation of it.
        for (TrackKind kind : new TrackKind[]{
                TrackKind.TILE, TrackKind.TUNNEL_SECTION, TrackKind.TUNNEL_PORTAL}) {
            assertTrue(BuilderTrackGhostShape.cells(kind, plotFor(kind), DIMS).isEmpty(), kind.id());
        }
    }

    @Test
    @DisplayName("No kind or no plot draws nothing rather than throwing")
    void missingInputsAreSafe() {
        assertTrue(BuilderTrackGhostShape.cells(null, plotFor(TrackKind.PILLAR_TOP), DIMS).isEmpty());
        assertTrue(BuilderTrackGhostShape.cells(TrackKind.PILLAR_TOP, null, DIMS).isEmpty());
    }
}
