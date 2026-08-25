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

    /** Protection is a question about scenery, and scenery is a question about the mode. */
    private static final BuilderMode CARRIAGE_MODE = BuilderMode.TRAIN_OUTSIDE;

    // ---- protection ----

    @Test
    @DisplayName("The platform floor is locked")
    void platformIsProtected() {
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 0, 0), CARRIAGE_MODE));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 1, 0), CARRIAGE_MODE));
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(-150, 1, 149), CARRIAGE_MODE));
        // And nothing outside it, which is where a room's own surroundings live.
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(-151, 1, 0), CARRIAGE_MODE));
    }

    @Test
    @DisplayName("The track rows are open — the line is a structure now, not the frame")
    void trackRowsAreNotProtected() {
        // The regression this replaces: the scene stands its pillar columns from Y_STAND, which is
        // also the bed row, so locking those rows locked the bottom of every column somebody tried
        // to author. The line is drawn rather than stamped unless Solid is asked for, nothing saves
        // it, and BuilderStructureStamp puts it back — so there is nothing left to defend.
        assertFalse(BuilderWorldLayout.isProtected(
                new BlockPos(0, BuilderWorldLayout.Y_TRACK_BED, 1), CARRIAGE_MODE));
        assertFalse(BuilderWorldLayout.isProtected(
                new BlockPos(0, BuilderWorldLayout.Y_TRACK_RAIL, 1), CARRIAGE_MODE));
    }

    @Test
    @DisplayName("Train Dimensions protects nothing — there is no floor to hole")
    void voidModeProtectsNothing() {
        for (int y : new int[]{BuilderWorldLayout.Y_BEDROCK, BuilderWorldLayout.Y_GRASS,
                BuilderWorldLayout.Y_STAND}) {
            assertFalse(BuilderWorldLayout.isProtected(
                    new BlockPos(0, y, 0), BuilderMode.TRAIN_DIMENSIONS));
        }
    }

    @Test
    @DisplayName("The room floor row sits one above the ground, where nothing is locked")
    void roomFloorIsEditable() {
        // Y_STAND is where a portal room's floor lands, and it must stay editable in every mode —
        // the author has to be able to edit the thing they opened.
        assertEquals(BuilderWorldLayout.Y_GRASS + 1, BuilderWorldLayout.Y_STAND);
        assertFalse(BuilderWorldLayout.isProtected(
                new BlockPos(0, BuilderWorldLayout.Y_STAND, 0), CARRIAGE_MODE));
    }

    @Test
    @DisplayName("An unknown mode is treated as having a platform")
    void unknownModeKeepsTheProtection() {
        // A null mode is a world whose mode was never recorded, which predates the void mode — so it
        // has a platform, and unlocking it would let someone dig through their own floor.
        assertTrue(BuilderWorldLayout.isProtected(new BlockPos(0, 0, 0), null));
    }

    @Test
    @DisplayName("Everything from the train floor up is editable — that's the build")
    void carriageHeightIsEditable() {
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, BuilderWorldLayout.TRAIN_Y, 0), CARRIAGE_MODE));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 40, 0), CARRIAGE_MODE));
    }

    @Test
    @DisplayName("Nothing outside the platform is protected — the void is yours")
    void outsideThePlatformIsUnprotected() {
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(500, 1, 0), CARRIAGE_MODE));
        assertFalse(BuilderWorldLayout.isProtected(new BlockPos(0, 1, 500), CARRIAGE_MODE));
    }

    @Test
    @DisplayName("Protection no longer depends on carriage width — only the ground is locked")
    void protectionIsIndependentOfCarriageWidth() {
        // It used to: the locked corridor was as wide as the carriage. The line is a structure now,
        // so the rule has nothing left in it that a dimension could change, which is why the method
        // no longer takes one.
        for (int z = 0; z <= 12; z++) {
            assertFalse(BuilderWorldLayout.isProtected(
                    new BlockPos(0, BuilderWorldLayout.Y_TRACK_RAIL, z), CARRIAGE_MODE));
            assertTrue(BuilderWorldLayout.isProtected(
                    new BlockPos(0, BuilderWorldLayout.Y_GRASS, z), CARRIAGE_MODE));
        }
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
    @DisplayName("Only a whole carriage parks the mode's full run; one template parks one carriage")
    void parkedCarriagesFollowsTheSubType() {
        // The silhouette case: three carriages is what Train Outside exists to show you.
        assertEquals(3, BuilderWorldLayout.parkedCarriages(
                BuilderMode.TRAIN_OUTSIDE, BuilderNewOptions.SubType.WHOLE_CARRIAGE));
        // One template is one carriage — three copies would be three answers to a one-answer
        // question, and it reads as a generic train rather than as the thing you opened.
        assertEquals(1, BuilderWorldLayout.parkedCarriages(
                BuilderMode.TRAIN_OUTSIDE, BuilderNewOptions.SubType.CARRIAGE_ROOM));
        assertEquals(1, BuilderWorldLayout.parkedCarriages(
                BuilderMode.TRAIN_OUTSIDE, BuilderNewOptions.SubType.PARTS));
        // Inside the wall there was only ever one carriage, so nothing changes there.
        for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
            assertEquals(1, BuilderWorldLayout.parkedCarriages(BuilderMode.INSIDE_CARRIAGE, subType));
        }
    }

    @Test
    @DisplayName("A world with no sub type yet keeps the mode's own count")
    void parkedCarriagesDefaultsToTheModeBeforeAnySelection() {
        // setupIfNeeded's path: a title-screen click, before New or Open has narrowed it. Reading
        // this as "one carriage" would silently shrink every freshly-created builder world.
        assertEquals(3, BuilderWorldLayout.parkedCarriages(BuilderMode.TRAIN_OUTSIDE, null));
        assertEquals(1, BuilderWorldLayout.parkedCarriages(BuilderMode.INSIDE_CARRIAGE, null));
    }

    @Test
    @DisplayName("The carriage-less modes park none whatever is being authored")
    void parkedCarriagesStaysZeroForTheTrackModes() {
        for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
            assertEquals(0, BuilderWorldLayout.parkedCarriages(BuilderMode.TRACKS_TUNNELS, subType));
            assertEquals(0, BuilderWorldLayout.parkedCarriages(BuilderMode.TRAIN_DIMENSIONS, subType));
        }
        assertEquals(0, BuilderWorldLayout.parkedCarriages(BuilderMode.TRACKS_TUNNELS, null));
        assertEquals(0, BuilderWorldLayout.parkedCarriages(null, null));
    }

    @Test
    @DisplayName("Both carriage modes ghost the same run, however much of it is real")
    void ghostGroupIsTheSameTrainFromEitherSide() {
        // A train reads the same from either side of the wall. Sizing the ghosts from the parked
        // count instead is what left Inside Carriage — which parks one by definition — drawing none.
        assertEquals(BuilderWorldLayout.OUTSIDE_CARRIAGES,
                BuilderWorldLayout.ghostGroupCarriages(BuilderMode.TRAIN_OUTSIDE));
        assertEquals(BuilderWorldLayout.OUTSIDE_CARRIAGES,
                BuilderWorldLayout.ghostGroupCarriages(BuilderMode.INSIDE_CARRIAGE));
        assertTrue(BuilderWorldLayout.ghostGroupCarriages(BuilderMode.INSIDE_CARRIAGE)
                > BuilderWorldLayout.parkedCarriages(BuilderMode.INSIDE_CARRIAGE, null),
                "an inside build has to have slots left over, or there is nothing to ghost");
    }

    @Test
    @DisplayName("The carriage-less modes imply no train around them")
    void ghostGroupIsZeroForTheTrackModes() {
        assertEquals(0, BuilderWorldLayout.ghostGroupCarriages(BuilderMode.TRACKS_TUNNELS));
        assertEquals(0, BuilderWorldLayout.ghostGroupCarriages(BuilderMode.TRAIN_DIMENSIONS));
        assertEquals(0, BuilderWorldLayout.ghostGroupCarriages(null));
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
