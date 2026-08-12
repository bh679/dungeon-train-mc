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
    @DisplayName("Every block of a pillar-bottom plot is the builder's to edit")
    void thePillarBottomIsFullyEditable() {
        // The bug this pins: the column stands from the bed row, so the track-row rule locked the
        // lowest two rows of the plot and there was no way to finish the template.
        BoundingBox plot = BuilderTrackPlot.volume(TrackKind.PILLAR_BOTTOM, DIMS);
        for (int y = plot.minY(); y <= plot.maxY(); y++) {
            for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                assertFalse(BuilderWorldLayout.isProtected(new BlockPos(plot.minX(), y, z), DIMS, true),
                        "locked at y=" + y + " z=" + z);
            }
        }
    }

    @Test
    @DisplayName("A stairs entrance caps a shaft over a ground-level line, not the elevated one")
    void entranceSitsOnItsShaft() {
        BoundingBox entrance = BuilderTrackPlot.volume(TrackKind.ADJUNCT_STAIRS_ENTRANCE, DIMS);
        TrackGeometry ground = BuilderTrackScene.groundGeometry(DIMS);

        // The down-stairs case: the line is underground, so the entrance belongs above a shaft that
        // climbs out of it — not on the bridge every other kind is previewed against.
        assertEquals(BuilderWorldLayout.Y_TRACK_BED, ground.bedY());
        assertTrue(entrance.minY() > BuilderTrackScene.shaftFloorY(DIMS),
                "the entrance should be up the shaft, not down in the tunnel");
        // Placed by the generator's own call, so the overlap into the staircase is the same block
        // on both sides rather than two implementations agreeing by luck.
        assertEquals(TrackGenerator.downStairsEntranceOrigin(
                        BuilderTrackScene.shaftMinX(BuilderTrackPlot.editedColumnCentreX()),
                        BuilderTrackScene.shaftMinZ(DIMS),
                        BuilderTrackScene.shaftSurfaceY(DIMS)),
                new BlockPos(entrance.minX(), entrance.minY(), entrance.minZ()));
        // Its lowest rows sit inside the top of the shaft rather than above it.
        assertTrue(entrance.minY() <= BuilderTrackScene.shaftTopY(DIMS),
                "the entrance floats over the staircase instead of capping it");
    }

    @Test
    @DisplayName("The staircase runs two rows up inside the entrance")
    void theStaircaseReachesIntoTheEntrance() {
        BoundingBox entrance = BuilderTrackPlot.volume(TrackKind.ADJUNCT_STAIRS_ENTRANCE, DIMS);
        int shaftTop = BuilderTrackScene.shaftTopY(DIMS);

        // The entrance is dropped ENTRANCE_OVERLAP_Y so its lowest rows sit inside the top of the
        // stairs — the two structures meet rather than stack. Two rows exactly: shaftTop and the one
        // below it.
        assertTrue(shaftTop >= entrance.minY(), "the stairs stop below the entrance");
        assertEquals(2, shaftTop - entrance.minY() + 1,
                "the overlap should be exactly the generator's ENTRANCE_OVERLAP_Y rows");
    }

    @Test
    @DisplayName("The shaft runs floor-to-surface the generator's way")
    void theShaftUsesTheGeneratorsRows() {
        assertEquals(TrackGenerator.downStairsFloorY(BuilderTrackScene.groundGeometry(DIMS)),
                BuilderTrackScene.shaftFloorY(DIMS));
        assertEquals(TrackGenerator.downStairsTopInclusive(BuilderTrackScene.shaftSurfaceY(DIMS)),
                BuilderTrackScene.shaftTopY(DIMS));
        assertEquals(TrackGenerator.downStairsOriginZ(false, BuilderTrackScene.groundGeometry(DIMS)),
                BuilderTrackScene.shaftMinZ(DIMS));
    }

    @Test
    @DisplayName("The shaft climbs a whole number of stair stamps")
    void theShaftIsAWholeNumberOfStamps() {
        int climb = BuilderTrackScene.shaftSurfaceY(DIMS) - BuilderTrackScene.shaftFloorY(DIMS);
        assertEquals(0, climb % PillarAdjunct.STAIRS.ySize(),
                "a part stamp would preview a clipped staircase the generator never builds");
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
