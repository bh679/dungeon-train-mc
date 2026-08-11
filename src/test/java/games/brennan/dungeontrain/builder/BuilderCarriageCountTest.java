package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the "how much train does this build park?" rule.
 *
 * <p>Worth a test of its own because seven call sites derive from it — the stamp, the clear, the
 * bounds packet, the dirty check, the save target, the photo and the cinematic. Any two of them
 * disagreeing points the out-of-bounds wash or a save at a volume the blocks aren't in.</p>
 */
final class BuilderCarriageCountTest {

    @Test
    @DisplayName("A carriage room parks one carriage, whatever the mode")
    void carriageRoomIsAlwaysOne() {
        String room = BuilderNewOptions.SubType.CARRIAGE_ROOM.id();
        assertEquals(1, BuilderCarriageCount.of(BuilderMode.TRAIN_OUTSIDE, room),
                "a room is the interior of one carriage — the three-carriage run showed it thrice "
                        + "and saved one");
        assertEquals(1, BuilderCarriageCount.of(BuilderMode.INSIDE_CARRIAGE, room));
    }

    @Test
    @DisplayName("The carriage-less modes stay carriage-less for a room")
    void carriageRoomClampsRatherThanForcingOne() {
        String room = BuilderNewOptions.SubType.CARRIAGE_ROOM.id();
        assertEquals(0, BuilderCarriageCount.of(BuilderMode.TRACKS_TUNNELS, room),
                "clamped, not hard-coded: whether there is a carriage at all is BuilderMode's call");
        assertEquals(0, BuilderCarriageCount.of(BuilderMode.TRAIN_DIMENSIONS, room));
    }

    @Test
    @DisplayName("Every other sub-type keeps the mode's own count")
    void otherSubTypesAreUnchanged() {
        assertEquals(3, BuilderCarriageCount.of(BuilderMode.TRAIN_OUTSIDE,
                BuilderNewOptions.SubType.WHOLE_CARRIAGE.id()),
                "the run of three is the whole point of Train Outside — judging a silhouette");
        assertEquals(3, BuilderCarriageCount.of(BuilderMode.TRAIN_OUTSIDE,
                BuilderNewOptions.SubType.PARTS.id()));
        assertEquals(1, BuilderCarriageCount.of(BuilderMode.INSIDE_CARRIAGE,
                BuilderNewOptions.SubType.WHOLE_CARRIAGE.id()));
    }

    @Test
    @DisplayName("An unrecorded or unknown sub-type reads as 'not a room'")
    void unknownSubTypeKeepsModeCount() {
        assertEquals(3, BuilderCarriageCount.of(BuilderMode.TRAIN_OUTSIDE, null),
                "a world stamped before a sub-type was recorded still parks the mode's train");
        assertEquals(3, BuilderCarriageCount.of(BuilderMode.TRAIN_OUTSIDE, ""));
        assertEquals(3, BuilderCarriageCount.of(BuilderMode.TRAIN_OUTSIDE, "not_a_sub_type"));
    }

    @Test
    @DisplayName("No mode means no carriages")
    void missingModeIsZero() {
        BuilderMode none = null;
        assertEquals(0, BuilderCarriageCount.of(none, BuilderNewOptions.SubType.CARRIAGE_ROOM.id()));
        assertEquals(0, BuilderCarriageCount.of((String) null, null),
                "outside a builder world there is nothing parked to count");
        assertEquals(0, BuilderCarriageCount.of("no_such_mode", null));
    }

    @Test
    @DisplayName("The id overload agrees with the enum overload")
    void idOverloadMatches() {
        for (BuilderMode mode : BuilderMode.values()) {
            for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
                assertEquals(BuilderCarriageCount.of(mode, subType.id()),
                        BuilderCarriageCount.of(mode.id(), subType.id()),
                        mode.id() + " / " + subType.id());
            }
        }
    }
}
