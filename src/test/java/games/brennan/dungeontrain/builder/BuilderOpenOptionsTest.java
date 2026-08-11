package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Open screen lists, and what it will let you click.
 *
 * <p>The interesting cases are the two places Open deliberately disagrees with New: the Carriage
 * Room arm, which New makes mode-dependent and Open does not, and the track modes, which New treats
 * as "authors its thing outright" and Open treats as "listed but not yet editable".</p>
 */
final class BuilderOpenOptionsTest {

    @Test
    @DisplayName("Whole Carriage lists carriages, from either carriage mode")
    void wholeCarriageListsCarriages() {
        for (BuilderMode mode : new BuilderMode[] {
                BuilderMode.TRAIN_OUTSIDE, BuilderMode.INSIDE_CARRIAGE}) {
            assertEquals(BuilderOpenOptions.OpenSource.CARRIAGES,
                    BuilderOpenOptions.openSourceFor(mode, BuilderNewOptions.SubType.WHOLE_CARRIAGE),
                    "wrong source for " + mode.id());
        }
    }

    @Test
    @DisplayName("A room is a room from either side of the carriage wall")
    void carriageRoomIsModeIndependent() {
        // New's copySourceFor splits here — CONTENTS from inside, CARRIAGES from outside — because
        // it is asking which carriage a *new* room will belong to. Open is naming the room itself,
        // and that id lives in one store whichever mode you reached it through. Getting this wrong
        // is what made the reuse-BuilderNewPacket approach overwrite rooms with empty rooms.
        assertEquals(BuilderOpenOptions.OpenSource.CONTENTS,
                BuilderOpenOptions.openSourceFor(BuilderMode.INSIDE_CARRIAGE,
                        BuilderNewOptions.SubType.CARRIAGE_ROOM));
        assertEquals(BuilderOpenOptions.OpenSource.CONTENTS,
                BuilderOpenOptions.openSourceFor(BuilderMode.TRAIN_OUTSIDE,
                        BuilderNewOptions.SubType.CARRIAGE_ROOM));
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
            assertEquals(BuilderOpenOptions.OpenSource.TRACK_TILES,
                    BuilderOpenOptions.openSourceFor(BuilderMode.TRACKS_TUNNELS, subType));
            assertEquals(BuilderOpenOptions.OpenSource.TUNNEL_PORTALS,
                    BuilderOpenOptions.openSourceFor(BuilderMode.TRAIN_DIMENSIONS, subType));
        }
    }

    @Test
    @DisplayName("Only the carriage sources are openable today")
    void onlyCarriageSourcesAreOpenable() {
        assertTrue(BuilderOpenOptions.isOpenable(BuilderOpenOptions.OpenSource.CARRIAGES));
        assertTrue(BuilderOpenOptions.isOpenable(BuilderOpenOptions.OpenSource.CONTENTS));
        assertTrue(BuilderOpenOptions.isOpenable(BuilderOpenOptions.OpenSource.PARTS));

        // Not a policy choice — there is no stamp or save path for these yet. When one lands, this
        // is the assertion that should fail and tell you to flip it.
        assertFalse(BuilderOpenOptions.isOpenable(BuilderOpenOptions.OpenSource.TRACK_TILES));
        assertFalse(BuilderOpenOptions.isOpenable(BuilderOpenOptions.OpenSource.TUNNEL_PORTALS));
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
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE,
                BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.CARRIAGES));
        assertEquals(BuilderPhotoPaths.Kind.CONTENTS,
                BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.CONTENTS));
        assertEquals(BuilderPhotoPaths.Kind.PART,
                BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.PARTS));

        // Null rather than a new enum constant: nothing photographs a track, so an entry for one
        // would be a path that never has a file behind it.
        assertNull(BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.TRACK_TILES));
        assertNull(BuilderOpenOptions.photoKindFor(BuilderOpenOptions.OpenSource.TUNNEL_PORTALS));
    }

    @Test
    @DisplayName("Anything openable can name where its photo lives")
    void openableSourcesAlwaysHaveAPhotoKind() {
        for (BuilderOpenOptions.OpenSource source : BuilderOpenOptions.OpenSource.values()) {
            if (BuilderOpenOptions.isOpenable(source)) {
                assertTrue(BuilderOpenOptions.photoKindFor(source) != null,
                        source + " is openable but has no photo kind");
            }
        }
    }
}
