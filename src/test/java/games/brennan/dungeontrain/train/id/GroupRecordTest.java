package games.brennan.dungeontrain.train.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Saving and reading back {@link GroupRecord}, and the context it gives each slot. */
final class GroupRecordTest {

    private static final GroupRecord RECORD =
        new GroupRecord(3, 17, 2, 0b10, 1737, true, 0b100, false, new int[] {-1, 88, -1});

    @Test
    @DisplayName("a record survives saving and loading")
    void ints_roundTrip() {
        assertEquals(RECORD, GroupRecord.fromInts(RECORD.toInts()));
    }

    @Test
    @DisplayName("each slot gets the group's context and only its own shared build")
    void contextFor_slot() {
        assertEquals(new CarriageId.Full(OptionalInt.of(17), 2, 0b10, 1737 % 1296, true, 0b100, false, OptionalInt.empty()),
            RECORD.contextFor(0, 0));
        assertEquals(OptionalInt.of(88), RECORD.contextFor(1, 0).sharedBuild());
    }

    @Test
    @DisplayName("the level is left out when the carriage number gives it")
    void contextFor_baselineLevel() {
        assertEquals(OptionalInt.empty(), RECORD.contextFor(0, 17).level());
        assertEquals(OptionalInt.of(17), RECORD.contextFor(0, 16).level());
    }

    @Test
    @DisplayName("malformed saved entries are dropped, not thrown")
    void fromInts_malformed() {
        assertNull(GroupRecord.fromInts(new int[0]));
        assertNull(GroupRecord.fromInts(new int[] {3, 1, 2, 3, 4, 0, 0, -1}));
        assertNull(GroupRecord.fromInts(new int[] {0, 1, 2, 3, 4, 0, 0, -1}));
    }

    @Test
    @DisplayName("the shared-build list is copied, so the record can't be changed from outside")
    void sharedBySlot_defensiveCopy() {
        int[] shared = {-1, -1, -1};
        GroupRecord r = new GroupRecord(3, 0, 0, 0, 0, false, 0, false, shared);
        shared[0] = 5;
        r.sharedBySlot()[1] = 6;
        assertEquals(OptionalInt.empty(), r.contextFor(0, 0).sharedBuild());
        assertEquals(OptionalInt.empty(), r.contextFor(1, 0).sharedBuild());
    }

    @Test
    @DisplayName("the version reads the minor number of the mod version")
    void minorOf_version() {
        assertEquals(1737, CarriageIds.minorOf("0.1737.0"));
        assertEquals(1737, CarriageIds.minorOf("0.1737.0+dev"));
        assertEquals(0, CarriageIds.minorOf("garbage"));
    }

    @Test
    @DisplayName("a record needs one shared entry per slot")
    void sharedBySlot_lengthChecked() {
        assertThrows(IllegalArgumentException.class,
            () -> new GroupRecord(3, 0, 0, 0, 0, false, 0, false, new int[] {-1}));
    }
}
