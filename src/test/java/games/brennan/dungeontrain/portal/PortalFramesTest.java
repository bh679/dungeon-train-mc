package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-math tests for {@link PortalFrames} — the carriage↔twin mapping that replaces the
 * free-standing portal's fixed {@code deltaY} when one copy is riding a moving train.
 *
 * <p>Test geometry: a default 9×7×7 carriage, its corridor origin at world {@code (100, 78, 0)},
 * and the static twin 96 blocks above at {@code (100, 174, 0)}. Midpoint is local X 4.5.</p>
 */
final class PortalFramesTest {

    private static final PortalCarriageLayout LAYOUT = new PortalCarriageLayout(9, 7, 7);

    private static final double CAR_X = 100, CAR_Y = 78, CAR_Z = 0;
    private static final double TWIN_X = 100, TWIN_Y = 174, TWIN_Z = 0;

    /** Local Y/Z of a player standing on the walkway, used for every position in these tests. */
    private static final double FEET_Y = 1;
    private static final double WALK_Z = 3;

    private static PortalFrames frames(double carriageX) {
        return new PortalFrames(LAYOUT,
            new PortalFrames.Origin(carriageX, CAR_Y, CAR_Z),
            new PortalFrames.Origin(TWIN_X, TWIN_Y, TWIN_Z));
    }

    private static PortalFrames frames() {
        return frames(CAR_X);
    }

    // ---- frame identification -------------------------------------------------

    @Test
    @DisplayName("frameAt tells the two corridors apart and rejects positions in neither")
    void frameAt() {
        PortalFrames f = frames();
        assertEquals(PortalFrames.FRAME_CARRIAGE, f.frameAt(CAR_X + 3, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
        assertEquals(PortalFrames.FRAME_TWIN, f.frameAt(TWIN_X + 6, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z));
        assertEquals(PortalFrames.FRAME_NONE, f.frameAt(CAR_X + 3, CAR_Y + 40, CAR_Z + WALK_Z));
        assertEquals(PortalFrames.FRAME_NONE, f.frameAt(CAR_X + 40, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
    }

    // ---- the invariant --------------------------------------------------------

    @Test
    @DisplayName("a position already in the frame its side demands needs no move")
    void settledPositionsDoNotMove() {
        PortalFrames f = frames();
        assertNull(f.requiredMove(CAR_X + 2, CAR_Y + FEET_Y, CAR_Z + WALK_Z));    // before mid, on train
        assertNull(f.requiredMove(TWIN_X + 7, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z)); // past mid, in twin
    }

    @Test
    @DisplayName("crossing the midpoint on the train lands in the twin at the same corridor offset")
    void crossingIntoTwinPreservesLocalOffset() {
        PortalFrames f = frames();
        double localX = 5.25;                       // just past the 4.5 midpoint

        PortalFrames.Move move = f.requiredMove(CAR_X + localX, CAR_Y + FEET_Y, CAR_Z + WALK_Z);

        assertNotNull(move);
        assertEquals(PortalFrames.FRAME_TWIN, move.toFrame());
        assertEquals(TWIN_X + localX, move.x(), 1e-9);
        assertEquals(TWIN_Y + FEET_Y, move.y(), 1e-9);
        assertEquals(TWIN_Z + WALK_Z, move.z(), 1e-9);
    }

    /**
     * The live-return property, and the whole reason this is a frame mapping rather than an offset:
     * the train keeps moving while the player is away, so walking back must put them wherever the
     * carriage is <i>now</i>, not where it was when they left it.
     */
    @Test
    @DisplayName("walking back rejoins the train at its CURRENT position, not where it was")
    void returnFollowsTheMovingCarriage() {
        double localX = 2.5;                         // before the midpoint: belongs on the train
        double movedCarriageX = CAR_X + 640;         // the train has travelled while you were away

        PortalFrames.Move move = frames(movedCarriageX)
            .requiredMove(TWIN_X + localX, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z);

        assertNotNull(move);
        assertEquals(PortalFrames.FRAME_CARRIAGE, move.toFrame());
        assertEquals(movedCarriageX + localX, move.x(), 1e-9);
        assertEquals(CAR_Y + FEET_Y, move.y(), 1e-9);
    }

    @Test
    @DisplayName("applying a move twice is a no-op — the rule is idempotent, not an event")
    void idempotent() {
        PortalFrames f = frames();
        PortalFrames.Move move = f.requiredMove(CAR_X + 5.25, CAR_Y + FEET_Y, CAR_Z + WALK_Z);

        assertNotNull(move);
        assertNull(f.requiredMove(move.x(), move.y(), move.z()));
    }

    @Test
    @DisplayName("the midpoint tie resolves to the carriage side, so only one branch can fire")
    void midpointTie() {
        PortalFrames f = frames();
        assertNull(f.requiredMove(CAR_X + LAYOUT.midX(), CAR_Y + FEET_Y, CAR_Z + WALK_Z));

        PortalFrames.Move move = f.requiredMove(TWIN_X + LAYOUT.midX(), TWIN_Y + FEET_Y, TWIN_Z + WALK_Z);
        assertNotNull(move);
        assertEquals(PortalFrames.FRAME_CARRIAGE, move.toFrame());
    }

    // ---- simulated walk on a moving train -------------------------------------

    /**
     * Walk the corridor while the train advances underneath, applying the invariant each tick the
     * way {@code PortalCarriageEvents} does. The player's world X gains both their own pace and the
     * carriage's motion while they are aboard; once in the static twin, only their pace.
     */
    @Test
    @DisplayName("walking a moving carriage's corridor swaps exactly once")
    void walkOnMovingTrainSwapsOnce() {
        double carriageX = CAR_X;
        double trainPerTick = 0.1;      // 2 m/s at 20 TPS
        double walkPerTick = 0.22;

        double px = CAR_X + 1;          // just inside the entrance
        double py = CAR_Y + FEET_Y;
        double pz = CAR_Z + WALK_Z;
        int swaps = 0;

        for (int tick = 0; tick < 200; tick++) {
            PortalFrames f = frames(carriageX);
            boolean aboard = f.frameAt(px, py, pz) == PortalFrames.FRAME_CARRIAGE;

            // The carriage carries its riders along; in the static twin it does not.
            px += walkPerTick + (aboard ? trainPerTick : 0.0);
            carriageX += trainPerTick;

            PortalFrames after = frames(carriageX);
            PortalFrames.Move move = after.requiredMove(px, py, pz);
            if (move != null) {
                px = move.x();
                py = move.y();
                pz = move.z();
                swaps++;
                assertNull(after.requiredMove(px, py, pz), "not settled after one move at tick " + tick);
            }
        }

        assertEquals(1, swaps);
        // Ended up in the twin, which does not move with the train.
        assertEquals(TWIN_Y + FEET_Y, py, 1e-9);
    }
}
