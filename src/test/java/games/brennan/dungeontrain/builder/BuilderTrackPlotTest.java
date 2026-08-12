package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where each track-side template is authored, and whether that spot is somewhere a builder can
 * actually stand and work.
 *
 * <p>Three things have to agree about this box or a save writes the wrong blocks: the stamp, the
 * protection carve-out, and the capture. They agree by all asking this class, so what is worth
 * testing is the class's own answers — that the tile lands in phase with the corridor, that the
 * free-standing kinds land clear of it, and that nothing lands below the world floor.</p>
 */
final class BuilderTrackPlotTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("The tile plot sits on the tile grid, in phase with the corridor around it")
    void tileIsAlignedToTheTileGrid() {
        BlockPos origin = BuilderTrackPlot.origin(TrackKind.TILE, DIMS);
        assertEquals(0, Math.floorMod(origin.getX(), TrackPlacer.TILE_LENGTH),
                "off the grid, so the plot would be half a tile out of phase with the track");
        assertEquals(BuilderWorldLayout.Y_TRACK_BED, origin.getY(),
                "the tile is the bed and rail rows; anywhere else is not the track");
    }

    @Test
    @DisplayName("A track tile fills the corridor exactly")
    void tileSpansTheCorridor() {
        BoundingBox box = BuilderTrackPlot.volume(TrackKind.TILE, DIMS);
        // The tile's footprint is width-derived, so its Z span is the corridor's by construction —
        // this pins the origin that turns that span into the right stretch of world.
        assertEquals(0, box.minZ());
        assertEquals(DIMS.width() - 1, box.maxZ());
        assertEquals(BuilderWorldLayout.Y_TRACK_RAIL, box.maxY());
    }

    @Test
    @DisplayName("Corridor kinds wrap the track; the free-standing ones stand beside it")
    void onlyTrackWrappingKindsGoInTheCorridor() {
        assertTrue(BuilderTrackPlot.inCorridor(TrackKind.TILE));
        assertTrue(BuilderTrackPlot.inCorridor(TrackKind.TUNNEL_SECTION));
        assertTrue(BuilderTrackPlot.inCorridor(TrackKind.TUNNEL_PORTAL));

        assertFalse(BuilderTrackPlot.inCorridor(TrackKind.PILLAR_BOTTOM));
        assertFalse(BuilderTrackPlot.inCorridor(TrackKind.ADJUNCT_STAIRS));
    }

    @Test
    @DisplayName("A tunnel is centred on the track it arches over")
    void tunnelIsCentredOnTheTrack() {
        BoundingBox box = BuilderTrackPlot.volume(TrackKind.TUNNEL_PORTAL, DIMS);
        double trackCentre = BuilderWorldLayout.trackCenterZ(DIMS);
        double boxCentre = (box.minZ() + box.maxZ() + 1) / 2.0;
        assertTrue(Math.abs(trackCentre - boxCentre) <= 0.5,
                "tunnel centre " + boxCentre + " is not on the track centre " + trackCentre);
    }

    @Test
    @DisplayName("Free-standing plots clear the corridor entirely")
    void pillarsAndStairsStandClearOfTheCorridor() {
        for (TrackKind kind : new TrackKind[]{
                TrackKind.PILLAR_BOTTOM, TrackKind.PILLAR_MIDDLE, TrackKind.PILLAR_TOP,
                TrackKind.ADJUNCT_STAIRS, TrackKind.ADJUNCT_STAIRS_ENTRANCE}) {
            BoundingBox box = BuilderTrackPlot.volume(kind, DIMS);
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                assertFalse(BuilderWorldLayout.inCorridor(z, DIMS),
                        kind.id() + " overlaps the corridor at z=" + z);
            }
            assertEquals(BuilderWorldLayout.Y_STAND, box.minY(),
                    kind.id() + " should stand on the platform, not float or sink into it");
        }
    }

    @Test
    @DisplayName("No plot reaches the bedrock floor")
    void nothingIsAuthoredAtTheWorldFloor() {
        // The protection carve-out relies on this: it refuses to unprotect Y_BEDROCK whatever the
        // plot says, and that refusal is only free if no plot wanted that row in the first place.
        for (TrackKind kind : TrackKind.values()) {
            assertTrue(BuilderTrackPlot.volume(kind, DIMS).minY() > BuilderWorldLayout.Y_BEDROCK,
                    kind.id() + " reaches the bedrock floor");
        }
    }

    @Test
    @DisplayName("The footprint is the kind's own, and the box matches it")
    void volumeMatchesFootprint() {
        for (TrackKind kind : TrackKind.values()) {
            Vec3i size = BuilderTrackPlot.footprint(kind, DIMS);
            BoundingBox box = BuilderTrackPlot.volume(kind, DIMS);
            assertEquals(kind.dims(DIMS), size, kind.id());
            assertEquals(size.getX(), box.getXSpan(), kind.id() + " x");
            assertEquals(size.getY(), box.getYSpan(), kind.id() + " y");
            assertEquals(size.getZ(), box.getZSpan(), kind.id() + " z");
        }
    }

    @Test
    @DisplayName("The viewing spot is on the platform, outside the plot, looking at it")
    void viewPosStandsBackFromThePlot() {
        for (TrackKind kind : TrackKind.values()) {
            BlockPos view = BuilderTrackPlot.viewPos(kind, DIMS);
            BoundingBox box = BuilderTrackPlot.volume(kind, DIMS);
            assertEquals(BuilderWorldLayout.Y_STAND, view.getY(), kind.id());
            assertTrue(view.getZ() > box.maxZ(), kind.id() + " would spawn the builder inside it");
            assertTrue(BuilderWorldLayout.inPlatform(view.getX(), view.getZ()),
                    kind.id() + " would spawn the builder off the edge of the world");
        }
    }
}
