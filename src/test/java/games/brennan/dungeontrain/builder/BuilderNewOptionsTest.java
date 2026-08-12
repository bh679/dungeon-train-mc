package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.builder.BuilderNewOptions.CopySource;
import games.brennan.dungeontrain.builder.BuilderNewOptions.Pick;
import games.brennan.dungeontrain.builder.BuilderNewOptions.PickKind;
import games.brennan.dungeontrain.builder.BuilderNewOptions.SubType;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.WholeCarriage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertFalse(BuilderNewOptions.isValidName("car-2"), "a hyphen is not an id character");
        assertFalse(BuilderNewOptions.isValidName(""), "empty");
        assertFalse(BuilderNewOptions.isValidName(null));
        assertFalse(BuilderNewOptions.isValidName("my car"), "a space breaks the id");
        assertFalse(BuilderNewOptions.isValidName("MyCar"), "ids are lower-case");
        assertFalse(BuilderNewOptions.isValidName("car!"), "punctuation");
        assertFalse(BuilderNewOptions.isValidName("a".repeat(33)), "over the field's max length");
    }

    /**
     * Names to run past both sides. Not a sample of the interesting ones — the point is the
     * boundary characters, which is where the two spellings of "a valid id" last disagreed.
     */
    private static final List<String> NAME_CANDIDATES = List.of(
            "crate_car", "car2", "a", "_", "0", "a".repeat(32),
            "crate-car", "car-2", "-", "", "my car", "MyCar", "car!", "café",
            "a".repeat(33), "crate.car", "crate/car", "crate:car");

    @Test
    @DisplayName("New accepts exactly the names Save can turn into an id")
    void newAndSaveAgreeOnNames() {
        // The New screen validates, then Save constructs whichever identity the sub type calls
        // for. A name New waves through and Save then rejects is an IllegalArgumentException deep
        // in BuilderSave, surfaced to the builder as a raw failure — so the sets have to be equal,
        // not merely overlapping. Matched raw against each pattern because that is what the Save
        // path does: CarriageVariant.custom, CarriageContents.custom and
        // new CarriageVariant.Custom all match the name as typed.
        //
        // Compared against the patterns rather than WholeCarriage.isValidName, which lower-cases
        // its argument first and so accepts "MyCar" on purpose — a deliberate divergence, not a
        // fourth opinion about the shape.
        for (String name : NAME_CANDIDATES) {
            boolean newScreen = BuilderNewOptions.isValidName(name);
            assertEquals(newScreen, CarriageVariant.NAME_PATTERN.matcher(name).matches(),
                    "carriage variant disagrees about '" + name + "'");
            assertEquals(newScreen, CarriageContents.NAME_PATTERN.matcher(name).matches(),
                    "carriage contents disagrees about '" + name + "'");
            assertEquals(newScreen, WholeCarriage.NAME_PATTERN.matcher(name).matches(),
                    "whole carriage disagrees about '" + name + "'");
            assertEquals(newScreen, CarriagePartRegistry.NAME_PATTERN.matcher(name).matches(),
                    "carriage part disagrees about '" + name + "'");
        }
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

    @Test
    @DisplayName("Every sub type round-trips through its stored id")
    void subTypeIdsRoundTrip() {
        // The world data keeps the id string, and the parked carriage count is read back off it —
        // a sub type that didn't round-trip would silently fall back to the mode's own count.
        for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
            assertEquals(subType, BuilderNewOptions.SubType.fromId(subType.id()).orElse(null));
        }
    }

    @Test
    @DisplayName("An absent or unknown sub type is empty, not a guess")
    void unknownSubTypeIsEmpty() {
        // Null is a real state: a world stamped straight off the title screen has never had a New
        // or an Open run in it. Callers own the default, so this must not pick one for them.
        assertTrue(BuilderNewOptions.SubType.fromId(null).isEmpty());
        assertTrue(BuilderNewOptions.SubType.fromId("").isEmpty());
        assertTrue(BuilderNewOptions.SubType.fromId("carriage").isEmpty());
        // Tolerant of case and stray whitespace, the way BuilderMode.fromId is.
        assertEquals(BuilderNewOptions.SubType.CARRIAGE_ROOM,
                BuilderNewOptions.SubType.fromId(" Carriage_Room ").orElse(null));
    }
}
