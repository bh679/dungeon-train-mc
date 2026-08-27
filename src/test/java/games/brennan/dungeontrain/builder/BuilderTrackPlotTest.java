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
 * The plot a track-side template is authored on.
 *
 * <p>Positioning within the scene is pinned by {@link BuilderTrackSceneTest}; what is left here is
 * the contract every caller depends on regardless of where the scene puts things — that the box and
 * the footprint agree, and that the box is somewhere a builder can actually stand and work. Three
 * things read this box and must agree exactly or a save writes the wrong blocks: the stamp, the
 * capture, and the ghosts that fill in around it.</p>
 */
final class BuilderTrackPlotTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("Every plot is inside the platform and clear of the world floor")
    void everyPlotIsSomewhereBuildable() {
        for (TrackKind kind : TrackKind.values()) {
            if (!BuilderTrackPlot.isTrackSide(kind)) continue;   // never authored on a plot
            BoundingBox box = BuilderTrackPlot.volume(kind, DIMS);
            // The protection rule refuses to unprotect the bedrock floor whatever else is going on,
            // so a plot reaching it would be one the builder could never finish editing.
            assertTrue(box.minY() > BuilderWorldLayout.Y_BEDROCK, kind.id() + " reaches the world floor");
            assertTrue(BuilderWorldLayout.inPlatform(box.minX(), box.minZ()), kind.id() + " min corner");
            assertTrue(BuilderWorldLayout.inPlatform(box.maxX(), box.maxZ()), kind.id() + " max corner");
        }
    }

    @Test
    @DisplayName("A tile is authored on the viaduct; a tunnel is authored on the ground")
    void tunnelsAreOnTheGroundAndTilesAreNot() {
        // Both wrap the line, and they are still at different heights, because each is the height its
        // own situation happens at: a tile runs over the pillars, and a tunnel is where the line goes
        // *underground* — which the generator only builds at ground level, with a shaft climbing out.
        // Previewing a tunnel up on the viaduct put its mouth on a bridge and its staircase
        // descending into open air.
        assertEquals(BuilderTrackScene.bedY(),
                BuilderTrackPlot.origin(TrackKind.TILE, DIMS).getY());

        int groundBedY = BuilderTrackScene.groundGeometry(DIMS).bedY();
        for (TrackKind kind : new TrackKind[]{TrackKind.TUNNEL_SECTION, TrackKind.TUNNEL_PORTAL}) {
            assertEquals(groundBedY, BuilderTrackPlot.origin(kind, DIMS).getY(), kind.id());
        }
        assertTrue(groundBedY < BuilderTrackScene.bedY(), "the ground line is under the viaduct");
    }

    @Test
    @DisplayName("Corridor kinds are the ones that sit in the line")
    void onlyTrackWrappingKindsGoInTheCorridor() {
        assertTrue(BuilderTrackPlot.inCorridor(TrackKind.TILE));
        assertTrue(BuilderTrackPlot.inCorridor(TrackKind.TUNNEL_SECTION));
        assertTrue(BuilderTrackPlot.inCorridor(TrackKind.TUNNEL_PORTAL));

        assertFalse(BuilderTrackPlot.inCorridor(TrackKind.PILLAR_BOTTOM));
        assertFalse(BuilderTrackPlot.inCorridor(TrackKind.ADJUNCT_STAIRS));
    }

    @Test
    @DisplayName("The footprint is the kind's own, and the box matches it")
    void volumeMatchesFootprint() {
        for (TrackKind kind : TrackKind.values()) {
            if (!BuilderTrackPlot.isTrackSide(kind)) continue;   // never authored on a plot
            Vec3i size = BuilderTrackPlot.footprint(kind, DIMS);
            BoundingBox box = BuilderTrackPlot.volume(kind, DIMS);
            assertEquals(kind.dims(DIMS), size, kind.id());
            assertEquals(size.getX(), box.getXSpan(), kind.id() + " x");
            assertEquals(size.getY(), box.getYSpan(), kind.id() + " y");
            assertEquals(size.getZ(), box.getZSpan(), kind.id() + " z");
        }
    }

}
