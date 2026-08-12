package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderTrackPlot;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which blocks the builder is told are <em>not</em> theirs to edit.
 *
 * <p>Worth pinning precisely because two things read this answer and must agree: the mixin that
 * drops these blocks from the chunk mesh, and the renderer that draws them back. Disagree in one
 * direction and the world has a hole in it; disagree in the other and a solid block wears a bright
 * smear. The rule is also the whole feature — "is this the track I am editing?" — so the interesting
 * cases are the boundary ones.</p>
 */
final class ClientTrackGhostTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BoundingBox PLOT = BuilderTrackPlot.volume(TrackKind.TILE, DIMS);

    @Test
    @DisplayName("Nothing ghosts while no track template is open")
    void inactiveGhostsNothing() {
        // The gate the chunk mesher hits for every block in every world that isn't this one.
        BlockPos onTheLine = new BlockPos(40, BuilderWorldLayout.Y_TRACK_RAIL, 1);
        assertFalse(ClientTrackGhost.ghosts(false, PLOT, onTheLine));
        assertFalse(ClientTrackGhost.ghosts(false, null, onTheLine));
    }

    @Test
    @DisplayName("Corridor track away from the plot ghosts")
    void corridorTrackOutsideThePlotGhosts() {
        for (int y : new int[]{BuilderWorldLayout.Y_TRACK_BED, BuilderWorldLayout.Y_TRACK_RAIL}) {
            assertTrue(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(PLOT.maxX() + 8, y, 1)),
                    "y=" + y + " down the line");
            assertTrue(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(PLOT.minX() - 8, y, 1)),
                    "y=" + y + " back up the line");
        }
    }

    @Test
    @DisplayName("The plot itself never ghosts — it is the build")
    void thePlotIsSolid() {
        for (int x = PLOT.minX(); x <= PLOT.maxX(); x++) {
            for (int y = PLOT.minY(); y <= PLOT.maxY(); y++) {
                for (int z = PLOT.minZ(); z <= PLOT.maxZ(); z++) {
                    assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(x, y, z)),
                            "the block being edited at " + x + "," + y + "," + z + " went see-through");
                }
            }
        }
    }

    @Test
    @DisplayName("One block past the plot, the line ghosts again")
    void theBoundaryIsExact() {
        int y = BuilderWorldLayout.Y_TRACK_RAIL;
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(PLOT.maxX(), y, 1)));
        assertTrue(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(PLOT.maxX() + 1, y, 1)));
    }

    @Test
    @DisplayName("Only the two track rows ghost — never the floor you stand on")
    void onlyTheTrackRows() {
        int x = PLOT.maxX() + 8;
        // Ghosting the platform would take the ground out from under the builder.
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(x, BuilderWorldLayout.Y_GRASS, 1)));
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(x, BuilderWorldLayout.Y_BEDROCK, 1)));
        // Nor anything above the line — a build in a carriage mode is not this feature's business.
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(x, BuilderWorldLayout.TRAIN_Y, 1)));
    }

    @Test
    @DisplayName("Track beside the corridor, and beyond the platform, is left alone")
    void onlyInsideTheCorridorAndThePlatform() {
        int y = BuilderWorldLayout.Y_TRACK_RAIL;
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(40, y, DIMS.width())),
                "one block past the corridor's far edge");
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(40, y, -1)),
                "one block before its near edge");
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, new BlockPos(BuilderWorldLayout.MAX_XZ + 1, y, 1)),
                "off the end of the platform");
    }

    @Test
    @DisplayName("A null position is not a crash")
    void nullPositionIsSafe() {
        // The mesher hands us whatever it has; a predicate on the hot path must not be the thing
        // that throws.
        assertFalse(ClientTrackGhost.ghosts(true, PLOT, null));
    }
}
