package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stretch of line the builder previews, and where each piece sits in it.
 *
 * <p>The whole value of this preview is that it agrees with what the world generator would build, so
 * the assertions worth making are the ones that would catch it drifting: the layout numbers must be
 * the generator's own answers, and each kind's plot must land where that kind really lives. A wrong
 * plot origin is the dangerous one — it is what the save captures from.</p>
 */
final class BuilderTrackSceneTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("The layout numbers are the generator's, not a second copy of them")
    void layoutComesFromTheGenerator() {
        int h = BuilderTrackScene.COLUMN_HEIGHT;
        assertEquals(TrackGenerator.computeSpacing(h), BuilderTrackScene.spacing());
        assertEquals(TrackGenerator.computeThickness(BuilderTrackScene.spacing()),
                BuilderTrackScene.thickness());
        assertArrayEquals(TrackGenerator.archProfile(h), BuilderTrackScene.archProfile());
    }

    @Test
    @DisplayName("The column height clears all three sections and earns a full arch")
    void theHeightIsWorthPreviewing() {
        // Both are the reason the constant is what it is: a shorter column would hide a section or
        // degrade the arch to a stub, and a preview that shows neither is worse than none.
        assertTrue(BuilderTrackScene.COLUMN_HEIGHT
                        > PillarSection.BOTTOM.height() + PillarSection.TOP.height(),
                "no room for a middle section");
        assertEquals(6, BuilderTrackScene.archProfile().length, "not the tall arch");
    }

    @Test
    @DisplayName("The bed sits one row above the column's cap")
    void theBedSitsOnTheColumns() {
        assertEquals(BuilderTrackScene.groundY() + BuilderTrackScene.COLUMN_HEIGHT + 1,
                BuilderTrackScene.bedY());
        assertEquals(BuilderTrackScene.bedY() + 1, BuilderTrackScene.railY());

        // And the geometry the ghosts and the plot both read agrees with that.
        TrackGeometry g = BuilderTrackScene.geometry(DIMS);
        assertEquals(BuilderTrackScene.bedY(), g.bedY());
        assertEquals(BuilderTrackScene.railY(), g.railY());
    }

    @Test
    @DisplayName("Columns stand on the generator's spacing grid, and the edited one is on it")
    void columnsAreOnTheGrid() {
        List<Integer> centres = BuilderTrackScene.pillarCentresX();
        assertFalse(centres.isEmpty());
        for (int centre : centres) {
            assertEquals(0, Math.floorMod(centre, BuilderTrackScene.spacing()),
                    centre + " is off the spacing grid");
        }
        assertTrue(centres.contains(BuilderTrackPlot.editedColumnCentreX()),
                "the column the builder edits is not one of the real ones");
    }

    @Test
    @DisplayName("A column's footprint is its thickness, biased the way the generator biases it")
    void columnFootprint() {
        int centre = BuilderTrackPlot.editedColumnCentreX();
        int span = BuilderTrackScene.columnMaxX(centre) - BuilderTrackScene.columnMinX(centre) + 1;
        assertEquals(BuilderTrackScene.thickness(), span);
    }

    @Test
    @DisplayName("Each pillar section's plot is its own rows of the column")
    void pillarPlotsStackIntoOneColumn() {
        BoundingBox bottom = BuilderTrackPlot.volume(TrackKind.PILLAR_BOTTOM, DIMS);
        BoundingBox middle = BuilderTrackPlot.volume(TrackKind.PILLAR_MIDDLE, DIMS);
        BoundingBox top = BuilderTrackPlot.volume(TrackKind.PILLAR_TOP, DIMS);

        // Bottom on the ground, top hanging from the cap — placePillarSlice's own arrangement.
        assertEquals(BuilderTrackScene.groundY(), bottom.minY());
        assertEquals(BuilderTrackScene.bedY() - 1, top.maxY());
        assertEquals(bottom.maxY() + 1, middle.minY(), "the middle should follow the bottom");

        // All three in the same column, so editing any of them puts you inside one structure.
        for (BoundingBox box : List.of(bottom, middle, top)) {
            assertEquals(BuilderTrackScene.columnMinX(BuilderTrackPlot.editedColumnCentreX()),
                    box.minX());
        }
    }

    @Test
    @DisplayName("A track tile's plot is part of the line, on the tile grid")
    void tilePlotIsInTheLine() {
        BoundingBox tile = BuilderTrackPlot.volume(TrackKind.TILE, DIMS);
        assertEquals(BuilderTrackScene.bedY(), tile.minY());
        assertEquals(BuilderTrackScene.railY(), tile.maxY());
        assertEquals(0, Math.floorMod(tile.minX(), games.brennan.dungeontrain.track.TrackPlacer.TILE_LENGTH),
                "off the tile grid, so it would sit out of phase with the line");
    }

    @Test
    @DisplayName("A staircase's plot hangs off the side of the line, reaching deck height")
    void stairsPlotIsBesideTheLine() {
        BoundingBox stairs = BuilderTrackPlot.volume(TrackKind.ADJUNCT_STAIRS, DIMS);
        TrackGeometry g = BuilderTrackScene.geometry(DIMS);

        assertTrue(stairs.minZ() > g.trackZMax(), "a staircase must not sit over the line");
        assertEquals(g.trackZMax() + 1, stairs.minZ(), "flush against the corridor");
        assertEquals(BuilderTrackScene.stairsTopY(), stairs.maxY(), "should reach deck height");
        assertEquals(PillarAdjunct.STAIRS.ySize(), stairs.getYSpan());
    }

    @Test
    @DisplayName("Staircases are spaced the generator's way, with the side alternating")
    void stairsFollowTheAnchorGrid() {
        long seed = 12345L;
        List<BuilderTrackScene.Stairs> stairs = BuilderTrackScene.stairs(seed);
        assertFalse(stairs.isEmpty(), "a 300-block platform should hold at least one staircase");
        for (int i = 1; i < stairs.size(); i++) {
            assertTrue(stairs.get(i).x() > stairs.get(i - 1).x(), "anchors should march along X");
            // The side flips per anchor slot, which is what stops a long line growing a staircase
            // wall down one side.
            assertTrue(stairs.get(i).minusZ() != stairs.get(i - 1).minusZ(),
                    "consecutive staircases should be on opposite sides");
        }
    }

    @Test
    @DisplayName("The view pose stands level with the plot, not down on the platform")
    void viewPoseIsAtTheScene() {
        for (TrackKind kind : TrackKind.values()) {
            BlockPos view = BuilderTrackPlot.viewPos(kind, DIMS);
            BoundingBox plot = BuilderTrackPlot.volume(kind, DIMS);
            // Dropped on the grass under a column twelve blocks up, you would be looking at the
            // underside of a bridge and wondering where your template went.
            assertEquals(plot.minY(), view.getY(), kind.id());
            assertTrue(view.getZ() > plot.maxZ(), kind.id() + ": would spawn inside the plot");
        }
    }
}
