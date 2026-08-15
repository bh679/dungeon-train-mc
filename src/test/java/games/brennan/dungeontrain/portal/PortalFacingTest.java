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

    // ---- totality: there is no undecided direction ----------------------------

    @Test
    @DisplayName("Every direction, in every block, belongs to one copy or the other")
    void theVerdictIsTotal() {
        for (int length : new int[] {LENGTH, LONG_LENGTH}) {
            for (PortalCarriageRole role : PortalCarriageRole.values()) {
                for (double localX = -0.5; localX <= length; localX += 0.25) {
                    for (int yaw = -180; yaw < 180; yaw += 5) {
                        Verdict v = PortalFacing.verdict(localX, length, role, yaw);
                        assertTrue(v == Verdict.COPY || v == Verdict.ORIGINAL,
                            "length " + length + " " + role + " localX " + localX + " yaw " + yaw);
                    }
                }
            }
        }
    }

    // ---- the sweep ------------------------------------------------------------

    @Test
    @DisplayName("The sweep runs 40° at block 1 to 140° at block L-2, with 90° at the middle")
    void theThreeAnchors() {
        for (int length : new int[] {LENGTH, LONG_LENGTH}) {
            assertEquals(PortalFacing.CONE_AT_TRAIN_DOOR_DEGREES,
                PortalFacing.coneDegreesAt(PortalFacing.FIRST_RAMP_BLOCK, length), 1e-6,
                "length " + length + " block 1");
            assertEquals(90.0,
                PortalFacing.coneDegreesAt((length - 1) / 2.0, length), 1e-6,
                "length " + length + ": trimming a block off each end leaves [1, L-2] still centred "
                    + "on (L-1)/2, so the middle is still exactly perpendicular");
            assertEquals(PortalFacing.CONE_AT_ROOM_DOOR_DEGREES,
                PortalFacing.coneDegreesAt(PortalFacing.lastRampBlock(length), length), 1e-6,
                "length " + length + " block L-2");
        }
    }

    /**
     * The two door planes are outside the sweep and hold their neighbour's value, so a player in a
     * doorway is treated exactly as one standing in the block just inside it.
     */
    @Test
    @DisplayName("The door blocks are not steps of their own — they clamp to the block inside")
    void theDoorBlocksClampInward() {
        for (int length : new int[] {LENGTH, LONG_LENGTH}) {
            assertEquals(PortalFacing.thresholdAt(PortalFacing.FIRST_RAMP_BLOCK, length),
                PortalFacing.thresholdAt(0, length), 1e-12,
                "length " + length + ": the train door plane must read as block 1");
            assertEquals(PortalFacing.thresholdAt(PortalFacing.lastRampBlock(length), length),
                PortalFacing.thresholdAt(length - 1, length), 1e-12,
                "length " + length + ": the room door plane must read as block L-2");
        }
    }

    @Test
    @DisplayName("At the middle the split is a clean perpendicular — any room-ward look crosses")
    void theMiddleIsPerpendicular() {
        double mid = (LENGTH - 1) / 2.0;
        assertEquals(0.0, PortalFacing.thresholdAt(mid, LENGTH), 1e-9);

        // One degree either side of dead perpendicular is enough to decide it.
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(mid, LENGTH, PortalCarriageRole.ENTRY, offPlusX(89)));
        assertEquals(Verdict.ORIGINAL,
            PortalFacing.verdict(mid, LENGTH, PortalCarriageRole.ENTRY, offPlusX(91)));
    }

    @Test
    @DisplayName("Near the train the train claims most directions; near the room, the room does")
    void theShareFollowsTheEnd() {
        double atTrain = PortalFacing.CONE_AT_TRAIN_DOOR_DEGREES;
        double atRoom = PortalFacing.CONE_AT_ROOM_DOOR_DEGREES;

        // Block 1, the train end of the sweep: only a look inside its cone crosses. Block 0, the
        // door plane, answers identically.
        for (double localX : new double[] {0.5, 1.5}) {
            assertEquals(Verdict.COPY,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, offPlusX(atTrain - 5)));
            assertEquals(Verdict.ORIGINAL,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, offPlusX(atTrain + 5)));
        }

        // Block 7, the room end, and block 8, its door plane: the mirror — only a look that far
        // round toward the TRAIN keeps you on it.
        for (double localX : new double[] {7.5, 8.5}) {
            assertEquals(Verdict.COPY,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, offPlusX(atRoom - 5)));
            assertEquals(Verdict.ORIGINAL,
                PortalFacing.verdict(localX, LENGTH, PortalCarriageRole.ENTRY, offPlusX(atRoom + 5)));
        }
    }

    @Test
    @DisplayName("The divide only ever sweeps toward the room as you walk in")
    void thresholdIsMonotonic() {
        for (int length : new int[] {LENGTH, LONG_LENGTH}) {
            double previous = Double.MAX_VALUE;
            for (int block = 0; block <= length - 1; block++) {
                double t = PortalFacing.thresholdAt(block, length);
                assertTrue(t <= previous + 1e-9,
                    "length " + length + " block " + block + ": the divide swung back toward the "
                        + "train, which would take area away from the room as you approach it");
                previous = t;
            }
        }
    }

    // ---- one step per block ---------------------------------------------------

    @Test
    @DisplayName("The divide does not move within a block — sub-block drift changes nothing")
    void quantisedToTheBlock() {
        double atBlockStart = PortalFacing.thresholdAt(
            PortalFacing.depthFromTrainDoor(3.0, LENGTH, PortalCarriageRole.ENTRY), LENGTH);

        // Integer tenths, not an accumulating `frac += 0.1`: ten of those additions reach
        // 0.9999999999999999, and `3 + that` rounds to exactly 4.0 in double — so the loop would
        // step into the next block and report it as a failure of the code.
        for (int tenths = 0; tenths < 10; tenths++) {
            double localX = 3 + tenths / 10.0;
            assertEquals(atBlockStart,
                PortalFacing.thresholdAt(
                    PortalFacing.depthFromTrainDoor(localX, LENGTH, PortalCarriageRole.ENTRY),
                    LENGTH),
                1e-12,
                "localX " + localX + " must read the same as localX 3.0 — this is what makes the "
                    + "rule immune to the Sable pose drift SWAP_HYSTERESIS was sized against");
        }
    }

    @Test
    @DisplayName("The block is floored before the role mirrors it, not after")
    void quantisationHappensOnLocalX() {
        // localX 3.7 is block 3 in both roles. From an ENTRY corridor's train door that is depth 3;
        // from an EXIT corridor's, depth 5. Flooring the mirrored value would give 4, naming a block
        // the player is not standing in.
        assertEquals(3.0,
            PortalFacing.depthFromTrainDoor(3.7, LENGTH, PortalCarriageRole.ENTRY), 1e-9);
        assertEquals(5.0,
            PortalFacing.depthFromTrainDoor(3.7, LENGTH, PortalCarriageRole.EXIT), 1e-9);
    }

    @Test
    @DisplayName("The half block of pad outside either door reads as the door block")
    void padClampsToTheDoor() {
        assertEquals(0.0,
            PortalFacing.depthFromTrainDoor(-0.4, LENGTH, PortalCarriageRole.ENTRY), 1e-9);
        assertEquals(LENGTH - 1.0,
            PortalFacing.depthFromTrainDoor(LENGTH + 0.4, LENGTH, PortalCarriageRole.ENTRY), 1e-9);
    }

    // ---- the mirror -----------------------------------------------------------

    @Test
    @DisplayName("EXIT mirrors ENTRY: the room is toward -X and the sweep runs the other way")
    void theRoleMirrors() {
        // ENTRY, at the train door: +X is the room.
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(0.5, LENGTH, PortalCarriageRole.ENTRY, FACE_PLUS_X));
        assertEquals(Verdict.ORIGINAL,
            PortalFacing.verdict(0.5, LENGTH, PortalCarriageRole.ENTRY, FACE_MINUS_X));

        // EXIT: the train door is at local X 8, so local X 0.5 is the ROOM end of that corridor —
        // and the room lies toward -X.
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(7.5, LENGTH, PortalCarriageRole.EXIT, FACE_MINUS_X));
        assertEquals(Verdict.ORIGINAL,
            PortalFacing.verdict(7.5, LENGTH, PortalCarriageRole.EXIT, FACE_PLUS_X));
    }

    @Test
    @DisplayName("Looking across the corridor goes to whichever end you are nearer")
    void perpendicularFollowsTheBlock() {
        // Perpendicular is the tie the sweep is built around: before the middle the train has it,
        // after the middle the room does. This is the direction with no undecided answer left.
        assertEquals(Verdict.ORIGINAL,
            PortalFacing.verdict(1.5, LENGTH, PortalCarriageRole.ENTRY, FACE_ACROSS));
        assertEquals(Verdict.ORIGINAL,
            PortalFacing.verdict(1.5, LENGTH, PortalCarriageRole.ENTRY, 180f));
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(6.5, LENGTH, PortalCarriageRole.ENTRY, FACE_ACROSS));
        assertEquals(Verdict.COPY,
            PortalFacing.verdict(6.5, LENGTH, PortalCarriageRole.ENTRY, 180f));
    }

    // ---- both corridor kinds --------------------------------------------------

    @Test
    @DisplayName("Both kinds sweep the same 30→150 over their own length")
    void bothKindsSweepTheirOwnLength() {
        assertEquals(PortalFacing.thresholdAt(0, LENGTH),
            PortalFacing.thresholdAt(0, LONG_LENGTH), 1e-9);
        assertEquals(PortalFacing.thresholdAt(LENGTH - 1, LENGTH),
            PortalFacing.thresholdAt(LONG_LENGTH - 1, LONG_LENGTH), 1e-9);
        assertEquals(PortalFacing.thresholdAt((LENGTH - 1) / 2.0, LENGTH),
            PortalFacing.thresholdAt((LONG_LENGTH - 1) / 2.0, LONG_LENGTH), 1e-9);
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
    @DisplayName("The room is +X from an ENTRY corridor and -X from an EXIT one")
    void axisMirrors() {
        assertEquals(1, PortalFacing.axisTowardRoom(PortalCarriageRole.ENTRY));
        assertEquals(-1, PortalFacing.axisTowardRoom(PortalCarriageRole.EXIT));
    }
}
