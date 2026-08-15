package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.portal.PortalFacing.Verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facing rule that decides which copy of a corridor a player is in.
 *
 * <p>Test geometry is the shipped 9-block carriage, so local X runs {@code 0..8} and the far door
 * plane is at {@code 8}. Yaws use Minecraft's convention: {@code -90} faces {@code +X} (the
 * direction of travel, and the room for an {@link PortalCarriageRole#ENTRY} pair), {@code +90} faces
 * {@code -X}, and {@code 0}/{@code 180} face across the corridor.</p>
 */
final class PortalFacingTest {

    private static final int LENGTH = 9;
    /** The 13-block corridor a LONG pair gets at the same carriage dims. */
    private static final int LONG_LENGTH = 13;

    private static final float FACE_PLUS_X = -90f;
    private static final float FACE_MINUS_X = 90f;
    private static final float FACE_ACROSS = 0f;

    /** A yaw {@code deg} degrees off {@code +X}, turning toward {@code +Z}. */
    private static float offPlusX(double deg) {
        return (float) (FACE_PLUS_X + deg);
    }

    // ---- the gate -------------------------------------------------------------

    @Test
    @DisplayName("inside the first block the rule answers nothing, however decisively you look")
    void belowTheGate_holds() {
        for (double localX : new double[] {0.0, 0.25, 0.5, 0.99}) {
            assertEquals(Verdict.HOLD,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, FACE_PLUS_X),
                "localX " + localX + " is inside the doorway — being yanked here would be felt, "
                    + "because the door frame is right there to notice it by");
            assertEquals(Verdict.HOLD,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, FACE_MINUS_X));
        }
    }

    @Test
    @DisplayName("the gate is measured from the TRAIN-side door, which is the far end for an EXIT")
    void theGateMirrorsWithTheRole() {
        // ENTRY: the train is at local X 0, so the gate bites there.
        assertEquals(Verdict.HOLD,
            PortalFacing.verdict(0.5, LENGTH, PortalCarriageRole.ENTRY, FACE_PLUS_X));
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(7.5, LENGTH, PortalCarriageRole.ENTRY, FACE_PLUS_X));

        // EXIT: mirrored. The train is at local X 8, so the gate bites at the HIGH end and the room
        // is toward -X.
        assertEquals(Verdict.HOLD,
            PortalFacing.verdict(7.5, LENGTH, PortalCarriageRole.EXIT, FACE_MINUS_X));
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(0.5, LENGTH, PortalCarriageRole.EXIT, FACE_MINUS_X));
    }

    // ---- which way is which ---------------------------------------------------

    @Test
    @DisplayName("looking toward the room puts you in the copy; toward the train, in the original")
    void theTwoDirections() {
        // ENTRY, mid-corridor: +X is the room.
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(4.5, LENGTH, PortalCarriageRole.ENTRY, FACE_PLUS_X));
        assertEquals(Verdict.ORIGINAL,
            PortalFacing.verdict(4.5, LENGTH, PortalCarriageRole.ENTRY, FACE_MINUS_X));

        // EXIT, mid-corridor: -X is the room.
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(4.5, LENGTH, PortalCarriageRole.EXIT, FACE_MINUS_X));
        assertEquals(Verdict.ORIGINAL,
            PortalFacing.verdict(4.5, LENGTH, PortalCarriageRole.EXIT, FACE_PLUS_X));
    }

    @Test
    @DisplayName("looking across the corridor decides nothing, at any depth")
    void perpendicularHolds() {
        for (double localX : new double[] {1.0, 2.5, 4.5, 6.5, 8.0}) {
            assertEquals(Verdict.HOLD,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, FACE_ACROSS),
                "localX " + localX + " looking dead across the corridor");
            assertEquals(Verdict.HOLD,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, 180f));
        }
    }

    // ---- the lerp -------------------------------------------------------------

    @Test
    @DisplayName("at the gate the cone is strict — 25° holds, 35° does not")
    void atTheGate_thirtyDegrees() {
        double atGate = PortalFacing.MIN_DEPTH;

        assertEquals(Verdict.COPY,
            PortalFacing.verdict(atGate, LENGTH, PortalCarriageRole.ENTRY, offPlusX(25)),
            "25° off the axis is inside the 30° cone, so it should still commit");
        assertEquals(Verdict.HOLD,
            PortalFacing.verdict(atGate, LENGTH, PortalCarriageRole.ENTRY, offPlusX(35)),
            "35° off the axis is outside it — stepping through the door at an angle, or glancing at "
                + "the frame, is not asking to be moved");
    }

    @Test
    @DisplayName("at the far end almost any turn commits — 80° fires where it would not at the gate")
    void atTheFarEnd_theConeIsWideOpen() {
        assertEquals(Verdict.HOLD,
            PortalFacing.verdict(PortalFacing.MIN_DEPTH, LENGTH, PortalCarriageRole.ENTRY,
                offPlusX(80)));
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(LENGTH - 1, LENGTH, PortalCarriageRole.ENTRY, offPlusX(80)),
            "this is what makes HOLD safe rather than a trap: by the way out, only a look within "
                + "about 5° of dead perpendicular leaves you undecided");
    }

    @Test
    @DisplayName("the required cone only ever widens as you go deeper")
    void thresholdIsMonotonic() {
        for (int length : new int[] {LENGTH, LONG_LENGTH}) {
            double previous = Double.MAX_VALUE;
            for (double depth = PortalFacing.MIN_DEPTH; depth <= length - 1; depth += 0.25) {
                double t = PortalFacing.thresholdAt(depth, length);
                assertTrue(t <= previous + 1e-9,
                    "length " + length + " depth " + depth + ": the cone tightened again, which "
                        + "would leave a player undecided at the end they have to leave through");
                previous = t;
            }
        }
    }

    @Test
    @DisplayName("the ramp is clamped outside the corridor rather than running past its ends")
    void thresholdClamps() {
        double atGate = PortalFacing.thresholdAt(PortalFacing.MIN_DEPTH, LENGTH);
        double atEnd = PortalFacing.thresholdAt(LENGTH - 1, LENGTH);

        // insideCorridor admits half a block of pad past either door, so both are reachable.
        assertEquals(atGate, PortalFacing.thresholdAt(-5, LENGTH), 1e-9);
        assertEquals(atEnd, PortalFacing.thresholdAt(LENGTH + 5, LENGTH), 1e-9);
    }

    @Test
    @DisplayName("a long corridor gets the same ramp stretched over its own length")
    void bothKindsRampOverTheirOwnLength() {
        // The ends agree; only the distance between them differs. So a SHORT pair is not merely a
        // truncated LONG one — a player is equally decided at the same fraction along either.
        assertEquals(PortalFacing.thresholdAt(PortalFacing.MIN_DEPTH, LENGTH),
            PortalFacing.thresholdAt(PortalFacing.MIN_DEPTH, LONG_LENGTH), 1e-9);
        assertEquals(PortalFacing.thresholdAt(LENGTH - 1, LENGTH),
            PortalFacing.thresholdAt(LONG_LENGTH - 1, LONG_LENGTH), 1e-9);

        // Halfway along each is the same verdict for the same off-axis look.
        double midShort = PortalFacing.MIN_DEPTH + (LENGTH - 1 - PortalFacing.MIN_DEPTH) / 2;
        double midLong = PortalFacing.MIN_DEPTH + (LONG_LENGTH - 1 - PortalFacing.MIN_DEPTH) / 2;
        assertEquals(PortalFacing.thresholdAt(midShort, LENGTH),
            PortalFacing.thresholdAt(midLong, LONG_LENGTH), 1e-9);
    }

    // ---- the primitives -------------------------------------------------------

    @Test
    @DisplayName("lookX follows Minecraft's yaw convention")
    void lookXConvention() {
        assertEquals(1.0, PortalFacing.lookX(FACE_PLUS_X), 1e-9);
        assertEquals(-1.0, PortalFacing.lookX(FACE_MINUS_X), 1e-9);
        assertEquals(0.0, PortalFacing.lookX(FACE_ACROSS), 1e-9);
        assertEquals(0.0, PortalFacing.lookX(180f), 1e-9);
    }

    @Test
    @DisplayName("the room is +X from an ENTRY corridor and -X from an EXIT one")
    void axisMirrors() {
        assertEquals(1, PortalFacing.axisTowardRoom(PortalCarriageRole.ENTRY));
        assertEquals(-1, PortalFacing.axisTowardRoom(PortalCarriageRole.EXIT));
    }

    @Test
    @DisplayName("depth counts from the train-side door, whichever end of the corridor that is")
    void depthMirrors() {
        assertEquals(0.0, PortalFacing.depthFromTrainDoor(0, LENGTH, PortalCarriageRole.ENTRY), 1e-9);
        assertEquals(8.0, PortalFacing.depthFromTrainDoor(8, LENGTH, PortalCarriageRole.ENTRY), 1e-9);
        assertEquals(8.0, PortalFacing.depthFromTrainDoor(0, LENGTH, PortalCarriageRole.EXIT), 1e-9);
        assertEquals(0.0, PortalFacing.depthFromTrainDoor(8, LENGTH, PortalCarriageRole.EXIT), 1e-9);
    }
}
