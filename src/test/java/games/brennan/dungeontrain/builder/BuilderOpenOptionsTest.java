package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

/**
 * What the Open screen lists, and what it will let you click.
 *
 * <p>Whole Carriage matches New exactly — Stages then saved builds, tagged — because the two screens
 * are asking the same question there. So does the Carriage Room arm's mode split. The interesting
 * divergence is the track modes, which New treats as "authors its thing outright" and Open treats as
 * "listed but not editable yet".</p>
 */
final class BuilderOpenOptionsTest {

    @Test
    @DisplayName("Whole Carriage lists Stages + saved builds, from either carriage mode")
    void wholeCarriageListsStagesAndSavedBuilds() {
        for (BuilderMode mode : new BuilderMode[] {
                BuilderMode.TRAIN_OUTSIDE, BuilderMode.INSIDE_CARRIAGE}) {
            assertEquals(BuilderOpenOptions.OpenSource.STAGES,
                    BuilderOpenOptions.openSourceFor(mode, BuilderNewOptions.SubType.WHOLE_CARRIAGE),
                    "wrong source for " + mode.id());
        }
    }

    @Test
    @DisplayName("Carriage Room means rooms from inside the wall and carriages from outside it")
    void carriageRoomSplitsByMode() {
        // The same split New's copySourceFor makes, for the same reason: from outside the train
        // there is no room in view to name, only the carriages one would go in.
        assertEquals(BuilderOpenOptions.OpenSource.CONTENTS,
                BuilderOpenOptions.openSourceFor(BuilderMode.INSIDE_CARRIAGE,
                        BuilderNewOptions.SubType.CARRIAGE_ROOM));
        assertEquals(BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE,
                BuilderOpenOptions.openSourceFor(BuilderMode.TRAIN_OUTSIDE,
                        BuilderNewOptions.SubType.CARRIAGE_ROOM));
    }

    @Test
    @DisplayName("The two-level lists drill in; the flat ones do not")
    void onlyTwoLevelListsDrillIn() {
        assertTrue(BuilderOpenOptions.drillsIn(BuilderOpenOptions.OpenSource.CONTENTS));
        assertTrue(BuilderOpenOptions.drillsIn(BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE));
        assertTrue(BuilderOpenOptions.drillsIn(BuilderOpenOptions.OpenSource.PORTAL_ROOMS));
        // Kinds, then one kind's templates — the same two levels, for the groups that have them.
        assertTrue(BuilderOpenOptions.drillsIn(BuilderOpenOptions.OpenSource.TRACK_KINDS));

        assertFalse(BuilderOpenOptions.drillsIn(BuilderOpenOptions.OpenSource.STAGES));
        assertFalse(BuilderOpenOptions.drillsIn(BuilderOpenOptions.OpenSource.PARTS));
    }

    @Test
    @DisplayName("Under Carriage Room, a Stage has no photo and the carriages under it do")
    void stageListPhotographsOnlyItsCarriages() {
        // The level decides, not the value: `desert` at the top is a stretch of the game with no
        // file behind it, and one level in every entry is a carriage template that has one.
        assertNull(BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE, "desert", false));
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE, BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE, "windowed", true));

        // The 2-arg form is the top level, so the two must agree there.
        assertNull(BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE, "desert"));
    }

    @Test
    @DisplayName("Only the two-level sources care which level the grid is on")
    void otherSourcesIgnoreTheLevel() {
        for (BuilderOpenOptions.OpenSource source : BuilderOpenOptions.OpenSource.values()) {
            // The sources whose top level is a heading with no file behind it — a stage, a room
            // mode, a track kind — and whose second level is templates that do have one.
            if (source == BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE
                    || source == BuilderOpenOptions.OpenSource.PORTAL_ROOMS
                    || source == BuilderOpenOptions.OpenSource.TRACK_KINDS) {
                continue;
            }
            assertEquals(BuilderOpenOptions.photoKindFor(source, "maze"),
                    BuilderOpenOptions.photoKindFor(source, "maze", true),
                    "drilling in changed the photo store for " + source);
        }
    }

    @Test
    @DisplayName("Parts list parts, and bring the part-kind control with them")
    void partsListParts() {
        assertEquals(BuilderOpenOptions.OpenSource.PARTS,
                BuilderOpenOptions.openSourceFor(BuilderMode.TRAIN_OUTSIDE,
                        BuilderNewOptions.SubType.PARTS));
        assertTrue(BuilderOpenOptions.showsPartKind(BuilderMode.TRAIN_OUTSIDE,
                BuilderNewOptions.SubType.PARTS));
        assertFalse(BuilderOpenOptions.showsPartKind(BuilderMode.TRAIN_OUTSIDE,
                BuilderNewOptions.SubType.WHOLE_CARRIAGE));
    }

    @Test
    @DisplayName("The track modes map to their own stores, whatever the sub type says")
    void trackModesIgnoreSubType() {
        // They have no sub type control at all, so whatever the screen last held must not leak in.
        for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
            assertEquals(BuilderOpenOptions.OpenSource.TRACK_KINDS,
                    BuilderOpenOptions.openSourceFor(BuilderMode.TRACKS_TUNNELS, subType));
            assertEquals(BuilderOpenOptions.OpenSource.PORTAL_ROOMS,
                    BuilderOpenOptions.openSourceFor(BuilderMode.TRAIN_DIMENSIONS, subType));
        }
    }

    @Test
    @DisplayName("Tracks & Tunnels offers all four groups; Train Dimensions offers none")
    void trackGroupsBelongToTracksAndTunnels() {
        assertEquals(List.of(BuilderTrackGroup.values()),
                BuilderOpenOptions.trackGroupsFor(BuilderMode.TRACKS_TUNNELS));
        // One kind of thing, so there is nothing to choose between and no group row to show.
        assertTrue(BuilderOpenOptions.trackGroupsFor(BuilderMode.TRAIN_DIMENSIONS).isEmpty());
        assertTrue(BuilderOpenOptions.trackGroupsFor(BuilderMode.TRAIN_OUTSIDE).isEmpty());
    }

    @Test
    @DisplayName("Each track mode lands on the kind it is about")
    void trackModesHaveTheirOwnDefaultKind() {
        assertEquals(TrackKind.TUNNEL_PORTAL,
                BuilderOpenOptions.defaultTrackKind(BuilderMode.TRAIN_DIMENSIONS));
        assertEquals(TrackKind.TILE,
                BuilderOpenOptions.defaultTrackKind(BuilderMode.TRACKS_TUNNELS));
    }

    @Test
    @DisplayName("Every source is openable, tracks included")
    void everySourceIsOpenable() {
        // The track sources were the exception for as long as there was no stamp or save path for
        // them. Both landed with BuilderTrackPlot, so the exception went with them.
        for (BuilderOpenOptions.OpenSource source : BuilderOpenOptions.OpenSource.values()) {
            assertTrue(BuilderOpenOptions.isOpenable(source), source + " should be openable");
        }
    }

    @Test
    @DisplayName("Every mode agrees with itself about being openable")
    void openableByModeMatchesBySource() {
        for (BuilderMode mode : BuilderMode.values()) {
            for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
                assertEquals(
                        BuilderOpenOptions.isOpenable(BuilderOpenOptions.openSourceFor(mode, subType)),
                        BuilderOpenOptions.isOpenable(mode, subType),
                        "the two isOpenable overloads disagree for " + mode.id() + "/" + subType.id());
            }
        }
    }

    @Test
    @DisplayName("Photo kinds line up with the stores, and are absent where no photo is ever written")
    void photoKindsMatchStores() {
        assertEquals(BuilderPhotoPaths.Kind.CONTENTS, BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.CONTENTS, "mess_hall"));
        assertEquals(BuilderPhotoPaths.Kind.PART, BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.PARTS, "slatted"));

        // A room mode is a heading with nothing to photograph; the rooms under it are templates
        // with a picture beside them, taken when the room was last saved.
        assertNull(BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.PORTAL_ROOMS, "bedrock_lock"));
        assertEquals(BuilderPhotoPaths.Kind.PORTAL_ROOM, BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.PORTAL_ROOMS, "labrynth", true));

        // A track kind is a category rather than a file, so the top level has no photo; one level in
        // the same cells are templates and do.
        assertNull(BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.TRACK_KINDS, "tunnel_section"));
        assertEquals(BuilderPhotoPaths.Kind.TRACK, BuilderOpenOptions.photoKindFor(
                BuilderOpenOptions.OpenSource.TRACK_KINDS, "nethertracks", true));
    }

    @Test
    @DisplayName("In the Whole Carriage list, a saved build has a photo and a Stage does not")
    void wholeCarriageEntriesSplitByTag() {
        String saved = BuilderNewOptions.tagWholeCarriage("my_build");
        String stage = BuilderNewOptions.tagStage("desert");

        assertTrue(BuilderOpenOptions.isSavedBuild(saved));
        assertFalse(BuilderOpenOptions.isSavedBuild(stage));

        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE,
                BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.STAGES, saved));
        // A Stage names a stretch of the game, not a file — there is nothing to photograph.
        assertNull(BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.STAGES, stage));
    }

    @Test
    @DisplayName("Tagged values resolve back to their bare id; untagged lists pass through")
    void bareIdUntagsOnlyWhereTagsExist() {
        assertEquals("my_build", BuilderOpenOptions.bareId(BuilderOpenOptions.OpenSource.STAGES,
                BuilderNewOptions.tagWholeCarriage("my_build")));
        assertEquals("desert", BuilderOpenOptions.bareId(BuilderOpenOptions.OpenSource.STAGES,
                BuilderNewOptions.tagStage("desert")));

        // Only the Whole Carriage list shows two lists at once, so it is the only one that tags.
        // Stripping a prefix off the others would corrupt any id that happened to contain one.
        assertEquals("whole:odd_name", BuilderOpenOptions.bareId(
                BuilderOpenOptions.OpenSource.CONTENTS, "whole:odd_name"));
        assertEquals("", BuilderOpenOptions.bareId(BuilderOpenOptions.OpenSource.PARTS, null));
    }
}
