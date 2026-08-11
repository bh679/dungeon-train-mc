package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.builder.BuilderNewOptions.CopySource;
import games.brennan.dungeontrain.builder.BuilderNewOptions.Pick;
import games.brennan.dungeontrain.builder.BuilderNewOptions.PickKind;
import games.brennan.dungeontrain.builder.BuilderNewOptions.SubType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the New screen's single picker lists, and what counts as a usable name.
 *
 * <p>The picker's contents are the part that's easy to get subtly wrong: the same id travels to the
 * server, which decides what to stamp from the same table. A mismatch between the two would mean a
 * list of stage names being looked up as carriage templates — every entry missing, and a silent
 * fallback to the first carriage.</p>
 */
final class BuilderNewOptionsTest {

    // ---- which controls appear ----

    @Test
    @DisplayName("Track modes have no sub types")
    void trackModesHaveNoSubTypes() {
        assertFalse(BuilderNewOptions.hasSubTypes(BuilderMode.TRACKS_TUNNELS));
        assertFalse(BuilderNewOptions.hasSubTypes(BuilderMode.TRAIN_DIMENSIONS));
        assertTrue(BuilderNewOptions.hasSubTypes(BuilderMode.TRAIN_OUTSIDE));
        assertTrue(BuilderNewOptions.hasSubTypes(BuilderMode.INSIDE_CARRIAGE));
    }

    @Test
    @DisplayName("A part kind is only asked for when parts are what's being made")
    void partKindVisibility() {
        assertTrue(BuilderNewOptions.showsPartKind(BuilderMode.TRAIN_OUTSIDE, SubType.PARTS));
        assertTrue(BuilderNewOptions.showsPartKind(BuilderMode.INSIDE_CARRIAGE, SubType.PARTS));
        assertFalse(BuilderNewOptions.showsPartKind(BuilderMode.TRAIN_OUTSIDE, SubType.WHOLE_CARRIAGE));
        assertFalse(BuilderNewOptions.showsPartKind(BuilderMode.TRACKS_TUNNELS, SubType.PARTS),
                "a track mode has no sub type to be Parts in the first place");
    }

    // ---- what the picker lists ----

    @Test
    @DisplayName("A whole carriage is picked by the stretch of the game it's for")
    void wholeCarriageListsStages() {
        assertEquals(CopySource.STAGES,
                BuilderNewOptions.copySourceFor(BuilderMode.TRAIN_OUTSIDE, SubType.WHOLE_CARRIAGE));
        assertEquals(CopySource.STAGES,
                BuilderNewOptions.copySourceFor(BuilderMode.INSIDE_CARRIAGE, SubType.WHOLE_CARRIAGE));
    }

    @Test
    @DisplayName("Parts list part templates, whichever mode you came in through")
    void partsListParts() {
        assertEquals(CopySource.PARTS,
                BuilderNewOptions.copySourceFor(BuilderMode.TRAIN_OUTSIDE, SubType.PARTS));
        assertEquals(CopySource.PARTS,
                BuilderNewOptions.copySourceFor(BuilderMode.INSIDE_CARRIAGE, SubType.PARTS));
    }

    @Test
    @DisplayName("A room means the carriage from outside, and the contents from inside")
    void carriageRoomFollowsTheMode() {
        // The whole reason copySourceFor takes the mode: from the platform you're saying which
        // carriage the room belongs to; standing in one, you're copying a room that already exists.
        assertEquals(CopySource.CARRIAGES,
                BuilderNewOptions.copySourceFor(BuilderMode.TRAIN_OUTSIDE, SubType.CARRIAGE_ROOM));
        assertEquals(CopySource.CONTENTS,
                BuilderNewOptions.copySourceFor(BuilderMode.INSIDE_CARRIAGE, SubType.CARRIAGE_ROOM));
    }

    @Test
    @DisplayName("Track and portal modes have nothing to pick from")
    void trackModesHaveNoPicker() {
        for (SubType subType : SubType.values()) {
            assertEquals(CopySource.NONE,
                    BuilderNewOptions.copySourceFor(BuilderMode.TRACKS_TUNNELS, subType));
            assertEquals(CopySource.NONE,
                    BuilderNewOptions.copySourceFor(BuilderMode.TRAIN_DIMENSIONS, subType));
        }
    }

    @Test
    @DisplayName("Every mode and sub type answers — no selection falls through to nothing")
    void everySelectionResolves() {
        for (BuilderMode mode : BuilderMode.values()) {
            for (SubType subType : SubType.values()) {
                assertTrue(BuilderNewOptions.copySourceFor(mode, subType) != null,
                        mode + "/" + subType);
            }
        }
    }

    // ---- name validation ----

    @Test
    @DisplayName("Names must survive becoming a template id")
    void nameValidation() {
        assertTrue(BuilderNewOptions.isValidName("crate_car"));
        assertTrue(BuilderNewOptions.isValidName("car-2"));
        assertFalse(BuilderNewOptions.isValidName(""), "empty");
        assertFalse(BuilderNewOptions.isValidName(null));
        assertFalse(BuilderNewOptions.isValidName("my car"), "a space breaks the id");
        assertFalse(BuilderNewOptions.isValidName("MyCar"), "ids are lower-case");
        assertFalse(BuilderNewOptions.isValidName("car!"), "punctuation");
        assertFalse(BuilderNewOptions.isValidName("a".repeat(33)), "over the field's max length");
    }

    @Test
    @DisplayName("An empty name is fine for New — an unnamed build is a draft, not an error")
    void emptyNameIsADraftNotAnError() {
        // New accepts it (the screen enables Create); the validator still rejects it, because
        // Save-as needs a real id. Both readings of "valid" have to stay distinguishable.
        assertFalse(BuilderNewOptions.isValidName(""));
    }

    // ---- tagged picks ----

    @Test
    @DisplayName("A tagged pick round-trips through the picker and back")
    void taggedPicksRoundTrip() {
        assertEquals(new Pick(PickKind.STAGE, "desert"),
                BuilderNewOptions.parsePick(BuilderNewOptions.tagStage("desert")));
        assertEquals(new Pick(PickKind.WHOLE_CARRIAGE, "crate_car"),
                BuilderNewOptions.parsePick(BuilderNewOptions.tagWholeCarriage("crate_car")));
    }

    @Test
    @DisplayName("The same name in both lists stays two different picks")
    void collidingNamesStayDistinct() {
        // The whole reason for tagging. Ids are only unique within their own store, and the
        // Whole Carriage picker shows two stores at once — an untagged 'quartz' coming back from
        // the client could mean either, and guessing wrong stamps the wrong thing.
        String asStage = BuilderNewOptions.tagStage("quartz");
        String asBuild = BuilderNewOptions.tagWholeCarriage("quartz");
        assertFalse(asStage.equals(asBuild), "the two must not collapse to one picker value");
        assertEquals(PickKind.STAGE, BuilderNewOptions.parsePick(asStage).kind());
        assertEquals(PickKind.WHOLE_CARRIAGE, BuilderNewOptions.parsePick(asBuild).kind());
        assertEquals("quartz", BuilderNewOptions.parsePick(asBuild).id());
    }

    @Test
    @DisplayName("An untagged value is a Stage — which is what every value used to be")
    void untaggedParsesAsStage() {
        assertEquals(new Pick(PickKind.STAGE, "desert"), BuilderNewOptions.parsePick("desert"));
    }

    @Test
    @DisplayName("Nothing picked is an empty Stage, not a null")
    void emptyPickIsEmptyNotNull() {
        assertTrue(BuilderNewOptions.parsePick("").isEmpty());
        assertTrue(BuilderNewOptions.parsePick(null).isEmpty());
        assertEquals(PickKind.STAGE, BuilderNewOptions.parsePick(null).kind());
        // A tag with nothing after it is still a whole-carriage pick, just an unusable one — the
        // packet resolves the id against the registry and starts blank when it misses.
        assertTrue(BuilderNewOptions.parsePick(BuilderNewOptions.tagWholeCarriage("")).isEmpty());
    }
}
