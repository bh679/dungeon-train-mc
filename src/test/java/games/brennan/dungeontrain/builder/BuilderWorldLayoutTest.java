package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
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

    /** Protection is a question about scenery, and scenery is a question about the mode. */
    private static final BuilderMode CARRIAGE_MODE = BuilderMode.TRAIN_OUTSIDE;

    // ---- protection ----

    @Test
    @DisplayName("The platform floor is locked")
    void platformIsProtected() {
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 0, 0), DIMS, CARRIAGE_MODE));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 1, 0), DIMS, CARRIAGE_MODE));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(-150, 1, 149), DIMS, CARRIAGE_MODE));
    }

    @Test
    @DisplayName("The track bed and rails are locked, but only inside the corridor")
    void trackIsProtectedWithinTheCorridor() {
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 2, 0), DIMS, CARRIAGE_MODE));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 3, DIMS.width() - 1), DIMS, CARRIAGE_MODE));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 3, DIMS.width()), DIMS, CARRIAGE_MODE),
                "one block past the corridor is open ground at track height");
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 2, -1), DIMS, CARRIAGE_MODE));
    }

    @Test
    @DisplayName("Train Dimensions protects nothing — there is no scenery, and the room stands in it")
    void voidModeProtectsNothing() {
        // Not a relaxation for its own sake. Y_TRACK_BED is 2 and so is Y_STAND, the row a portal
        // room's floor sits on, and inCorridor spans z in [0, width) — which a room centred on the
        // origin straddles. Protecting those rows here makes part of the room the author just opened
        // unbreakable.
        assertFalse(BuilderWorldLayout.isProtected(
                new BlockPos(0, BuilderWorldLayout.Y_STAND, 0), DIMS, BuilderMode.TRAIN_DIMENSIONS));
        assertFalse(BuilderWorldLayout.isProtected(
                new BlockPos(0, BuilderWorldLayout.Y_BEDROCK, 0), DIMS, BuilderMode.TRAIN_DIMENSIONS));
        assertFalse(BuilderWorldLayout.isProtected(
                new BlockPos(0, BuilderWorldLayout.Y_GRASS, 0), DIMS, BuilderMode.TRAIN_DIMENSIONS));
    }

    @Test
    @DisplayName("The room floor row is exactly the one the track bed used to claim")
    void roomFloorSharesTheTrackBedRow() {
        // The collision that made the bug possible, pinned so a layout change surfaces here rather
        // than as an unbreakable block in-game.
        assertEquals(BuilderWorldLayout.Y_STAND, BuilderWorldLayout.Y_TRACK_BED);
        assertTrue(BuilderWorldLayout.isProtected(
                new BlockPos(0, BuilderWorldLayout.Y_STAND, 0), DIMS, CARRIAGE_MODE),
                "that row is still the track bed in a carriage mode");
    }

    @Test
    @DisplayName("An unknown mode is treated as having scenery")
    void unknownModeKeepsTheProtection() {
        // A null mode is a world whose mode was never recorded, which predates the void mode — so it
        // has a platform, and unlocking it would let someone dig through their own floor.
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 0, 0), DIMS, null));
    }

    @Test
    @DisplayName("Everything from the train floor up is editable — that's the build")
    void carriageHeightIsEditable() {
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, BuilderWorldLayout.TRAIN_Y, 0), DIMS, CARRIAGE_MODE));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 40, 0), DIMS, CARRIAGE_MODE));
    }

    @Test
    @DisplayName("Nothing outside the platform is protected — the void is yours")
    void outsideThePlatformIsUnprotected() {
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(500, 1, 0), DIMS, CARRIAGE_MODE));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 2, 500), DIMS, CARRIAGE_MODE));
    }

    @Test
    @DisplayName("A wider carriage widens the locked corridor with it")
    void corridorFollowsCarriageWidth() {
        CarriageDims wide = CarriageDims.clamp(9, 12, 7);
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 3, 11), wide, CARRIAGE_MODE));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 3, 11), DIMS, CARRIAGE_MODE),
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
