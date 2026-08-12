package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builder world's fixed geometry, and the rule deciding what a builder may not edit.
 *
 * <p>Protection is derived from this geometry rather than stored, so these assertions are the
 * only thing standing between "the floor is locked" and "the floor is locked <em>and so is the
 * carriage you're trying to build</em>".</p>
 */
final class BuilderWorldLayoutTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("The platform is exactly 300 wide on both axes")
    void platformIsExactly300Wide() {
        assertEquals(300, BuilderWorldLayout.MAX_XZ - BuilderWorldLayout.MIN_XZ + 1);
        assertEquals(-150, BuilderWorldLayout.MIN_XZ);
        assertEquals(149, BuilderWorldLayout.MAX_XZ);
    }

    @Test
    @DisplayName("inPlatform covers the corners and rejects one block past them")
    void inPlatformCoversCornersOnly() {
        assertTrue(BuilderWorldLayout.inPlatform(0, 0));
        assertTrue(BuilderWorldLayout.inPlatform(-150, -150));
        assertTrue(BuilderWorldLayout.inPlatform(149, 149));
        assertFalse(BuilderWorldLayout.inPlatform(-151, 0));
        assertFalse(BuilderWorldLayout.inPlatform(150, 0));
        assertFalse(BuilderWorldLayout.inPlatform(0, 150));
    }

    @Test
    @DisplayName("Grass sits directly on the bedrock, and the track bed sits on the grass")
    void layerStackIsContiguous() {
        assertEquals(0, BuilderWorldLayout.Y_BEDROCK);
        assertEquals(1, BuilderWorldLayout.Y_GRASS);
        assertEquals(2, BuilderWorldLayout.Y_STAND);
        assertEquals(2, BuilderWorldLayout.Y_TRACK_BED);
        assertEquals(3, BuilderWorldLayout.Y_TRACK_RAIL);
        assertEquals(4, BuilderWorldLayout.TRAIN_Y);
    }

    // ---- protection ----

    @Test
    @DisplayName("The platform floor is locked")
    void platformIsProtected() {
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 0, 0), DIMS));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 1, 0), DIMS));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(-150, 1, 149), DIMS));
    }

    @Test
    @DisplayName("The track bed and rails are locked, but only inside the corridor")
    void trackIsProtectedWithinTheCorridor() {
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 2, 0), DIMS));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 3, DIMS.width() - 1), DIMS));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 3, DIMS.width()), DIMS),
                "one block past the corridor is open ground at track height");
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 2, -1), DIMS));
    }

    @Test
    @DisplayName("Everything from the train floor up is editable — that's the build")
    void carriageHeightIsEditable() {
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, BuilderWorldLayout.TRAIN_Y, 0), DIMS));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 40, 0), DIMS));
    }

    @Test
    @DisplayName("Nothing outside the platform is protected — the void is yours")
    void outsideThePlatformIsUnprotected() {
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(500, 1, 0), DIMS));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 2, 500), DIMS));
    }

    @Test
    @DisplayName("A wider carriage widens the locked corridor with it")
    void corridorFollowsCarriageWidth() {
        CarriageDims wide = CarriageDims.clamp(9, 12, 7);
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 3, 11), wide));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 3, 11), DIMS),
                "the default 7-wide corridor stops well before z=11");
    }

    // ---- train footprint ----

    @Test
    @DisplayName("A multi-carriage run is wrapped in flatbed pads and centred on the origin")
    void multiCarriageRunIsPadded() {
        int length = BuilderWorldLayout.totalTrainLength(3, DIMS);
        assertTrue(BuilderWorldLayout.usesPads(3));
        assertEquals(3 * DIMS.length() + 2 * ((DIMS.length() + 1) / 2), length);
        assertEquals(-length / 2, BuilderWorldLayout.trainStartX(3, DIMS));
    }

    @Test
    @DisplayName("A single carriage gets no pads, matching spawnGroup's groupSize==1 case")
    void singleCarriageHasNoPads() {
        assertFalse(BuilderWorldLayout.usesPads(1));
        assertEquals(DIMS.length(), BuilderWorldLayout.totalTrainLength(1, DIMS));
    }

    @Test
    @DisplayName("Modes carry the carriage counts they were specified with")
    void modeCarriageCounts() {
        assertEquals(3, BuilderMode.TRAIN_OUTSIDE.carriageCount());
        assertEquals(1, BuilderMode.INSIDE_CARRIAGE.carriageCount());
        assertEquals(0, BuilderMode.TRACKS_TUNNELS.carriageCount());
        assertEquals(0, BuilderMode.TRAIN_DIMENSIONS.carriageCount());
    }

    @Test
    @DisplayName("Spawn is on the grass and clear of the track corridor")
    void spawnIsClearOfTheTrain() {
        BlockPos spawn = BuilderWorldLayout.spawnPos(DIMS);
        assertEquals(BuilderWorldLayout.Y_STAND, spawn.getY());
        assertFalse(BuilderWorldLayout.inCorridor(spawn.getZ(), DIMS));
        assertTrue(BuilderWorldLayout.inPlatform(spawn.getX(), spawn.getZ()));
    }
}
