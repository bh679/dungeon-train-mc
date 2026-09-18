package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.WholeCarriage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wire-level contract of the "Whole carriage room" download destination. */
final class BuilderRelayWholeRoomTest {

    @Test
    @DisplayName("the sentinel can never collide with a real parent id")
    void sentinelIsNeverAName() {
        assertFalse(WholeCarriage.isValidName(BuilderRelayWholeRoom.WHOLE_ROOM_PARENT));
        assertFalse(CarriageGroup.isValidName(BuilderRelayWholeRoom.WHOLE_ROOM_PARENT));
        assertTrue(BuilderRelayWholeRoom.WHOLE_ROOM_PARENT.length() <= 64, "must fit the packet's writeUtf(64)");
    }

    @Test
    @DisplayName("requested only by the exact sentinel")
    void requested() {
        assertTrue(BuilderRelayWholeRoom.requested(BuilderRelayWholeRoom.WHOLE_ROOM_PARENT));
        assertFalse(BuilderRelayWholeRoom.requested(BuilderRelaySubVariant.DEFAULT_PARENT_ID));
        assertFalse(BuilderRelayWholeRoom.requested(""));
        assertFalse(BuilderRelayWholeRoom.requested(null));
    }

    @Test
    @DisplayName("carriages and contents can become rooms; nothing else can")
    void supports() {
        assertTrue(BuilderRelayWholeRoom.supports(BuilderPhotoPaths.Kind.CARRIAGE));
        assertTrue(BuilderRelayWholeRoom.supports(BuilderPhotoPaths.Kind.CONTENTS));
        assertFalse(BuilderRelayWholeRoom.supports(BuilderPhotoPaths.Kind.CARRIAGE_GROUP));
        assertFalse(BuilderRelayWholeRoom.supports(BuilderPhotoPaths.Kind.PART));
        assertFalse(BuilderRelayWholeRoom.supports(BuilderPhotoPaths.Kind.TRACK));
        assertFalse(BuilderRelayWholeRoom.supports(BuilderPhotoPaths.Kind.PORTAL_ROOM));
        assertFalse(BuilderRelayWholeRoom.supports(null));
    }
}
