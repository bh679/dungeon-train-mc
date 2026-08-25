package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.net.SnapshotCuePacket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the THRESHOLD camera may stand. Pure geometry — {@code AABB} needs no NeoForge bootstrap.
 *
 * <p>The two things this has to get right are the two ways a Train Dimension photo goes wrong: the
 * camera standing in the next copy of a tiled room (nothing is in the way — the seams are carved
 * open — so only the box says no), and the camera standing inside a twin corridor, which is an
 * illusion that only reads correctly from one side.</p>
 */
class SnapshotFramingTest {

    /** A room copy occupying blocks 0..15 on X and Z, 0..7 on Y. */
    private static final SnapshotCuePacket.Box ROOM = new SnapshotCuePacket.Box(0, 0, 0, 15, 7, 15);
    /** A corridor tube standing inside it, along the middle of X. */
    private static final SnapshotCuePacket.Box CORRIDOR = new SnapshotCuePacket.Box(0, 0, 6, 15, 3, 9);

    @Test
    @DisplayName("no boxes at all is the unconstrained default, shared rather than rebuilt")
    void noBoxesIsNone() {
        assertSame(SnapshotFraming.NONE, SnapshotFraming.of(null, List.of()));
        assertSame(SnapshotFraming.NONE, SnapshotFraming.of(null, null));
        assertTrue(SnapshotFraming.NONE.allows(new Vec3(9999, -60, -9999)));
    }

    @Test
    @DisplayName("inside the room copy is allowed")
    void insideTheRoomIsAllowed() {
        SnapshotFraming framing = SnapshotFraming.of(ROOM, List.of());
        assertTrue(framing.allows(new Vec3(8.0, 4.0, 2.0)));
        assertTrue(framing.allows(new Vec3(2.0, 1.0, 13.0)));
    }

    @Test
    @DisplayName("the copy next door is refused even though nothing is in the way")
    void theNextCopyIsRefused() {
        SnapshotFraming framing = SnapshotFraming.of(ROOM, List.of());
        assertFalse(framing.allows(new Vec3(20.0, 4.0, 8.0)));  // one room along the train
        assertFalse(framing.allows(new Vec3(8.0, 4.0, 20.0)));  // one room across it
        assertFalse(framing.allows(new Vec3(-3.0, 4.0, 8.0)));  // and the one behind
    }

    @Test
    @DisplayName("the room's own wall face is refused, so the lens is never flush against it")
    void theWallFaceIsRefused() {
        SnapshotFraming framing = SnapshotFraming.of(ROOM, List.of());
        assertFalse(framing.allows(new Vec3(0.1, 4.0, 8.0)));
        assertFalse(framing.allows(new Vec3(15.9, 4.0, 8.0)));
    }

    @Test
    @DisplayName("a corridor standing inside the room is refused")
    void corridorsInsideTheRoomAreRefused() {
        SnapshotFraming framing = SnapshotFraming.of(ROOM, List.of(CORRIDOR));
        assertFalse(framing.allows(new Vec3(8.0, 2.0, 8.0)), "in the corridor tube");
        assertTrue(framing.allows(new Vec3(8.0, 6.0, 8.0)), "above it, still in the room");
        assertTrue(framing.allows(new Vec3(8.0, 2.0, 2.0)), "beside it, still in the room");
    }

    @Test
    @DisplayName("a cue with only exclusions still constrains")
    void exclusionsWithoutConfinement() {
        SnapshotFraming framing = SnapshotFraming.of(null, List.of(CORRIDOR));
        assertFalse(framing.allows(new Vec3(8.0, 2.0, 8.0)));
        assertTrue(framing.allows(new Vec3(500.0, 2.0, 500.0)));
    }
}
